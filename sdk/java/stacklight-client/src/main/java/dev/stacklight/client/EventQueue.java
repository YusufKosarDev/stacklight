package dev.stacklight.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded hand-off between the threads that fail and the thread that reports.
 *
 * <h2>What is dropped when it is full</h2>
 *
 * The queue only fills when the collector cannot be reached, which on this deployment
 * means it is asleep and waking. When it comes back, the most useful thing to send is
 * what is happening <i>now</i>, so the oldest event is discarded to make room.
 *
 * <p>The cost is real: the first occurrence of an incident is the first to be thrown
 * away. It is accepted because the collector groups by fingerprint, so a later event of
 * the same fault opens the same group — losing the earliest copy costs a count and a
 * timestamp, while dropping the newest would mean that during a sustained burst nothing
 * recent ever gets through, which is worse.
 *
 * <p>Either way the loss is counted rather than silent. A reporter that quietly discards
 * things is worse than one that says it discarded them.
 */
final class EventQueue {

    private final Deque<StacklightEvent> events = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final int capacity;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    EventQueue(int capacity) {
        this.capacity = capacity;
    }

    /** Never blocks and never rejects: at capacity it makes room by dropping the oldest. */
    void offer(StacklightEvent event) {
        lock.lock();
        try {
            while (events.size() >= capacity) {
                events.pollFirst();
                dropped.incrementAndGet();
            }
            events.addLast(event);
            accepted.incrementAndGet();
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes up to {@code max} events, waiting a while if there are none.
     *
     * @return an empty list when the wait elapsed with nothing to send
     */
    List<StacklightEvent> drain(int max, long waitMillis) throws InterruptedException {
        lock.lock();
        try {
            if (events.isEmpty()) {
                notEmpty.await(waitMillis, TimeUnit.MILLISECONDS);
            }
            List<StacklightEvent> batch = new ArrayList<>(Math.min(max, events.size()));
            while (batch.size() < max && !events.isEmpty()) {
                batch.add(events.pollFirst());
            }
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /** Takes everything available without waiting. */
    List<StacklightEvent> drainNow(int max) {
        lock.lock();
        try {
            List<StacklightEvent> batch = new ArrayList<>(Math.min(max, events.size()));
            while (batch.size() < max && !events.isEmpty()) {
                batch.add(events.pollFirst());
            }
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a failed batch to the front, keeping its order.
     *
     * <p>A batch that could not be delivered has not been reported, so it goes back where
     * it was rather than being counted as sent. If the queue filled while it was in
     * flight, the usual rule applies and the oldest gives way.
     */
    void requeueFront(List<StacklightEvent> batch) {
        lock.lock();
        try {
            ListIterator<StacklightEvent> backwards = batch.listIterator(batch.size());
            while (backwards.hasPrevious()) {
                StacklightEvent event = backwards.previous();
                if (events.size() >= capacity) {
                    events.pollLast();
                    dropped.incrementAndGet();
                }
                events.addFirst(event);
            }
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return events.size();
        } finally {
            lock.unlock();
        }
    }

    boolean isEmpty() {
        return size() == 0;
    }

    long accepted() {
        return accepted.get();
    }

    long dropped() {
        return dropped.get();
    }
}
