package dev.stacklight.backend.grouping;

import java.util.List;

/**
 * Turns raw stack trace text into frames.
 *
 * <p>One implementation per platform: the formats have nothing in common beyond the
 * leading {@code at}, and the rule for deciding whether a frame belongs to the
 * application is different for each.
 */
public interface StackTraceParser {

    /** Platform this parser handles. */
    Platform platform();

    /**
     * Confidence that the given text is this parser's format, from 0 to 100.
     *
     * <p>Used only when the caller did not declare a platform.
     */
    int confidence(String rawTrace);

    /** Parses frames in the order they appear, outermost throw site first. */
    List<Frame> parse(String rawTrace);
}
