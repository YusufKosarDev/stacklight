package dev.stacklight.backend.ingest;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only read query on this side of the project.
 *
 * <p>Every other read belongs to the dashboard, which talks to Postgres directly and never
 * comes through here. This one exists because the triage console has to run on the service
 * that can write, and a console that cannot list the groups it changes is not one.
 *
 * <p>Deliberately smaller than the dashboard's list: no sparklines, no trigram search, no
 * keyset cursor. Those serve reading, and this serves picking a row to act on.
 */
@Repository
public class GroupListStore {

    private static final String LIST =
            """
            select id, service, title, status, event_count, last_seen
              from event_groups
             where (:status is null or status = :status)
             order by last_seen desc, id desc
             limit :limit
            """;

    /**
     * @param lastSeen an ISO-8601 instant, written as a string here rather than left to
     *     Jackson's date handling. The console parses it and renders in the reader's own
     *     zone, and pinning the wire format means a serialisation setting cannot silently
     *     turn it into an epoch number that {@code new Date(...)} would read as 1970.
     */
    public record Row(
            long id, String service, String title, String status, long eventCount, String lastSeen) {}

    private final JdbcClient jdbc;

    GroupListStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param status null lists every status */
    public List<Row> list(String status, int limit) {
        return jdbc.sql(LIST)
                .param("status", status)
                .param("limit", limit)
                .query(
                        (rs, rowNum) ->
                                new Row(
                                        rs.getLong("id"),
                                        rs.getString("service"),
                                        rs.getString("title"),
                                        rs.getString("status"),
                                        rs.getLong("event_count"),
                                        rs.getTimestamp("last_seen").toInstant().toString()))
                .list();
    }
}
