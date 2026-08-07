package dev.stacklight.backend.grouping;

/**
 * Result of grouping one event.
 *
 * @param hash 32 hex characters identifying the group within its version
 * @param version algorithm version that produced the hash; part of the group key, see
 *     {@link FingerprinterRegistry}
 * @param input exact text that was hashed, stored so a future version can be compared
 *     against this one without replaying the original events
 * @param title short human-readable label, built from normalized parts only
 * @param culprit frame the error is attributed to
 * @param degradedReason why grouping fell back to weaker signal, or null when the
 *     fingerprint was built from in-app frames as intended
 * @param platform platform actually used, after detection
 * @param frames every parsed frame, in order, each carrying the in-app decision made for
 *     it; kept so the grouping can be shown rather than described
 */
public record Fingerprint(
        String hash,
        int version,
        String input,
        String title,
        String culprit,
        String degradedReason,
        Platform platform,
        java.util.List<Frame> frames) {

    /** No in-app frames were present, so vendor frames had to be used. */
    public static final String NO_IN_APP_FRAMES = "no_in_app_frames";

    /** No frames at all; only the type and message were available. */
    public static final String NO_FRAMES = "no_frames";

    /** Frames looked minified, which makes their names useless for grouping. */
    public static final String MINIFIED = "minified";
}
