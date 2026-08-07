package dev.stacklight.backend.ingest;

import dev.stacklight.backend.grouping.Fingerprint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Creates and updates the group an event belongs to. */
@Repository
public class GroupStore {

    /**
     * Creates the group on first sight, otherwise bumps its counter and recency.
     *
     * <p>{@code first_seen} is never touched after creation, and {@code last_seen} uses
     * {@code greatest} so that an event arriving late, after a network retry, cannot pull
     * the group's recency backwards.
     */
    private static final String UPSERT =
            """
            insert into event_groups (fingerprint, fingerprint_version, service, platform,
                                      level, exception_type, title, culprit,
                                      fingerprint_input, frames, degraded_reason,
                                      first_seen, last_seen, event_count)
            values (:fingerprint, :fingerprintVersion, :service, :platform,
                    :level, :exceptionType, :title, :culprit,
                    :fingerprintInput, cast(:frames as jsonb), :degradedReason,
                    now(), now(), 1)
            on conflict (fingerprint, fingerprint_version) do update
               set last_seen   = greatest(event_groups.last_seen, excluded.last_seen),
                   event_count = event_groups.event_count + 1,
                   level       = excluded.level
            returning id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    GroupStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Returns the id of the group this fingerprint belongs to, creating it if needed. */
    public long upsert(Fingerprint fingerprint, String service, String level, String exceptionType) {
        return jdbc.sql(UPSERT)
                .param("fingerprint", fingerprint.hash())
                .param("fingerprintVersion", fingerprint.version())
                .param("service", service)
                .param("platform", fingerprint.platform().wireName())
                .param("level", level)
                .param("exceptionType", exceptionType)
                .param("title", fingerprint.title())
                .param("culprit", fingerprint.culprit())
                .param("fingerprintInput", fingerprint.input())
                .param("frames", writeFrames(fingerprint))
                .param("degradedReason", fingerprint.degradedReason())
                .query(Long.class)
                .single();
    }

    private String writeFrames(Fingerprint fingerprint) {
        if (fingerprint.frames() == null || fingerprint.frames().isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(fingerprint.frames());
    }
}
