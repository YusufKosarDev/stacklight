package dev.stacklight.backend.ingest;

import dev.stacklight.backend.alerting.AlertDispatcher;
import dev.stacklight.backend.detection.SelfScoringService;
import dev.stacklight.backend.detection.SilenceService;
import dev.stacklight.backend.observability.PipelineMetrics;
import dev.stacklight.backend.retention.RetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The work that has to happen when nothing is happening.
 *
 * <h2>Why this is not a scheduler inside the process</h2>
 *
 * Retention says it plainly: a timer firing into a process that is not running does not
 * fail loudly, it simply does not happen. That is true, and it is why retention and
 * scoring were hung off the ingest path instead.
 *
 * <p>What that reasoning misses is that the scheduler does not have to be inside the
 * process. This service starts on an inbound request, so a caller from outside wakes it
 * and *then* the work runs — the process is running by the time there is anything to do.
 * An external trigger is a different animal from an in-process timer, and the README's
 * objection only ever applied to the second.
 *
 * <p>What it buys is the one signal ingest can never produce. Every other alert here is
 * raised by an event arriving; silence is raised by events not arriving, so nothing on
 * that path can see it.
 *
 * <h2>What it costs</h2>
 *
 * Waking is not free. Each call spends instance-hours on Render and CU-hours on Neon, and
 * a service woken often enough stops being a service that sleeps — which weakens the
 * demonstration this project is partly built to make, even though it leaves the
 * architecture untouched.
 *
 * <p>The caller runs every three hours. That is set by Render's quota rather than by what
 * the signal deserves: 750 instance-hours a month are shared across every free service in
 * the workspace, and each wake costs the cold start plus the fifteen minutes Render waits
 * before idling the instance out again. Hourly spent 208 of those hours; three-hourly
 * spends about 69, for a signal whose condition stays true while the group stays quiet and
 * so does not need to be sampled often to be seen.
 *
 * <p>Under {@code /api}, so the shared-secret filter already guards it.
 */
@RestController
@RequestMapping("/api/sweep")
public class SweepController {

    private static final Logger log = LoggerFactory.getLogger(SweepController.class);

    private final SilenceService silenceService;
    private final RetentionService retentionService;
    private final SelfScoringService selfScoringService;
    private final AlertDispatcher alertDispatcher;
    private final PipelineMetrics metrics;

    SweepController(
            SilenceService silenceService,
            RetentionService retentionService,
            SelfScoringService selfScoringService,
            AlertDispatcher alertDispatcher,
            PipelineMetrics metrics) {
        this.silenceService = silenceService;
        this.retentionService = retentionService;
        this.selfScoringService = selfScoringService;
        this.alertDispatcher = alertDispatcher;
        this.metrics = metrics;
    }

    /**
     * @param silenceAlerts groups that had gone quiet and were not already inside a cooldown
     * @param deletedEvents rows retention removed on this pass, bounded to one batch
     * @param scoredObservations detector verdicts that had aged into range
     */
    public record SweepResult(
            int silenceAlerts, long deletedEvents, int scoredObservations) {}

    @PostMapping
    public ResponseEntity<SweepResult> sweep() {
        // Order matters only in that delivery goes last: an alert raised here should
        // leave in the same wake rather than waiting for the next one.
        int silenceAlerts = silenceService.check();
        long deletedEvents = retentionService.sweep("scheduled");
        int scored = selfScoringService.score();
        alertDispatcher.requestDrain();

        SweepResult result = new SweepResult(silenceAlerts, deletedEvents, scored);
        // Counters rather than a gauge: what a sweep found is a total to add to, and the
        // instance holding the number will be gone before the next one runs.
        metrics.swept(silenceAlerts, (int) deletedEvents, scored);
        log.info(
                "sweep silence_alerts={} deleted_events={} scored={}",
                silenceAlerts,
                deletedEvents,
                scored);

        return ResponseEntity.ok(result);
    }
}
