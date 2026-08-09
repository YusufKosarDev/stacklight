package dev.stacklight.backend.detection;

/**
 * One way of deciding whether an hour's error count is unusual.
 *
 * <p>Several exist at once on purpose. Exactly one is configured as active and allowed to
 * raise alerts; the rest run on the same input and have their verdicts recorded anyway,
 * so that "which detector" is settled by comparing their records rather than by argument.
 */
public interface AnomalyDetector {

    /** Stable name; it is the key the scorecard groups by, so it does not change. */
    String name();

    /** One-line description of the rule, shown next to its numbers on the scorecard. */
    String describe();

    /**
     * Judges the current bucket.
     *
     * <p>Always returns a verdict, including when it decides nothing is wrong: a detector
     * that only reported firings could never be measured for what it missed.
     */
    Detection evaluate(Series series);
}
