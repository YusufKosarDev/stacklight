package dev.stacklight.backend.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention tuning.
 *
 * @param windowDays how long raw events are kept when storage is comfortable
 * @param warnWindowDays window used once storage passes {@code warnBytes}
 * @param criticalWindowDays window used once storage passes {@code criticalBytes}
 * @param warnBytes first pressure threshold for the events table, indexes included
 * @param criticalBytes second pressure threshold
 * @param batchSize most rows one sweep may delete, so a sweep's cost stays bounded
 * @param sweepEveryEvents events between sweeps
 * @param sweepEveryMinutes minutes after which the next event sweeps regardless of count
 */
@ConfigurationProperties(prefix = "stacklight.retention")
public record RetentionProperties(
        @DefaultValue("14") int windowDays,
        @DefaultValue("7") int warnWindowDays,
        @DefaultValue("3") int criticalWindowDays,
        @DefaultValue("314572800") long warnBytes,
        @DefaultValue("419430400") long criticalBytes,
        @DefaultValue("5000") int batchSize,
        @DefaultValue("200") int sweepEveryEvents,
        @DefaultValue("10") int sweepEveryMinutes) {}
