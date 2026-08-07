package dev.stacklight.backend.grouping;

/**
 * Everything the grouping algorithm is allowed to look at.
 *
 * @param service owning service; part of the fingerprint so that two services throwing
 *     the same error keep separate groups
 * @param declaredPlatform platform as declared by the caller, may be {@link
 *     Platform#UNKNOWN} to request detection
 * @param exceptionType fully qualified exception or error type, may be null
 * @param message error message, may be null
 * @param stacktrace raw stack trace text, may be null
 */
public record GroupingInput(
        String service,
        Platform declaredPlatform,
        String exceptionType,
        String message,
        String stacktrace) {}
