package dev.stacklight.backend.detection;

import java.util.List;

/**
 * What a detector is allowed to see: the counts before this hour, and the count in it.
 *
 * <p>Strictly causal. Nothing after {@code observed} is available, which is what keeps a
 * detector's record comparable with the hindsight the scorer uses later.
 *
 * @param history hourly counts before the current bucket, oldest first, zeros included
 * @param observed count in the bucket under evaluation
 */
public record Series(List<Integer> history, int observed) {

    public int historyLength() {
        return history.size();
    }

    public double mean() {
        if (history.isEmpty()) {
            return 0;
        }
        return history.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    public double standardDeviation() {
        if (history.size() < 2) {
            return 0;
        }
        double mean = mean();
        double sumSquares =
                history.stream().mapToDouble(count -> (count - mean) * (count - mean)).sum();
        return Math.sqrt(sumSquares / (history.size() - 1));
    }

    /** Share of history buckets that are zero. The number that shapes every threshold here. */
    public double zeroShare() {
        if (history.isEmpty()) {
            return 1;
        }
        long zeros = history.stream().filter(count -> count == 0).count();
        return (double) zeros / history.size();
    }
}
