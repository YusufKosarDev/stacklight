package dev.stacklight.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventQueueTests {

    private static StacklightEvent event(String id) {
        return new StacklightEvent(id, "svc", "ERROR", "boom", "java", "E", null, null);
    }

    @Test
    void deliversInTheOrderThingsFailed() throws Exception {
        EventQueue queue = new EventQueue(10);
        queue.offer(event("1"));
        queue.offer(event("2"));
        queue.offer(event("3"));

        assertThat(queue.drain(10, 0)).extracting(StacklightEvent::eventId).containsExactly("1", "2", "3");
    }

    @Test
    void atCapacityTheOldestGivesWay() {
        // The queue only fills when the collector cannot be reached. When it comes back,
        // what is happening now is worth more than what was happening two minutes ago.
        EventQueue queue = new EventQueue(3);
        queue.offer(event("1"));
        queue.offer(event("2"));
        queue.offer(event("3"));
        queue.offer(event("4"));

        assertThat(queue.drainNow(10))
                .extracting(StacklightEvent::eventId)
                .containsExactly("2", "3", "4");
        assertThat(queue.dropped()).isEqualTo(1);
    }

    @Test
    void offeringNeverBlocksOrThrowsHoweverFullItIs() {
        EventQueue queue = new EventQueue(2);

        for (int i = 0; i < 10_000; i++) {
            queue.offer(event(String.valueOf(i)));
        }

        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.accepted()).isEqualTo(10_000);
        assertThat(queue.dropped()).isEqualTo(9_998);
    }

    @Test
    void everyDropIsCounted() {
        // A reporter that quietly discards things is worse than one that says so.
        EventQueue queue = new EventQueue(5);
        for (int i = 0; i < 12; i++) {
            queue.offer(event(String.valueOf(i)));
        }

        assertThat(queue.accepted() - queue.dropped()).isEqualTo(queue.size());
    }

    @Test
    void aFailedBatchGoesBackWhereItWas() {
        EventQueue queue = new EventQueue(10);
        queue.offer(event("1"));
        queue.offer(event("2"));
        queue.offer(event("3"));

        List<StacklightEvent> batch = queue.drainNow(2);
        queue.offer(event("4"));
        queue.requeueFront(batch);

        assertThat(queue.drainNow(10))
                .extracting(StacklightEvent::eventId)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void requeueingIntoAFullQueueStillRespectsTheBound() {
        EventQueue queue = new EventQueue(3);
        queue.offer(event("1"));
        queue.offer(event("2"));
        List<StacklightEvent> batch = queue.drainNow(2);

        queue.offer(event("3"));
        queue.offer(event("4"));
        queue.offer(event("5"));
        queue.requeueFront(batch);

        assertThat(queue.size()).isEqualTo(3);
        assertThat(queue.drainNow(10))
                .extracting(StacklightEvent::eventId)
                .containsExactly("1", "2", "3");
    }

    @Test
    void drainingAnEmptyQueueWaitsRatherThanSpinning() throws Exception {
        EventQueue queue = new EventQueue(10);

        long start = System.nanoTime();
        List<StacklightEvent> batch = queue.drain(10, 120);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(batch).isEmpty();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(100);
    }

    @Test
    void aWaitingDrainWakesAsSoonAsSomethingArrives() throws Exception {
        EventQueue queue = new EventQueue(10);
        Thread producer =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            queue.offer(event("1"));
                        });
        producer.start();

        long start = System.nanoTime();
        List<StacklightEvent> batch = queue.drain(10, 5_000);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        producer.join();
        assertThat(batch).hasSize(1);
        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    void concurrentProducersLoseNothingTheyWereNotToldAbout() throws Exception {
        EventQueue queue = new EventQueue(10_000);
        int threads = 8;
        int perThread = 500;

        List<Thread> producers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread producer =
                    new Thread(
                            () -> {
                                for (int i = 0; i < perThread; i++) {
                                    queue.offer(event(id + "-" + i));
                                }
                            });
            producers.add(producer);
            producer.start();
        }
        for (Thread producer : producers) {
            producer.join();
        }

        assertThat(queue.accepted()).isEqualTo((long) threads * perThread);
        assertThat(queue.dropped()).isZero();
        assertThat(queue.size()).isEqualTo(threads * perThread);
    }
}
