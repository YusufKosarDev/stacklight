package dev.stacklight.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

/**
 * One error, in the shape the collector accepts.
 *
 * @param eventId generated here rather than server-side, so a delivery that is retried
 *     after an ambiguous failure is recognised as the same event instead of counted twice
 */
public record StacklightEvent(
        String eventId,
        String service,
        String level,
        String message,
        String platform,
        String exceptionType,
        String stacktrace,
        String release) {

    /** Longest message the collector will accept; longer ones are cut rather than rejected. */
    private static final int MAX_MESSAGE = 4000;

    private static final int MAX_STACKTRACE = 20000;

    public static StacklightEvent from(Throwable throwable, String level, StacklightOptions options) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }

        return new StacklightEvent(
                UUID.randomUUID().toString(),
                options.service(),
                level,
                truncate(message, MAX_MESSAGE),
                options.platform(),
                throwable.getClass().getName(),
                truncate(render(throwable), MAX_STACKTRACE),
                options.release());
    }

    public static StacklightEvent message(String message, String level, StacklightOptions options) {
        return new StacklightEvent(
                UUID.randomUUID().toString(),
                options.service(),
                level,
                truncate(message, MAX_MESSAGE),
                options.platform(),
                null,
                null,
                options.release());
    }

    /**
     * The stack trace exactly as the JVM prints it.
     *
     * <p>No reformatting on purpose. The collector's parser was written against
     * {@code printStackTrace} output — {@code at pkg.Class.method(File.java:42)}, the
     * {@code Caused by:} chain, the {@code ... N common frames omitted} markers — so
     * sending anything tidier would mean two formats to keep in step instead of one.
     */
    private static String render(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** Hand-written because this library carries no JSON dependency. */
    String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "eventId", eventId, true);
        field(json, "service", service, false);
        field(json, "level", level, false);
        field(json, "message", message, false);
        field(json, "platform", platform, false);
        field(json, "exceptionType", exceptionType, false);
        field(json, "stacktrace", stacktrace, false);
        field(json, "release", release, false);
        json.append('}');
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first && json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(name).append("\":\"");
        escape(json, value);
        json.append('"');
    }

    static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }
}
