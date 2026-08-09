package dev.stacklight.backend.detection;

import org.springframework.stereotype.Component;

/**
 * How improbable this hour's count is under the rate the recent hours imply.
 *
 * <p>Written for the shape this data actually has. Hourly error counts are non-negative
 * integers, usually small, usually zero, and arriving in bursts — a counting process, not
 * a noisy continuous signal. A Poisson model says so directly: the baseline is a rate, and
 * the question asked of an hour is "how often would a rate like that produce a count at
 * least this large".
 *
 * <p>The practical difference from a z-score is that the spread is not a free parameter.
 * Under Poisson the variance is the rate, so a quiet group and a busy one are held to
 * proportionate standards without either being hand-tuned: three errors in an hour is
 * remarkable for a group that normally sees none and unremarkable for one that normally
 * sees fifty. A z-score has to be told that; this does not.
 */
@Component
public class PoissonRateDetector implements AnomalyDetector {

    /** Beyond this the tail probability is already far past any sane threshold. */
    private static final int MAX_TERMS = 200;

    private final DetectorProperties properties;

    PoissonRateDetector(DetectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "poisson";
    }

    @Override
    public String describe() {
        return "Upper-tail probability of this count under the rate recent hours imply.";
    }

    @Override
    public Detection evaluate(Series series) {
        double lambda = Math.max(series.mean(), properties.poissonLambdaFloor());
        double tail = upperTail(series.observed(), lambda);

        // Reported as -log10(p) so that bigger is more surprising, which keeps the score
        // column reading the same direction for every detector on the scorecard.
        double score = -Math.log10(Math.max(tail, 1e-300));
        double threshold = -Math.log10(properties.poissonProbability());

        boolean fired =
                series.historyLength() >= properties.minHistoryHours()
                        && series.observed() >= properties.minObserved()
                        && tail < properties.poissonProbability();

        return new Detection(name(), fired, series.observed(), lambda, score, threshold);
    }

    /** P(X >= observed) for X ~ Poisson(lambda), summed term by term to stay stable. */
    static double upperTail(int observed, double lambda) {
        if (observed <= 0) {
            return 1;
        }

        // P(X <= observed - 1), accumulated from the k = 0 term to avoid computing
        // factorials or exponentials of large numbers.
        double term = Math.exp(-lambda);
        double cumulative = term;
        int limit = Math.min(observed - 1, MAX_TERMS);

        for (int k = 1; k <= limit; k++) {
            term *= lambda / k;
            cumulative += term;
        }

        return Math.max(0, 1 - cumulative);
    }
}
