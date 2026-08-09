package dev.stacklight.backend.ingest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GroupStatusStore {

    /**
     * Moving to {@code resolved} stamps when and in which build. Moving away clears the
     * regression marks: a group that is open again has no unresolved contradiction left
     * to explain, and leaving the old stamps in place would keep a stale "came back in
     * 1.4.0" on a group that has since been reopened by hand.
     */
    private static final String UPDATE =
            """
            update event_groups
               set status = :status,
                   resolved_at = case when :status = 'resolved' then now() else null end,
                   resolved_in_release = case when :status = 'resolved'
                                              then coalesce(:release, release_last)
                                              else null end,
                   regressed_at = null,
                   regressed_in_release = null
             where id = :id
            """;

    private final JdbcClient jdbc;

    GroupStatusStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when no group has that id */
    public boolean updateStatus(long groupId, String status, String release) {
        return jdbc.sql(UPDATE)
                        .param("status", status)
                        .param("release", release)
                        .param("id", groupId)
                        .update()
                > 0;
    }
}
