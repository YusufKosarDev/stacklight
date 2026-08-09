package dev.stacklight.backend.ingest;

import dev.stacklight.backend.grouping.Fingerprint;
import dev.stacklight.backend.grouping.FingerprinterRegistry;
import dev.stacklight.backend.grouping.GroupingInput;
import dev.stacklight.backend.grouping.Platform;
import dev.stacklight.backend.retention.RetentionService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stores an event, files it into a group, and counts it into the hour.
 *
 * <p>Grouping and rollup both run inline rather than behind a queue. They are a few
 * regular expressions, a hash and two upserts, so the latency they add is small next to
 * the network call that delivered the event, and a group and its trend are visible the
 * moment the first event lands. A worker becomes worth its complexity when ingest volume
 * outgrows a single free instance, not before.
 */
@Service
public class IngestService {

    private final EventStore eventStore;
    private final GroupStore groupStore;
    private final RollupStore rollupStore;
    private final FingerprinterRegistry fingerprinters;
    private final RetentionService retentionService;
    private final int hourlyCapPerGroup;

    IngestService(
            EventStore eventStore,
            GroupStore groupStore,
            RollupStore rollupStore,
            FingerprinterRegistry fingerprinters,
            RetentionService retentionService,
            @Value("${stacklight.ingest.hourly-cap-per-group:200}") int hourlyCapPerGroup) {
        this.eventStore = eventStore;
        this.groupStore = groupStore;
        this.rollupStore = rollupStore;
        this.fingerprinters = fingerprinters;
        this.retentionService = retentionService;
        this.hourlyCapPerGroup = hourlyCapPerGroup;
    }

    /**
     * Result of an ingest call.
     *
     * @param stored false when the event id had already been seen
     * @param fingerprint null when the event was a duplicate
     * @param sampled true when the event was counted but its detail was not kept
     * @param regressed true when this event brought a resolved group back
     */
    public record Result(
            boolean stored, Fingerprint fingerprint, Long groupId, boolean sampled, boolean regressed) {

        static Result duplicate() {
            return new Result(false, null, null, false, false);
        }
    }

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
        // rejected by the unique constraint before any counter is touched; doing the
        // group or rollup upsert first would inflate both every time a client retried a
        // delivery it had already made.
        boolean stored = eventStore.insert(eventId, request, fingerprint.platform().wireName());
        if (!stored) {
            return Result.duplicate();
        }

        GroupStore.Filed filed =
                groupStore.upsert(
                        fingerprint,
                        request.service(),
                        request.level(),
                        request.resolvedExceptionType(),
                        request.resolvedRelease());

        int countThisHour = rollupStore.record(filed.groupId());
        boolean withinCap = countThisHour <= hourlyCapPerGroup;

        eventStore.attach(eventId, filed.groupId(), fingerprint, request, withinCap);
        if (!withinCap) {
            groupStore.markSampled(filed.groupId());
        }

        // Retention runs after the event is safely committed, never as part of it: a
        // sweep that fails, or one that is slow, must not be able to reject an event.
        afterCommit(retentionService::onEventStored);

        return new Result(true, fingerprint, filed.groupId(), !withinCap, filed.broughtItBack());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }
}
