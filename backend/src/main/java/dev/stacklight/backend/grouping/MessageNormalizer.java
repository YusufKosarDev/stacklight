package dev.stacklight.backend.grouping;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Replaces the parts of an error message that change between occurrences of the same
 * fault: identifiers, addresses, paths, timestamps and plain numbers.
 *
 * <p>Order matters and runs from most specific to least. A UUID contains digits, so
 * replacing digits first would shred it into something no longer recognisable as an
 * identifier, and two messages that differ only by a UUID would stop matching.
 */
@Component
public class MessageNormalizer {

    private record Rule(Pattern pattern, String replacement) {}

    private static final java.util.List<Rule> RULES =
            java.util.List.of(
                    new Rule(
                            Pattern.compile(
                                    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                                            + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
                            "<uuid>"),
                    new Rule(
                            Pattern.compile(
                                    "\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}"
                                            + "(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\b"),
                            "<timestamp>"),
                    new Rule(
                            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b"), "<ip>"),
                    new Rule(
                            Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b"), "<email>"),
                    new Rule(Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://\\S+"), "<url>"),
                    // Absolute paths, POSIX and Windows.
                    new Rule(Pattern.compile("(?:[A-Za-z]:)?[\\\\/](?:[\\w.-]+[\\\\/]){2,}[\\w.-]*"), "<path>"),
                    new Rule(Pattern.compile("\\b0x[0-9a-fA-F]+\\b"), "<hex>"),
                    new Rule(Pattern.compile("\\b[0-9a-fA-F]{8,}\\b"), "<hex>"),
                    // Plain numbers last: every rule above may legitimately contain digits.
                    new Rule(Pattern.compile("\\b\\d[\\d_,]*(?:\\.\\d+)?\\b"), "<num>"));

    /** Applies every rule in order. Returns an empty string for null input. */
    public String normalize(String message) {
        if (message == null) {
            return "";
        }

        String result = message.strip();
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }

        // Collapse runs of whitespace so that wrapping differences do not matter.
        return result.replaceAll("\\s+", " ").strip();
    }
}
