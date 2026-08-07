package dev.stacklight.backend.ingest;

import dev.stacklight.backend.grouping.Fingerprint;
import dev.stacklight.backend.grouping.FingerprinterRegistry;
import dev.stacklight.backend.grouping.GroupingInput;
import dev.stacklight.backend.grouping.Platform;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores an event and files it into a group.
 *
 * <p>Grouping runs inline rather than behind a queue. It is a few regular expressions and
 * a hash followed by one upsert, so the latency it adds is small next to the network call
 * that delivered the event, and doing it here means a group is visible the moment its
 * first event lands. A worker becomes worth its complexity when ingest volume outgrows a
 * single free instance, not before.
 */
@Service
public class IngestService {

    private final EventStore eventStore;
    private final GroupStore groupStore;
    private final FingerprinterRegistry fingerprinters;

    IngestService(
            EventStore eventStore, GroupStore groupStore, FingerprinterRegistry fingerprinters) {
        this.eventStore = eventStore;
        this.groupStore = groupStore;
        this.fingerprinters = fingerprinters;
    }

    /**
     * Result of an ingest call.
     *
     * @param stored false when the event id had already been seen
     * @param fingerprint null when the event was a duplicate
     */
    public record Result(boolean stored, Fingerprint fingerprint, Long groupId) {}

    @Transactional
    public Result ingest(UUID eventId, IngestRequest request) {
        Fingerprint fingerprint =
                fingerprinters
                        .active()
                        .compute(
                                new GroupingInput(
                                        request.service(),
                                        Platform.fromWireName(request.platform()),
                                        request.resolvedExceptionType(),
                                        request.message(),
                                        request.resolvedStacktrace()));

        // Order matters. The event is written first so that a repeated event_id is
        // rejected by the unique constraint before the group counter is touched; doing
        // the upsert first would inflate the count every time a client retried a delivery
        // it had already made.
        boolean stored = eventStore.insert(eventId, request, fingerprint.platform().wireName());
        if (!stored) {
            return new Result(false, null, null);
        }

        long groupId =
                groupStore.upsert(
                        fingerprint,
                        request.service(),
                        request.level(),
                        request.resolvedExceptionType());
        eventStore.linkToGroup(eventId, groupId, fingerprint);

        return new Result(true, fingerprint, groupId);
    }
}
