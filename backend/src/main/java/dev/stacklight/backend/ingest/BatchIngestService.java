package dev.stacklight.backend.ingest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Accepts a queue drain in one request.
 *
 * <h2>One transaction per event, not one per batch</h2>
 *
 * <p>A batch here is a client emptying its queue, not a unit of work. The events in it are
 * independent failure reports, possibly from different moments and different code paths in
 * the same application, and nothing about them is jointly meaningful. Wrapping them in one
 * transaction would invent a coupling the domain does not have.
 *
 * <p>It would also make failure expensive. A database error on the eighty-seventh event of
 * a hundred would roll back the eighty-six that had already succeeded, and the client --
 * which cannot know which ones those were -- would send all hundred again. Per event, the
 * eighty-six stay, the client is told exactly which ones did not, and the retry is the
 * remainder. Re-sending is safe either way, because every event carries its own id and the
 * insert is {@code on conflict do nothing}; the difference is how much work is repeated.
 *
 * <p>It is worth being precise about what this does not buy: <b>the number of transactions
 * is unchanged</b>. Twenty events are twenty transactions before and after. What collapses
 * is twenty HTTP round trips into one, twenty connection acquisitions into one, and twenty
 * follow-up passes into one.
 *
 * <h2>Every event is validated, and a bad one costs only itself</h2>
 *
 * <p>Validation runs per event rather than over the batch, so one message that outgrew its
 * limit does not discard the nineteen around it. That also makes the reported outcome
 * meaningful: a validation failure cannot be fixed by trying again and is reported as not
 * retryable, while anything that went wrong talking to the database is. A client can act on
 * that without parsing prose -- requeue what is retryable, drop and count what is not.
 */
@Service
public class BatchIngestService {

    /**
     * Both clients default to batches of twenty and cap their queues at 512, so a hundred
     * is five times what either sends and a fifth of what either could hold. Above that the
     * arithmetic stops being about queues: an event carries up to 4,000 characters of
     * message and 20,000 of stack trace, so a hundred is roughly 2.5 MB of request against
     * an instance with 512 MB for everything.
     */
    public static final int MAX_EVENTS = 100;

    private final IngestService ingestService;
    private final Validator validator;

    BatchIngestService(IngestService ingestService, Validator validator) {
        this.ingestService = ingestService;
        this.validator = validator;
    }

    /**
     * @param error null when the event was accepted
     * @param retryable whether sending this event again could change the outcome; false for
     *     anything the client would have to fix first
     */
    public record EventOutcome(
            UUID eventId,
            boolean stored,
            String fingerprint,
            Long groupId,
            boolean sampled,
            boolean regressed,
            String error,
            boolean retryable) {

        static EventOutcome rejected(UUID eventId, String error) {
            return new EventOutcome(eventId, false, null, null, false, false, error, false);
        }

        static EventOutcome failed(UUID eventId, String error) {
            return new EventOutcome(eventId, false, null, null, false, false, error, true);
        }
    }

    /**
     * @param accepted events in the request
     * @param stored events written; duplicates and failures are not counted here
     */
    public record BatchResult(
            int accepted, int stored, int duplicates, int failed, List<EventOutcome> results) {}

    public BatchResult ingestAll(List<IngestRequest> requests) {
        List<EventOutcome> outcomes = new ArrayList<>(requests.size());
        int stored = 0;

        for (IngestRequest request : requests) {
            UUID eventId = request.eventId() != null ? request.eventId() : UUID.randomUUID();

            String invalid = firstViolation(request);
            if (invalid != null) {
                outcomes.add(EventOutcome.rejected(eventId, invalid));
                continue;
            }

            try {
                // Through the proxy on purpose: each call is its own transaction, and a
                // failure below rolls back that event and nothing else.
                IngestService.Result result =
                        ingestService.ingestWithoutFollowUp(eventId, request);

                outcomes.add(
                        new EventOutcome(
                                eventId,
                                result.stored(),
                                result.fingerprint() == null ? null : result.fingerprint().hash(),
                                result.groupId(),
                                result.sampled(),
                                result.regressed(),
                                null,
                                false));
                if (result.stored()) {
                    stored++;
                }
            } catch (DataAccessException e) {
                // Retryable by construction: everything a caller could have fixed was
                // rejected above, so what is left is the database being unavailable or
                // unhappy, and the same event may well land on the next attempt.
                outcomes.add(EventOutcome.failed(eventId, describe(e)));
            }
        }

        // Once for the batch. See IngestService#followUp for why the count matters.
        if (stored > 0) {
            ingestService.followUp(stored);
        }

        int failed = (int) outcomes.stream().filter(o -> o.error() != null).count();
        int duplicates = (int) outcomes.stream().filter(o -> o.error() == null && !o.stored()).count();

        return new BatchResult(requests.size(), stored, duplicates, failed, outcomes);
    }

    /** @return the first constraint message, or null when the event is valid */
    private String firstViolation(IngestRequest request) {
        Set<ConstraintViolation<IngestRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        // Sorted so the same invalid event always reports the same reason: the set has no
        // order, and a message that changes between identical requests is a message nobody
        // can write a test against.
        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList())
                .get(0);
    }

    /** Class name and message only: the cause chain can carry connection strings. */
    private static String describe(DataAccessException e) {
        return e.getClass().getSimpleName();
    }
}
