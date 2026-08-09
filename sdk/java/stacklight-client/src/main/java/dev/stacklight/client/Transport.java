package dev.stacklight.client;

import java.time.Duration;
import java.util.List;

/** Delivers a batch. Separated from the dispatcher so the retry logic can be tested without a network. */
public interface Transport {

    /**
     * Result of one delivery attempt.
     *
     * @param delivered the collector accepted the batch
     * @param retryable worth trying again; false for a rejection that will not change, such
     *     as a bad key or a malformed body, where retrying only wastes attempts
     */
    record Result(boolean delivered, boolean retryable, String detail) {

        public static Result ok() {
            return new Result(true, false, null);
        }

        public static Result retry(String detail) {
            return new Result(false, true, detail);
        }

        public static Result reject(String detail) {
            return new Result(false, false, detail);
        }
    }

    /**
     * @param timeout budget for this attempt. Passed in rather than read from the options
     *     so that a shutdown flush can shorten it: otherwise an attempt started just
     *     inside the flush deadline runs its full request timeout past it, and the
     *     shutdown bound quietly becomes the sum of the two.
     */
    Result send(List<StacklightEvent> batch, Duration timeout);
}
