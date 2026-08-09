package dev.stacklight.backend.detection;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Detection tuning.
 *
 * @param active name of the detector allowed to raise alerts; the rest run in shadow
 * @param historyHours how many hours of history a detector may look at
 * @param minHistoryHours below this much history, statistical detectors decline to judge
 *     and the new-group rule covers the group instead
 * @param minObserved floor on the count itself, applied by every detector. The single
 *     most important number here: without it, a series that is mostly zeros makes any
 *     non-zero hour look infinitely unusual, and every first error becomes an alert
 * @param ewmaAlpha smoothing factor for the EWMA detector
 * @param ewmaMultiplier how many times the smoothed baseline counts as a spike
 * @param zThreshold z above which the rolling z-score detector fires
 * @param zSigmaFloor floor on the standard deviation, so a flat history cannot divide by
 *     nearly zero
 * @param poissonProbability upper-tail probability below which the Poisson detector fires
 * @param poissonLambdaFloor floor on the Poisson rate, for the same reason as the sigma
 *     floor
 * @param newGroupWindowHours a group younger than this is judged by the new-group rule
 * @param newGroupThreshold events within that window that make a new group worth saying
 * @param cooldownMinutes minimum gap between two alerts for the same group
 * @param scoreAfterHours how much hindsight the scorer waits for before judging a bucket
 * @param oracleMultiplier how far above the two-sided baseline a bucket must sit for the
 *     scorer to call it a genuine surge
 */
@ConfigurationProperties(prefix = "stacklight.detection")
public record DetectorProperties(
        @DefaultValue("poisson") String active,
        @DefaultValue("24") int historyHours,
        @DefaultValue("6") int minHistoryHours,
        @DefaultValue("5") int minObserved,
        @DefaultValue("0.3") double ewmaAlpha,
        @DefaultValue("3.0") double ewmaMultiplier,
        @DefaultValue("3.0") double zThreshold,
        @DefaultValue("1.0") double zSigmaFloor,
        @DefaultValue("0.001") double poissonProbability,
        @DefaultValue("0.1") double poissonLambdaFloor,
        @DefaultValue("2") int newGroupWindowHours,
        @DefaultValue("10") int newGroupThreshold,
        @DefaultValue("60") int cooldownMinutes,
        @DefaultValue("3") int scoreAfterHours,
        @DefaultValue("4.0") double oracleMultiplier) {}
