package dev.stacklight.backend.ingest;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes raw events. No fingerprinting or grouping at this step. */
@Repository
public class EventStore {

    private static final String INSERT =
            """
            insert into events (event_id, service, level, message, payload)
            values (:eventId, :service, :level, :message, cast(:payload as jsonb))
            on conflict (event_id) do nothing
            """;

    private final JdbcClient jdbc;

    EventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one event. A repeated {@code eventId} is discarded by the unique
     * constraint rather than surfacing as a server error.
     *
     * @return true when a row was written, false when the id was already present
     */
    public boolean insert(UUID eventId, IngestRequest request) {
        int rows =
                jdbc.sql(INSERT)
                        .param("eventId", eventId)
                        .param("service", request.service())
                        .param("level", request.level())
                        .param("message", request.message())
                        .param("payload", request.payload() == null ? null : request.payload().toString())
                        .update();
        return rows > 0;
    }
}
