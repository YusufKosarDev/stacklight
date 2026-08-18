package dev.stacklight.client;

import java.time.Duration;
import java.util.List;

/** Delivers a batch. Separated from the dispatcher so the retry logic can be tested without a network. */
public interface Transport {

    /** An event the collector refused for a reason another attempt cannot change. */
    record Discarded(StacklightEvent event, String reason) {}

    /**
     * Result of one delivery attempt.
     *
     * <p>{@code delivered} is about the request rather than the events: it says the
     * collector answered and the body was understood. What became of each event is in the
     * three fields below, because a batch can be partly accepted and the parts have to be
     * treated differently.
     *
     * <p>There is no longer a {@code deliveredBeforeFailure}. It existed because the wire
     * format was one event per request, so a batch of twenty was twenty requests that could
     * stop at the seventh, and re-sending the first six would have spent a round trip each
     * to be told by the collector's duplicate check that it already had them. A batch is one
     * request now: either it happened and the collector said what became of every event, or
     * it did not and the whole batch is still owed at the cost of one round trip.
     *
     * @param retryable worth trying again; false for a rejection that will not change, such
     *     as a bad key or a malformed body
     * @param accepted events the collector took, including ones it already had
     * @param discarded events it refused permanently, with the reason it gave
     * @param pending events still owed, to go back on the queue
     */
    record Result(
            boolean delivered,
            boolean retryable,
            String detail,
            int accepted,
            List<Discarded> discarded,
            List<StacklightEvent> pending) {

        public static Result ok(int accepted) {
            return new Result(true, false, null, accepted, List.of(), List.of());
        }

        public static Result ok(
                int accepted, List<Discarded> discarded, List<StacklightEvent> pending) {
            return new Result(true, false, null, accepted, List.copyOf(discarded), List.copyOf(pending));
        }

        public static Result retry(String detail, List<StacklightEvent> pending) {
            return new Result(false, true, detail, 0, List.of(), List.copyOf(pending));
        }

        public static Result reject(String detail, List<StacklightEvent> pending) {
            return new Result(false, false, detail, 0, List.of(), List.copyOf(pending));
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
