package dev.stacklight.backend.retention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The window is a pure function of storage use, so it is tested as one. */
class RetentionWindowTests {

    private static final long MB = 1024L * 1024L;

    private final RetentionProperties properties =
            new RetentionProperties(14, 7, 3, 300 * MB, 400 * MB, 5000, 200, 10);

    private final RetentionService service = new RetentionService(null, properties);

    @Test
    void keepsTheFullWindowWhileStorageIsComfortable() {
        assertThat(service.windowFor(0)).isEqualTo(14);
        assertThat(service.windowFor(299 * MB)).isEqualTo(14);
    }

    @Test
    void tightensOnceStorageIsHigh() {
        assertThat(service.windowFor(300 * MB)).isEqualTo(7);
        assertThat(service.windowFor(399 * MB)).isEqualTo(7);
    }

    @Test
    void tightensFurtherNearTheLimit() {
        // The plan suspends the project when storage runs out rather than charging for
        // the overage, so the last band trades history for staying alive.
        assertThat(service.windowFor(400 * MB)).isEqualTo(3);
        assertThat(service.windowFor(500 * MB)).isEqualTo(3);
    }

    @Test
    void widensAgainWhenPressureIsGone() {
        assertThat(service.windowFor(450 * MB)).isEqualTo(3);
        assertThat(service.windowFor(100 * MB)).isEqualTo(14);
    }

    /**
     * A sweep that fails has to back off like one that succeeded.
     *
     * <p>Both triggers are counters reset by a completed sweep. Resetting them only on the
     * success path left a broken sweep permanently armed: the age trigger never moved off
     * its last success and the volume counter stayed parked above its threshold, so every
     * event after the first failure paid for another attempt and another stack trace in
     * the log. Retention being broken is bad enough without it also being loud.
     */
    @Test
    void aFailingSweepBacksOffRatherThanRetryingOnEveryEvent() {
        CountingRetentionService failing = new CountingRetentionService(properties);

        failing.onEventStored();
        failing.onEventStored();
        failing.onEventStored();

        assertThat(failing.attempts).isEqualTo(1);
    }

    /** Counts attempts. The null {@code JdbcClient} is what makes every one of them fail. */
    private static final class CountingRetentionService extends RetentionService {

        private int attempts;

        CountingRetentionService(RetentionProperties properties) {
            super(null, properties);
        }

        @Override
        public long sweep(String source) {
            attempts++;
            return super.sweep(source);
        }
    }
}
