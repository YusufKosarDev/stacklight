package dev.stacklight.backend.ingest;

import dev.stacklight.backend.alerting.AlertDispatcher;
import dev.stacklight.backend.alerting.AlertService;
import dev.stacklight.backend.detection.DetectionService;
import dev.stacklight.backend.detection.SelfScoringService;
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
    private final DetectionService detectionService;
    private final SelfScoringService selfScoringService;
    private final AlertService alertService;
    private final AlertDispatcher alertDispatcher;
    private final int hourlyCapPerGroup;

    IngestService(
            EventStore eventStore,
            GroupStore groupStore,
            RollupStore rollupStore,
            FingerprinterRegistry fingerprinters,
            RetentionService retentionService,
            DetectionService detectionService,
            SelfScoringService selfScoringService,
            AlertService alertService,
            AlertDispatcher alertDispatcher,
            @Value("${stacklight.ingest.hourly-cap-per-group:200}") int hourlyCapPerGroup) {
        this.eventStore = eventStore;
        this.groupStore = groupStore;
        this.rollupStore = rollupStore;
        this.fingerprinters = fingerprinters;
        this.retentionService = retentionService;
        this.detectionService = detectionService;
        this.selfScoringService = selfScoringService;
        this.alertService = alertService;
        this.alertDispatcher = alertDispatcher;
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

        // Detection is inside the transaction so an alert cannot outlive a rolled-back
        // event, and so the row is durable before anyone tries to email it.
        detectionService.evaluate(filed.groupId(), countThisHour);
        if (filed.broughtItBack()) {
            alertService.raiseRegression(filed.groupId());
        }

        // A group too young for the statistical detectors to judge is covered by the one
        // rule that says something true about it: it is new. The two cannot both fire --
        // a detector needs min-history-hours of history and a group inside the new-group
        // window cannot have that much -- so this runs last and the cooldown keeps it
        // from repeating once per event through the burst that triggered it.
        detectionService.evaluateNewGroup(filed.groupId(), filed.ageHours(), filed.eventCount());

        // Everything below runs after the event is safely committed, never as part of
        // it: a slow sweep, a scoring pass or an unreachable mail server must not be
        // able to reject an event.
        afterCommit(
                () -> {
                    retentionService.onEventStored();
                    selfScoringService.scoreIfDue();
                    alertDispatcher.requestDrain();
                });

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
