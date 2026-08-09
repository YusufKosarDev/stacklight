package dev.stacklight.backend.ingest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Maintains the hourly counts a group's trend is drawn from.
 *
 * <p>Written on the ingest path, in the same transaction as the event, rather than
 * rebuilt by a periodic job. A job would have to run somewhere, and this service sleeps
 * after fifteen idle minutes; a scheduler that fires into a process that is not there
 * silently produces a gap in the history, and the gap is only noticed later, when the
 * history is what someone came to look at. Counting on arrival cannot miss, because the
 * count and the arrival are the same event.
 */
@Repository
public class RollupStore {

    private static final String UPSERT =
            """
            insert into event_rollups (group_id, bucket_start, event_count)
            values (:groupId, date_trunc('hour', now()), 1)
            on conflict (group_id, bucket_start) do update
               set event_count = event_rollups.event_count + 1
            returning event_count
            """;

    private final JdbcClient jdbc;

    RollupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counts one event into the current hour.
     *
     * @return the running count for this group in this hour, used to decide whether the
     *     group is over its cap
     */
    public int record(long groupId) {
        return jdbc.sql(UPSERT).param("groupId", groupId).query(Integer.class).single();
    }
}
