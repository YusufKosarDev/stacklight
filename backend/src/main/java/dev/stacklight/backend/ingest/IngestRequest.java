package dev.stacklight.backend.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/events}.
 *
 * <p>Only {@code service}, {@code level} and {@code message} are required. Everything
 * that feeds grouping is optional, so a caller that sends nothing but a message still
 * gets a group, just a coarser one.
 *
 * @param eventId caller-supplied identifier; the server generates one when absent
 * @param platform {@code java} or {@code javascript}; detected from the stack trace when
 *     absent
 * @param exceptionType fully qualified exception or error type
 * @param stacktrace raw stack trace text as the runtime printed it
 */
public record IngestRequest(
        UUID eventId,

        @NotBlank @Size(max = 100) String service,

        @NotBlank @Size(max = 20) String level,

        @NotBlank @Size(max = 4000) String message,

        @Size(max = 50) String platform,

        @Size(max = 500) String exceptionType,

        @Size(max = 20000) String stacktrace,

        @Size(max = 100) String release,

        JsonNode payload) {

    /**
     * Stack trace text, accepting the shape step 0 clients used.
     *
     * <p>Before grouping existed, callers put the trace in {@code payload.stacktrace} as
     * an array of lines. Those clients are still in the wild, so that form keeps working;
     * the top-level field wins when both are present.
     */
    public String resolvedStacktrace() {
        if (stacktrace != null && !stacktrace.isBlank()) {
            return stacktrace;
        }
        if (payload == null) {
            return null;
        }

        JsonNode legacy = payload.get("stacktrace");
        if (legacy == null) {
            return null;
        }
        if (legacy.isString()) {
            return legacy.stringValue();
        }
        if (legacy.isArray()) {
            StringBuilder sb = new StringBuilder();
            legacy.forEach(
                    line -> {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(line.isString() ? line.stringValue() : line.toString());
                    });
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    /** Exception type, falling back to the legacy {@code payload.exceptionType} field. */
    public String resolvedExceptionType() {
        return firstNonBlank(exceptionType, "exceptionType");
    }

    /**
     * Release the event came from.
     *
     * <p>Read from {@code payload.release} when not sent at the top level: that is where
     * clients were putting it before regression detection gave it a column of its own.
     */
    public String resolvedRelease() {
        return firstNonBlank(release, "release");
    }

    private String firstNonBlank(String topLevel, String payloadField) {
        if (topLevel != null && !topLevel.isBlank()) {
            return topLevel;
        }
        if (payload == null) {
            return null;
        }
        JsonNode legacy = payload.get(payloadField);
        return legacy != null && legacy.isString() ? legacy.stringValue() : null;
    }
}
