package dev.stacklight.backend.ingest;

import dev.stacklight.backend.grouping.Fingerprint;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists raw events and links them to their group. */
@Repository
public class EventStore {

    /**
     * Claims the event id.
     *
     * <p>The stack trace and payload are deliberately left out of this statement. They
     * are the bulk of a row, and whether they are worth keeping depends on the group's
     * current rate, which is not known until the event has been filed. Claiming the id
     * first is what keeps a retried delivery from being counted twice; the detail follows
     * in {@link #attach}.
     */
    private static final String INSERT =
            """
            insert into events (event_id, service, level, message,
                                platform, exception_type, release)
            values (:eventId, :service, :level, :message,
                    :platform, :exceptionType, :release)
            on conflict (event_id) do nothing
            """;

    private static final String ATTACH =
            """
            update events
               set group_id = :groupId,
                   fingerprint = :fingerprint,
                   fingerprint_version = :fingerprintVersion,
                   stacktrace = :stacktrace,
                   payload = cast(:payload as jsonb)
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
                        .param("platform", platform)
                        .param("exceptionType", request.resolvedExceptionType())
                        .param("release", request.resolvedRelease())
                        .update();
        return rows > 0;
    }

    /**
     * Attaches a stored event to its group.
     *
     * @param keepDetail when false the stack trace and payload are left null: the group
     *     is over its hourly cap, the event is still counted in the rollup, and the trend
     *     stays honest while storage stops growing
     */
    public void attach(
            UUID eventId,
            long groupId,
            Fingerprint fingerprint,
            IngestRequest request,
            boolean keepDetail) {

        jdbc.sql(ATTACH)
                .param("groupId", groupId)
                .param("fingerprint", fingerprint.hash())
                .param("fingerprintVersion", fingerprint.version())
                .param("stacktrace", keepDetail ? request.resolvedStacktrace() : null)
                .param(
                        "payload",
                        keepDetail && request.payload() != null
                                ? request.payload().toString()
                                : null)
                .param("eventId", eventId)
                .update();
    }
}
