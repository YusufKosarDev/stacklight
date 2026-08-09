package dev.stacklight.backend.detection;

/**
 * One detector's verdict on one bucket.
 *
 * @param detector name, used as the key in the scorecard
 * @param fired whether this detector would raise an alert
 * @param observed the count it judged
 * @param baseline what it expected
 * @param score how far from expected, in that detector's own units
 * @param threshold the score above which it fires
 */
public record Detection(
        String detector,
        boolean fired,
        int observed,
        double baseline,
        double score,
        double threshold) {

    static Detection quiet(String detector, int observed, double baseline, double score, double threshold) {
        return new Detection(detector, false, observed, baseline, score, threshold);
    }
}
