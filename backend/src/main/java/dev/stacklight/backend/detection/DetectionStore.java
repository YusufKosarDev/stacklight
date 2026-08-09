package dev.stacklight.backend.detection;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DetectionStore {

    /**
     * The hours before the current one, zeros included.
     *
     * <p>The window starts at the group's first sighting, never earlier. Counting hours
     * from before a group existed as "zero" would drag every baseline toward nothing and
     * make a group's ordinary rate look like a spike for its first day.
     */
    private static final String HISTORY =
            """
            select coalesce(r.event_count, 0)::int as count
              from event_groups g
             cross join lateral generate_series(
                     greatest(date_trunc('hour', g.first_seen),
                              date_trunc('hour', now()) - make_interval(hours => :hours)),
                     date_trunc('hour', now()) - interval '1 hour',
                     interval '1 hour') as bucket
              left join event_rollups r
                     on r.group_id = g.id and r.bucket_start = bucket
             where g.id = :groupId
             order by bucket
            """;

    private static final String RECORD =
            """
            insert into detector_observations (group_id, bucket_start, detector, is_active,
                                               fired, observed, baseline, score, threshold)
            values (:groupId, date_trunc('hour', now()), :detector, :isActive,
                    :fired, :observed, :baseline, :score, :threshold)
            on conflict (group_id, bucket_start, detector) do update
               set fired = excluded.fired,
                   observed = excluded.observed,
                   baseline = excluded.baseline,
                   score = excluded.score,
                   threshold = excluded.threshold,
                   is_active = excluded.is_active
            """;

    private final JdbcClient jdbc;

    DetectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Integer> history(long groupId, int hours) {
        return jdbc.sql(HISTORY)
                .param("groupId", groupId)
                .param("hours", hours)
                .query(Integer.class)
                .list();
    }

    /**
     * Records a verdict for the current bucket.
     *
     * <p>Upserted rather than appended: a bucket is evaluated repeatedly as events keep
     * arriving in it, and what the record should hold is the detector's opinion of the
     * hour as a whole, not one row per event.
     */
    public void record(long groupId, Detection detection, boolean isActive) {
        jdbc.sql(RECORD)
                .param("groupId", groupId)
                .param("detector", detection.detector())
                .param("isActive", isActive)
                .param("fired", detection.fired())
                .param("observed", detection.observed())
                .param("baseline", detection.baseline())
                .param("score", detection.score())
                .param("threshold", detection.threshold())
                .update();
    }
}
