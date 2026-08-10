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
     * @param deliveredBeforeFailure how many events from the front of the batch the
     *     collector had already accepted when the attempt failed. Only meaningful while
     *     {@code delivered} is false, and it is what keeps a partial failure from being
     *     treated as a total one: the wire format is one event per request, so a batch can
     *     stop halfway, and re-sending the accepted half would spend a round trip each to
     *     be told by the collector's duplicate check that it already had them.
     */
    record Result(boolean delivered, boolean retryable, String detail, int deliveredBeforeFailure) {

        public static Result ok() {
            return new Result(true, false, null, 0);
        }

        public static Result retry(String detail) {
            return new Result(false, true, detail, 0);
        }

        public static Result reject(String detail) {
            return new Result(false, false, detail, 0);
        }

        /** The same outcome, recording how much of the batch got through before it. */
        public Result after(int deliveredBeforeFailure) {
            return new Result(delivered, retryable, detail, deliveredBeforeFailure);
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
