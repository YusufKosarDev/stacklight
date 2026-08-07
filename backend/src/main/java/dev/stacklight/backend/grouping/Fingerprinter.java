package dev.stacklight.backend.grouping;

/**
 * One version of the grouping algorithm.
 *
 * <p>Versions are additive and never edited once events have been grouped by them. A
 * group is keyed by {@code (fingerprint, fingerprint_version)}, so changing how an
 * existing version hashes would silently re-point old groups at events they never
 * contained. To change the algorithm, add a new implementation with a new version number.
 */
public interface Fingerprinter {

    /** Version number written alongside every fingerprint this instance produces. */
    int version();

    /** Computes the fingerprint. Never returns null; degrades instead, see {@link Fingerprint}. */
    Fingerprint compute(GroupingInput input);
}
