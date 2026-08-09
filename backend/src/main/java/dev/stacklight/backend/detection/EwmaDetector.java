package dev.stacklight.backend.detection;

import org.springframework.stereotype.Component;

/**
 * Exponentially weighted moving average of the hourly count.
 *
 * <p>The conventional choice for a metric time series: recent hours weigh more than old
 * ones, so the baseline follows a shifting normal level without needing a window edge.
 *
 * <p>Its weakness on this data is structural rather than a matter of tuning. Error counts
 * per group are mostly zero, so the smoothed baseline sits near zero almost all the time,
 * and "several times the baseline" is satisfied by the number two. What stops it firing on
 * every first error is not the multiplier but the absolute floor underneath it, which is
 * the detector admitting that the ratio alone means very little here.
 */
@Component
public class EwmaDetector implements AnomalyDetector {

    private final DetectorProperties properties;

    EwmaDetector(DetectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "ewma";
    }

    @Override
    public String describe() {
        return "Exponentially weighted baseline; fires when the hour exceeds it by a multiple.";
    }

    @Override
    public Detection evaluate(Series series) {
        double baseline = ewma(series);
        double denominator = Math.max(baseline, 0.5);
        double score = series.observed() / denominator;
        double threshold = properties.ewmaMultiplier();

        boolean fired =
                series.historyLength() >= properties.minHistoryHours()
                        && series.observed() >= properties.minObserved()
                        && score > threshold;

        return new Detection(name(), fired, series.observed(), baseline, score, threshold);
    }

    private double ewma(Series series) {
        if (series.history().isEmpty()) {
            return 0;
        }
        double alpha = properties.ewmaAlpha();
        double value = series.history().get(0);
        for (int i = 1; i < series.history().size(); i++) {
            value = alpha * series.history().get(i) + (1 - alpha) * value;
        }
        return value;
    }
}
