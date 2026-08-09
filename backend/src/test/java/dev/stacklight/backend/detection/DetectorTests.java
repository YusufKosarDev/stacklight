package dev.stacklight.backend.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetectorTests {

    private static DetectorProperties props() {
        return new DetectorProperties(
                "poisson", 24, 6, 5, 0.3, 3.0, 3.0, 1.0, 0.001, 0.1, 2, 10, 60, 3, 4.0);
    }

    /** Same properties but with the count floor lifted, to expose what it is hiding. */
    private static DetectorProperties withoutFloor() {
        return new DetectorProperties(
                "poisson", 24, 6, 1, 0.3, 3.0, 3.0, 1.0, 0.001, 0.1, 2, 10, 60, 3, 4.0);
    }

    private static List<Integer> zeros(int n) {
        return Collections.nCopies(n, 0);
    }

    private static List<Integer> repeat(int value, int n) {
        return Collections.nCopies(n, value);
    }

    // --- the shape of the data ------------------------------------------------

    @Test
    void aMostlyEmptySeriesMakesEveryDetectorCallASingleErrorASpike() {
        // The measured reality on this deployment: 97% of group-hour buckets are empty.
        // With the count floor lifted, all three detectors call four errors in an hour a
        // significant deviation, because against a baseline of nothing it formally is
        // one. This is the failure the floor exists to prevent, and it is not specific
        // to one detector -- it is what the domain does to the whole family.
        Series quiet = new Series(zeros(24), 4);
        var unfloored = withoutFloor();

        assertThat(new EwmaDetector(unfloored).evaluate(quiet).fired()).isTrue();
        assertThat(new RollingZScoreDetector(unfloored).evaluate(quiet).fired()).isTrue();
        assertThat(new PoissonRateDetector(unfloored).evaluate(quiet).fired()).isTrue();
    }

    @Test
    void theCountFloorStopsAllOfThem() {
        Series quiet = new Series(zeros(24), 4);
        var floored = props();

        assertThat(new EwmaDetector(floored).evaluate(quiet).fired()).isFalse();
        assertThat(new RollingZScoreDetector(floored).evaluate(quiet).fired()).isFalse();
        assertThat(new PoissonRateDetector(floored).evaluate(quiet).fired()).isFalse();
    }

    @Test
    void noneOfThemJudgeAGroupWithAlmostNoHistory() {
        Series young = new Series(zeros(3), 50);

        assertThat(new EwmaDetector(props()).evaluate(young).fired()).isFalse();
        assertThat(new RollingZScoreDetector(props()).evaluate(young).fired()).isFalse();
        assertThat(new PoissonRateDetector(props()).evaluate(young).fired()).isFalse();
    }

    // --- where the detectors actually differ ----------------------------------

    @Test
    void poissonCatchesASurgeThatBurstinessHidesFromTheZScore() {
        // A group whose normal behaviour is uneven: some quiet hours, some busy ones,
        // averaging four. The unevenness inflates the standard deviation to about seven,
        // and since the z-score divides by it, the very burstiness that makes a group
        // worth watching is what desensitises the detector watching it: twenty errors in
        // an hour lands at 2.4 sigma and passes unremarked. Poisson takes its spread
        // from the rate instead, and twenty against a rate of four is not a close call.
        List<Integer> bursty = new ArrayList<>(List.of(0, 0, 18, 0, 1, 12, 0, 0, 15, 2, 0, 0));
        Series series = new Series(bursty, 20);

        Detection z = new RollingZScoreDetector(props()).evaluate(series);
        Detection poisson = new PoissonRateDetector(props()).evaluate(series);

        assertThat(z.fired()).isFalse();
        assertThat(z.score()).isLessThan(3.0);
        assertThat(poisson.fired()).isTrue();
    }

    @Test
    void poissonOverFiresWhenAGroupIsGenuinelyErratic() {
        // The other side of the same assumption, stated rather than hidden. Poisson ties
        // the spread to the rate, so a group whose real behaviour is bimodal -- quiet or
        // busy, rarely in between -- looks surprising to it far more often than it
        // should. Which of the two failure modes costs more is not decidable from first
        // principles, which is the argument for the scorecard.
        List<Integer> bimodal =
                new ArrayList<>(List.of(0, 0, 0, 30, 0, 0, 28, 0, 0, 0, 31, 0));
        Series ordinaryForThisGroup = new Series(bimodal, 29);

        assertThat(new PoissonRateDetector(props()).evaluate(ordinaryForThisGroup).fired()).isTrue();
        assertThat(new RollingZScoreDetector(props()).evaluate(ordinaryForThisGroup).fired())
                .isFalse();
    }

    @Test
    void allThreeAgreeOnAnObviousSpikeOverASteadyBaseline() {
        Series series = new Series(repeat(5, 24), 40);

        assertThat(new EwmaDetector(props()).evaluate(series).fired()).isTrue();
        assertThat(new RollingZScoreDetector(props()).evaluate(series).fired()).isTrue();
        assertThat(new PoissonRateDetector(props()).evaluate(series).fired()).isTrue();
    }

    @Test
    void allThreeStaySilentOnAnOrdinaryHour() {
        Series series = new Series(repeat(20, 24), 22);

        assertThat(new EwmaDetector(props()).evaluate(series).fired()).isFalse();
        assertThat(new RollingZScoreDetector(props()).evaluate(series).fired()).isFalse();
        assertThat(new PoissonRateDetector(props()).evaluate(series).fired()).isFalse();
    }

    @Test
    void poissonHoldsQuietAndBusyGroupsToProportionateStandards() {
        // Six errors is remarkable for a group that normally sees one and unremarkable
        // for a group that normally sees fifty. Under Poisson the spread comes from the
        // rate itself, so this needs no per-group tuning.
        var poisson = new PoissonRateDetector(props());

        assertThat(poisson.evaluate(new Series(repeat(1, 24), 12)).fired()).isTrue();
        assertThat(poisson.evaluate(new Series(repeat(50, 24), 60)).fired()).isFalse();
    }

    @Test
    void everyDetectorReportsAVerdictEvenWhenItDeclinesToFire() {
        // A detector that only reported firings could never be measured for what it
        // missed, and the scorecard would flatter whichever one fired least.
        Series quiet = new Series(repeat(3, 24), 3);

        for (AnomalyDetector detector :
                List.of(
                        new EwmaDetector(props()),
                        new RollingZScoreDetector(props()),
                        new PoissonRateDetector(props()))) {
            Detection detection = detector.evaluate(quiet);
            assertThat(detection.fired()).isFalse();
            assertThat(detection.detector()).isEqualTo(detector.name());
            assertThat(detection.observed()).isEqualTo(3);
        }
    }

    @Test
    void scoresRunInTheSameDirectionForEveryDetector() {
        // The scorecard puts them in one column, so "bigger is more surprising" has to
        // hold across all three or the table lies.
        Series ordinary = new Series(repeat(10, 24), 11);
        Series extreme = new Series(repeat(10, 24), 60);

        for (AnomalyDetector detector :
                List.of(
                        new EwmaDetector(props()),
                        new RollingZScoreDetector(props()),
                        new PoissonRateDetector(props()))) {
            assertThat(detector.evaluate(extreme).score())
                    .as(detector.name())
                    .isGreaterThan(detector.evaluate(ordinary).score());
        }
    }

    // --- the arithmetic -------------------------------------------------------

    @Test
    void poissonUpperTailMatchesKnownValues() {
        // P(X >= 1 | 1) = 1 - e^-1
        assertThat(PoissonRateDetector.upperTail(1, 1.0)).isCloseTo(0.6321, within(1e-4));
        // P(X >= 3 | 1) = 1 - e^-1(1 + 1 + 0.5)
        assertThat(PoissonRateDetector.upperTail(3, 1.0)).isCloseTo(0.0803, within(1e-4));
        assertThat(PoissonRateDetector.upperTail(0, 5.0)).isEqualTo(1.0);
    }

    @Test
    void poissonUpperTailStaysInRangeForLargeCounts() {
        double tail = PoissonRateDetector.upperTail(500, 0.5);

        assertThat(tail).isBetween(0.0, 1.0);
    }

    @Test
    void seriesReportsTheZeroShareThatShapesTheThresholds() {
        Series series = new Series(List.of(0, 0, 0, 0, 1), 3);

        assertThat(series.zeroShare()).isCloseTo(0.8, within(1e-9));
        assertThat(series.mean()).isCloseTo(0.2, within(1e-9));
    }
}
