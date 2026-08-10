package dev.stacklight.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one thread that talks to the collector.
 *
 * <p>Everything the application does with this client is an enqueue. Delivery, retries and
 * the waiting in between happen here, on a daemon thread, so that a collector taking a
 * hundred seconds to wake is invisible to the request that reported the error.
 */
final class Dispatcher {

    private static final long POLL_WAIT_MILLIS = 500;

    private final EventQueue queue;
    private final Transport transport;
    private final StacklightOptions options;
    private final Logger logger;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failedAttempts = new AtomicLong();
    private final AtomicLong givenUp = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    private volatile boolean running = true;
    private Thread worker;

    Dispatcher(EventQueue queue, Transport transport, StacklightOptions options, Logger logger) {
        this.queue = queue;
        this.transport = transport;
        this.options = options;
        this.logger = logger;
    }

    void start() {
        worker = new Thread(this::loop, "stacklight-dispatcher");
        // Daemon: an application that has finished its work must not be held open by its
        // error reporter. Anything still queued is dealt with by the shutdown flush.
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        int consecutiveFailures = 0;

        while (running) {
            try {
                List<StacklightEvent> batch = queue.drain(options.batchSize(), POLL_WAIT_MILLIS);
                if (batch.isEmpty()) {
                    continue;
                }

                if (deliver(batch, options.requestTimeout())) {
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                    sleep(backoffMillis(consecutiveFailures));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Nothing this thread does is allowed to kill it. A reporter that stops
                // reporting because of its own bug is the worst outcome available.
                logger.debug(() -> "dispatcher recovered from " + e);
                sleep(backoffMillis(++consecutiveFailures));
            }
        }
    }

    /**
     * Package-private rather than private so one attempt can be driven directly from a
     * test. Going through the worker thread means asserting on whatever the scheduler
     * happened to batch together, which is not the same thing as asserting on the rule.
     *
     * @return true when the batch was delivered or definitively rejected
     */
    boolean deliver(List<StacklightEvent> batch, java.time.Duration timeout) {
        Transport.Result result = transport.send(batch, timeout);

        if (result.delivered()) {
            sent.addAndGet(batch.size());
            return true;
        }

        // A batch is one request per event, so it can stop in the middle. What the
        // collector already took is delivered and counted; only the rest is still owed.
        // Sending the accepted part again would cost a round trip each to be told by the
        // collector's duplicate check that it already had them, and against a 429 it would
        // feed the very problem it is reacting to.
        int accepted = result.deliveredBeforeFailure();
        if (accepted > 0) {
            sent.addAndGet(accepted);
        }
        List<StacklightEvent> remainder =
                new ArrayList<>(batch.subList(accepted, batch.size()));

        failedAttempts.incrementAndGet();
        lastError.set(result.detail());

        if (!result.retryable()) {
            // Retrying will not change the answer, so what is left is discarded rather
            // than kept for ever in front of events that could still be delivered.
            givenUp.addAndGet(remainder.size());
            logger.debug(() -> "discarding " + remainder.size() + " events: " + result.detail());
            return true;
        }

        queue.requeueFront(remainder);
        logger.debug(() -> "requeued " + remainder.size() + " events: " + result.detail());
        return false;
    }

    /**
     * Exponential backoff with full jitter.
     *
     * <p>The jitter is not decoration. Several instances of an application usually fail at
     * the same moment for the same reason, and a fixed schedule would have all of them
     * retry in step, arriving together on a collector that is still waking up and pushing
     * it over again.
     */
    long backoffMillis(int consecutiveFailures) {
        long base = options.retryBaseDelay().toMillis();
        long cap = options.retryMaxDelay().toMillis();
        int exponent = Math.min(consecutiveFailures - 1, 30);
        long ceiling = Math.min(cap, base << exponent);
        return ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends what is queued, on the calling thread, until it is empty or time runs out.
     *
     * <p>The worker is stopped first so that only one thread is delivering. Bounded by a
     * deadline because a shutdown that waits on an unreachable collector is a process that
     * will not exit.
     */
    void flush(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;

        while (true) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) {
                break;
            }

            List<StacklightEvent> batch = queue.drainNow(options.batchSize());
            if (batch.isEmpty()) {
                return;
            }

            // The attempt gets whatever is left, never more. Otherwise a request started
            // just inside the deadline runs its own full timeout past it and the shutdown
            // bound silently becomes the sum of the two.
            java.time.Duration attemptTimeout =
                    java.time.Duration.ofMillis(
                            Math.min(options.requestTimeout().toMillis(), remainingMillis));

            if (!deliver(batch, attemptTimeout)) {
                sleep(Math.min(200, Math.max(0, (deadline - System.nanoTime()) / 1_000_000L)));
            }
        }

        if (!queue.isEmpty()) {
            logger.debug(() -> "shutdown flush ran out of time with " + queue.size() + " events left");
        }
    }

    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    long sent() {
        return sent.get();
    }

    long failedAttempts() {
        return failedAttempts.get();
    }

    long givenUp() {
        return givenUp.get();
    }

    String lastError() {
        return lastError.get();
    }
}
