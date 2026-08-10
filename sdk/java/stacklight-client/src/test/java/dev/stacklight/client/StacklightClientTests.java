package dev.stacklight.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StacklightClientTests {

    /** A transport that can be told how to behave, and remembers what it was given. */
    private static final class FakeTransport implements Transport {
        final List<StacklightEvent> delivered = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger attempts = new AtomicInteger();
        volatile int failuresRemaining = 0;
        volatile boolean rejectEverything = false;
        volatile long delayMillis = 0;
        volatile CountDownLatch arrived = new CountDownLatch(0);

        volatile java.time.Duration lastTimeout;

        @Override
        public Result send(List<StacklightEvent> batch, java.time.Duration timeout) {
            attempts.incrementAndGet();
            lastTimeout = timeout;
            if (delayMillis > 0) {
                try {
                    // Honours the budget it was given, the way a real request does.
                    Thread.sleep(Math.min(delayMillis, timeout.toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (rejectEverything) {
                return Result.reject("401");
            }
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return Result.retry("simulated");
            }
            delivered.addAll(batch);
            arrived.countDown();
            return Result.ok();
        }
    }

    /**
     * Accepts events one at a time, the way the wire format really works, and stops after
     * a chosen number. Records everything it was handed, so a redelivery is visible.
     */
    private static final class PerEventTransport implements Transport {
        final List<String> handed = Collections.synchronizedList(new ArrayList<>());
        final List<String> accepted = Collections.synchronizedList(new ArrayList<>());
        /** How many events this transport will take before failing, per attempt. */
        volatile int acceptsPerAttempt = Integer.MAX_VALUE;
        volatile CountDownLatch arrived = new CountDownLatch(0);

        @Override
        public Result send(List<StacklightEvent> batch, java.time.Duration timeout) {
            int delivered = 0;
            for (StacklightEvent event : batch) {
                handed.add(event.eventId());
                if (delivered >= acceptsPerAttempt) {
                    return Result.retry("collector stopped answering").after(delivered);
                }
                accepted.add(event.eventId());
                delivered++;
            }
            arrived.countDown();
            return Result.ok();
        }
    }

    private static StacklightOptions options() {
        return new StacklightOptions()
                .endpoint("https://collector.invalid/api/events")
                .apiKey("test-key")
                .service("checkout-api")
                .release("1.4.0")
                .retryBaseDelay(Duration.ofMillis(10))
                .retryMaxDelay(Duration.ofMillis(50))
                .shutdownTimeout(Duration.ofSeconds(2));
    }

    @Test
    void captureReturnsImmediatelyEvenWhenDeliveryIsSlow() throws Exception {
        // The measured cold start of the collector is between 95 and 114 seconds. If any
        // of that reached the caller, this client would be worse than no client.
        FakeTransport transport = new FakeTransport();
        transport.delayMillis = 2_000;

        try (StacklightClient client = StacklightClient.start(options(), transport)) {
            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                client.capture(new IllegalStateException("boom " + i));
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMillis).isLessThan(200);
        }
    }

    @Test
    void anEventThatFailsTwiceIsStillDelivered() throws Exception {
        // A single failed attempt is not a lost event; the first request against a
        // sleeping collector is expected to fail.
        FakeTransport transport = new FakeTransport();
        transport.failuresRemaining = 2;
        transport.arrived = new CountDownLatch(1);

        try (StacklightClient client = StacklightClient.start(options(), transport)) {
            client.capture(new IllegalStateException("boom"));

            assertThat(transport.arrived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.delivered).hasSize(1);
            assertThat(transport.attempts.get()).isEqualTo(3);
        }
    }

    @Test
    void aRejectionIsNotRetriedForEver() throws Exception {
        // A wrong key does not become right by being tried again, and a batch that
        // cannot be delivered must not sit in front of ones that could.
        FakeTransport transport = new FakeTransport();
        transport.rejectEverything = true;

        try (StacklightClient client = StacklightClient.start(options(), transport)) {
            client.capture(new IllegalStateException("boom"));
            Thread.sleep(500);

            assertThat(transport.attempts.get()).isEqualTo(1);
            assertThat(client.stats().dropped()).isEqualTo(1);
            assertThat(client.stats().lastError()).contains("401");
        }
    }

    @Test
    void aBatchThatStopsHalfwayOnlyResendsTheHalfThatDidNotArrive() throws Exception {
        // One event per request means a batch can stop in the middle. Treating that as a
        // total failure sent the accepted half again, to be told by the collector's
        // duplicate check that it already had them -- a round trip each to learn nothing,
        // and with a 429 the retry makes the very problem it is reacting to worse.
        PerEventTransport transport = new PerEventTransport();
        transport.acceptsPerAttempt = 3;
        transport.arrived = new CountDownLatch(1);

        try (StacklightClient client =
                StacklightClient.start(options().batchSize(10), transport)) {
            for (int i = 0; i < 8; i++) {
                client.capture(new IllegalStateException("boom " + i));
            }

            // Let it recover so the remainder gets through and the run can be totalled.
            Thread.sleep(200);
            transport.acceptsPerAttempt = Integer.MAX_VALUE;
            assertThat(transport.arrived.await(3, TimeUnit.SECONDS)).isTrue();

            // Every event exactly once, and no event handed over twice.
            assertThat(transport.accepted).hasSize(8).doesNotHaveDuplicates();
            assertThat(transport.handed).doesNotHaveDuplicates();
        }
    }

    @Test
    void aPartialDeliveryStillBalancesTheBooks() throws Exception {
        PerEventTransport transport = new PerEventTransport();
        transport.acceptsPerAttempt = 2;

        try (StacklightClient client =
                StacklightClient.start(options().batchSize(10), transport)) {
            for (int i = 0; i < 5; i++) {
                client.capture(new IllegalStateException("boom " + i));
            }
            Thread.sleep(300);

            // The invariant the whole client rests on: nothing is unaccounted for, and
            // what the collector took is counted as sent rather than still owed.
            StacklightStats stats = client.stats();
            assertThat(stats.accepted()).isEqualTo(stats.sent() + stats.dropped() + stats.queued());
            assertThat(stats.sent()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void aFullQueueDropsTheOldestAndSaysSo() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failuresRemaining = Integer.MAX_VALUE;

        try (StacklightClient client =
                StacklightClient.start(options().queueCapacity(10), transport)) {
            for (int i = 0; i < 1_000; i++) {
                client.capture(new IllegalStateException("boom " + i));
            }

            StacklightStats stats = client.stats();
            assertThat(stats.accepted()).isEqualTo(1_000);
            assertThat(stats.dropped()).isGreaterThan(900);
            assertThat(stats.queued()).isLessThanOrEqualTo(10);
        }
    }

    @Test
    void closingSendsWhatIsStillQueued() throws Exception {
        FakeTransport transport = new FakeTransport();
        StacklightClient client = StacklightClient.start(options(), transport);

        for (int i = 0; i < 5; i++) {
            client.capture(new IllegalStateException("boom " + i));
        }
        client.close();

        assertThat(transport.delivered).hasSize(5);
        assertThat(client.stats().queued()).isZero();
    }

    @Test
    void aShutdownFlushGivesUpRatherThanHoldingTheProcessOpen() {
        // An application that has finished must be allowed to exit. A collector that is
        // not answering cannot be a reason to hang.
        FakeTransport transport = new FakeTransport();
        transport.failuresRemaining = Integer.MAX_VALUE;

        StacklightClient client =
                StacklightClient.start(options().shutdownTimeout(Duration.ofMillis(300)), transport);
        client.capture(new IllegalStateException("boom"));

        long start = System.nanoTime();
        client.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @Test
    void theShutdownBoundHoldsEvenWhenEachAttemptIsSlow() {
        // The bound has to be the shutdown timeout, not the shutdown timeout plus one
        // request timeout. An attempt started just inside the deadline must be given only
        // what is left, or a three second promise quietly becomes eight.
        FakeTransport transport = new FakeTransport();
        transport.failuresRemaining = Integer.MAX_VALUE;
        transport.delayMillis = 5_000;

        StacklightClient client =
                StacklightClient.start(
                        options()
                                .shutdownTimeout(Duration.ofMillis(500))
                                .requestTimeout(Duration.ofSeconds(5)),
                        transport);
        client.capture(new IllegalStateException("boom"));

        long start = System.nanoTime();
        client.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(1_500);
    }

    @Test
    void aFlushAttemptIsGivenOnlyTheTimeThatIsLeft() {
        FakeTransport transport = new FakeTransport();
        transport.failuresRemaining = Integer.MAX_VALUE;

        StacklightClient client =
                StacklightClient.start(
                        options()
                                .shutdownTimeout(Duration.ofMillis(200))
                                .requestTimeout(Duration.ofSeconds(30)),
                        transport);
        client.capture(new IllegalStateException("boom"));
        client.close();

        assertThat(transport.lastTimeout).isLessThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void withNoEndpointConfiguredTheClientIsInertRatherThanBroken() {
        // An application should be able to run somewhere with no collector without that
        // being something it has to handle.
        try (StacklightClient client =
                StacklightClient.start(new StacklightOptions().service("checkout-api"))) {
            client.capture(new IllegalStateException("boom"));
            client.captureMessage("something", "WARN");
            client.flush();

            assertThat(client.stats().accepted()).isZero();
        }
    }

    @Test
    void captureNeverThrowsWhateverItIsGiven() {
        FakeTransport transport = new FakeTransport();

        try (StacklightClient client = StacklightClient.start(options(), transport)) {
            client.capture(null);
            client.captureMessage(null, "ERROR");
            client.captureMessage("   ", "ERROR");
            client.capture(new StackOverflowError("deep"));

            assertThat(client.stats().accepted()).isEqualTo(1);
        }
    }

    @Test
    void eventsCarryAnIdSoARetriedDeliveryIsNotCountedTwice() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failuresRemaining = 1;
        transport.arrived = new CountDownLatch(1);

        try (StacklightClient client = StacklightClient.start(options(), transport)) {
            client.capture(new IllegalStateException("boom"));
            assertThat(transport.arrived.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(transport.delivered).hasSize(1);
        assertThat(transport.delivered.get(0).eventId()).isNotBlank();
    }

    @Test
    void backoffGrowsAndIsCappedAndJittered() {
        Dispatcher dispatcher =
                new Dispatcher(
                        new EventQueue(10),
                        (batch, timeout) -> Transport.Result.ok(),
                        options().retryBaseDelay(Duration.ofSeconds(1)).retryMaxDelay(Duration.ofSeconds(30)),
                        new Logger(false));

        // Full jitter: each wait is somewhere between zero and the growing ceiling, so
        // instances failing together do not come back in step.
        for (int attempt = 1; attempt <= 12; attempt++) {
            long ceiling = Math.min(30_000, 1_000L << Math.min(attempt - 1, 30));
            for (int sample = 0; sample < 50; sample++) {
                assertThat(dispatcher.backoffMillis(attempt)).isBetween(0L, ceiling);
            }
        }

        boolean sawSpread = false;
        long first = dispatcher.backoffMillis(8);
        for (int i = 0; i < 50 && !sawSpread; i++) {
            sawSpread = dispatcher.backoffMillis(8) != first;
        }
        assertThat(sawSpread).isTrue();
    }
}
