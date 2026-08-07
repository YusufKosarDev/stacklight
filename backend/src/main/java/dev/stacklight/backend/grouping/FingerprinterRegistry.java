package dev.stacklight.backend.grouping;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds every grouping algorithm version and decides which one new events use.
 *
 * <h2>Why old groups are frozen rather than recomputed</h2>
 *
 * When the algorithm changes, the obvious move is to re-fingerprint history so that
 * everything lives under the new rules. This project deliberately does not, for three
 * reasons.
 *
 * <ol>
 *   <li><b>Recomputing rewrites the record.</b> A group carries observed facts: when it
 *       was first seen, how many times it happened, and later whether someone resolved
 *       it. If a new version merges two old groups, there is no correct answer for which
 *       first-seen survives or what happens to the one already marked resolved.
 *   <li><b>A version change re-partitions, it does not relabel.</b> Groups can merge and
 *       split at the same time, so there is no one-to-one mapping to apply as an update.
 *   <li><b>A frozen group stays truthful.</b> It means exactly "these events, grouped by
 *       the rule that was in force when they arrived", which remains a statement worth
 *       reading a year later.
 * </ol>
 *
 * <p>The cost is real and worth stating plainly: after a version bump an error that is
 * still happening opens a new group while the old one stops growing. That is visible
 * noise, which is why the active version changes rarely and on purpose.
 *
 * <p>What makes the change safe to plan is that every group stores the exact text that
 * was hashed. A future version can be run over those stored inputs offline to produce a
 * merge-and-split report before it is ever made active.
 */
@Component
public class FingerprinterRegistry {

    private final Map<Integer, Fingerprinter> byVersion;
    private final int activeVersion;

    FingerprinterRegistry(
            List<Fingerprinter> fingerprinters,
            @Value("${stacklight.grouping.active-version:1}") int activeVersion) {

        this.byVersion =
                fingerprinters.stream()
                        .collect(Collectors.toMap(Fingerprinter::version, Function.identity()));

        if (!byVersion.containsKey(activeVersion)) {
            throw new IllegalStateException(
                    "configured grouping version "
                            + activeVersion
                            + " has no implementation; available: "
                            + byVersion.keySet());
        }
        this.activeVersion = activeVersion;
    }

    /** Algorithm applied to newly ingested events. */
    public Fingerprinter active() {
        return byVersion.get(activeVersion);
    }

    public int activeVersion() {
        return activeVersion;
    }

    /** Looks up a specific version, for replaying or comparing stored fingerprints. */
    public Fingerprinter forVersion(int version) {
        Fingerprinter fingerprinter = byVersion.get(version);
        if (fingerprinter == null) {
            throw new IllegalArgumentException("no fingerprinter for version " + version);
        }
        return fingerprinter;
    }

    public List<Integer> availableVersions() {
        return byVersion.keySet().stream().sorted().toList();
    }
}
