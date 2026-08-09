package dev.stacklight.backend.detection;

import dev.stacklight.backend.alerting.AlertService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs every detector on every bucket it is asked about, and lets exactly one of them
 * speak.
 *
 * <h2>Why more than one detector exists</h2>
 *
 * Picking a detector by reading about it is guessing. All of them run on the same input at
 * the same moment and every verdict is recorded, including the ones that decline to fire.
 * Once the scorer has caught up, the choice becomes a table of numbers per detector rather
 * than a preference, and changing the active one is a configuration change whose effect
 * was already measured before it was made.
 *
 * <h2>What this data does to the textbook detectors</h2>
 *
 * On this deployment 97% of group-hour buckets are empty. That single number is what
 * makes an error stream different from the metric series EWMA and z-score were built for:
 * with a baseline near zero almost everywhere, "several times the usual" and "many sigmas
 * above the mean" are both satisfied by the number two, and a detector saying so on every
 * first error is not detecting anything. All three detectors therefore sit behind an
 * absolute floor on the count, which is less a tuning parameter than an admission that
 * ratios carry little information down here.
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    private final List<AnomalyDetector> detectors;
    private final DetectionStore detectionStore;
    private final AlertService alertService;
    private final DetectorProperties properties;

    DetectionService(
            List<AnomalyDetector> detectors,
            DetectionStore detectionStore,
            AlertService alertService,
            DetectorProperties properties) {
        this.detectors = detectors;
        this.detectionStore = detectionStore;
        this.alertService = alertService;
        this.properties = properties;

        if (detectors.stream().noneMatch(d -> d.name().equals(properties.active()))) {
            throw new IllegalStateException(
                    "configured active detector '"
                            + properties.active()
                            + "' does not exist; available: "
                            + detectors.stream().map(AnomalyDetector::name).toList());
        }
    }

    public List<AnomalyDetector> detectors() {
        return detectors;
    }

    /**
     * Evaluates the group's current hour.
     *
     * <p>Called from the ingest path once the hour's count is high enough that a detector
     * could fire at all. Below the floor there is nothing to decide, so the query and the
     * three evaluations are skipped entirely — the same reasoning that makes the floor
     * necessary also makes it a cheap short circuit.
     */
    public void evaluate(long groupId, int countThisHour) {
        if (countThisHour < properties.minObserved()) {
            return;
        }

        List<Integer> history = detectionStore.history(groupId, properties.historyHours());
        Series series = new Series(history, countThisHour);

        Detection active = null;
        for (AnomalyDetector detector : detectors) {
            boolean isActive = detector.name().equals(properties.active());
            Detection detection = detector.evaluate(series);
            detectionStore.record(groupId, detection, isActive);
            if (isActive) {
                active = detection;
            }
        }

        if (active != null && active.fired()) {
            alertService.raiseSpike(groupId, active);
            log.info(
                    "spike group={} detector={} observed={} baseline={} score={}",
                    groupId,
                    active.detector(),
                    active.observed(),
                    String.format("%.2f", active.baseline()),
                    String.format("%.2f", active.score()));
        }
    }

    /**
     * A group that appeared in the last few hours and is already producing volume.
     *
     * <p>Kept separate from the statistical detectors on purpose. A new group has no
     * history, so there is no baseline to be unusual against; what makes it worth saying
     * is that it is new, which is a fact rather than an inference.
     */
    public void evaluateNewGroup(long groupId, int ageHours, long eventCount) {
        if (ageHours <= properties.newGroupWindowHours()
                && eventCount >= properties.newGroupThreshold()) {
            alertService.raiseNewGroup(groupId, eventCount);
        }
    }

    public Optional<AnomalyDetector> byName(String name) {
        return detectors.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    public String activeName() {
        return properties.active();
    }
}
