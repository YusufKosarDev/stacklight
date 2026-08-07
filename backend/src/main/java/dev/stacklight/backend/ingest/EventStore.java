package dev.stacklight.backend.ingest;

import dev.stacklight.backend.grouping.Fingerprint;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists raw events and links them to their group. */
@Repository
public class EventStore {

    private static final String INSERT =
            """
            insert into events (event_id, service, level, message, payload,
                                platform, exception_type, stacktrace)
            values (:eventId, :service, :level, :message, cast(:payload as jsonb),
                    :platform, :exceptionType, :stacktrace)
            on conflict (event_id) do nothing
            """;

    private static final String LINK_GROUP =
            """
            update events
               set group_id = :groupId,
                   fingerprint = :fingerprint,
                   fingerprint_version = :fingerprintVersion
             where event_id = :eventId
            """;

    private final JdbcClient jdbc;

    EventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one event, ignoring a repeat of an {@code event_id} already stored.
     *
     * @return true when a row was written, false when the id was already present
     */
    public boolean insert(UUID eventId, IngestRequest request, String platform) {
        int rows =
                jdbc.sql(INSERT)
                        .param("eventId", eventId)
                        .param("service", request.service())
                        .param("level", request.level())
                        .param("message", request.message())
                        .param(
                                "payload",
                                request.payload() == null ? null : request.payload().toString())
                        .param("platform", platform)
                        .param("exceptionType", request.resolvedExceptionType())
                        .param("stacktrace", request.resolvedStacktrace())
                        .update();
        return rows > 0;
    }

    /** Attaches a stored event to its group. */
    public void linkToGroup(UUID eventId, long groupId, Fingerprint fingerprint) {
        jdbc.sql(LINK_GROUP)
                .param("groupId", groupId)
                .param("fingerprint", fingerprint.hash())
                .param("fingerprintVersion", fingerprint.version())
                .param("eventId", eventId)
                .update();
    }
}
