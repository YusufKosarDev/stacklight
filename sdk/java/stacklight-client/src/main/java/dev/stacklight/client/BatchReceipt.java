package dev.stacklight.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the collector's answer to a batch, without a JSON library.
 *
 * <p>This client has no dependencies and that is a property worth keeping: every jar an
 * error reporter drags in is a version it can conflict with, inside an application that did
 * not ask for it. Writing events out is already done by hand; this is the same job in the
 * other direction, over a shape this project controls and tests.
 *
 * <p>It is deliberately narrow. It walks the {@code results} array and reads two fields per
 * entry, and it understands strings, {@code null}, booleans and numbers because that is all
 * the collector sends. It is not a JSON parser and should not be used as one.
 *
 * <p>Anything it cannot make sense of returns an empty list, which the caller reads as "the
 * collector answered 2xx, so assume it has them all". Re-sending events because the receipt
 * was unreadable would be worse than not reading it.
 */
final class BatchReceipt {

    /**
     * @param failed the collector did not take this event
     * @param retryable sending it again could change the outcome
     */
    record Entry(boolean failed, boolean retryable) {}

    private BatchReceipt() {}

    static List<Entry> parse(String json) {
        if (json == null) {
            return List.of();
        }
        int start = indexOfKey(json, "results");
        if (start < 0) {
            return List.of();
        }
        int open = json.indexOf('[', start);
        if (open < 0) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        objectStart = i;
                    }
                    depth++;
                }
                case '}' -> {
                    depth--;
                    if (depth == 0 && objectStart >= 0) {
                        entries.add(read(json.substring(objectStart, i + 1)));
                        objectStart = -1;
                    }
                }
                case ']' -> {
                    if (depth == 0) {
                        return entries;
                    }
                }
                default -> {
                    // Whitespace, commas and the characters of unquoted values.
                }
            }
        }
        return entries;
    }

    private static Entry read(String object) {
        String error = value(object, "error");
        boolean failed = error != null && !error.equals("null");
        boolean retryable = "true".equals(value(object, "retryable"));
        return new Entry(failed, retryable);
    }

    /**
     * @return the raw value after {@code "key":}, quotes stripped, or null when absent
     */
    private static String value(String object, String key) {
        int at = indexOfKey(object, key);
        if (at < 0) {
            return null;
        }
        int colon = object.indexOf(':', at);
        if (colon < 0) {
            return null;
        }

        int i = colon + 1;
        while (i < object.length() && Character.isWhitespace(object.charAt(i))) {
            i++;
        }
        if (i >= object.length()) {
            return null;
        }

        if (object.charAt(i) == '"') {
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int j = i + 1; j < object.length(); j++) {
                char c = object.charAt(j);
                if (escaped) {
                    out.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return out.toString();
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        int end = i;
        while (end < object.length() && ",}] \n\r\t".indexOf(object.charAt(end)) < 0) {
            end++;
        }
        return object.substring(i, end);
    }

    /** Finds {@code "key"} as a key rather than as text inside some value. */
    private static int indexOfKey(String json, String key) {
        String quoted = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int at = json.indexOf(quoted, from);
            if (at < 0) {
                return -1;
            }
            int after = at + quoted.length();
            while (after < json.length() && Character.isWhitespace(json.charAt(after))) {
                after++;
            }
            if (after < json.length() && json.charAt(after) == ':' && !insideString(json, at)) {
                return at;
            }
            from = at + 1;
        }
    }

    /** True when the position sits inside a string literal rather than beside one. */
    private static boolean insideString(String json, int position) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < position; i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            }
        }
        return inString;
    }
}
