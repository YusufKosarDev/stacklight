package dev.stacklight.backend.detection;

import org.springframework.stereotype.Component;

/**
 * Standard deviations from the trailing mean.
 *
 * <p>The other conventional choice, and the one this data breaks most clearly. A z-score
 * assumes the spread around the mean is a meaningful unit. When most hours are zero the
 * mean is near zero and the deviation is near zero with it, so dividing by it turns any
 * ordinary hour into a large number of sigmas.
 *
 * <p>The sigma floor keeps that arithmetic from exploding, but it does not make the model
 * fit: what the floor really does is convert the detector into "is the count much bigger
 * than usual", measured in units chosen by hand rather than by the data. It is kept
 * because a comparison is only worth anything if the alternatives are implemented
 * properly and given a fair run.
 */
@Component
public class RollingZScoreDetector implements AnomalyDetector {

    private final DetectorProperties properties;

    RollingZScoreDetector(DetectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "zscore";
    }

    @Override
    public String describe() {
        return "Standard deviations above the trailing mean, with a floor under sigma.";
    }

    @Override
    public Detection evaluate(Series series) {
        double mean = series.mean();
        double sigma = Math.max(series.standardDeviation(), properties.zSigmaFloor());
        double score = (series.observed() - mean) / sigma;
        double threshold = properties.zThreshold();

        boolean fired =
                series.historyLength() >= properties.minHistoryHours()
                        && series.observed() >= properties.minObserved()
                        && score > threshold;

        return new Detection(name(), fired, series.observed(), mean, score, threshold);
    }
}
