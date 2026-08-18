package dev.stacklight.backend.scale;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fills a real PostgreSQL with a shape a long-lived deployment would have.
 *
 * <h2>Why the distribution is not uniform</h2>
 *
 * <p>Spreading events evenly across groups would make every index look equally good and
 * hide the behaviour worth finding. Real error streams are the opposite: a handful of
 * faults produce most of the volume and a long tail produces one event each, so the counts
 * here follow a power law. The timestamps are spread too, because a keyset cursor over
 * {@code last_seen} is only exercised when there is a real ordering to walk.
 *
 * <h2>Why rollups get the most rows</h2>
 *
 * <p>Retention deletes events after fourteen days. It never deletes rollups -- that is the
 * whole reason a trend outlives the events behind it -- so {@code event_rollups} is the one
 * table in this schema that grows without a bound, at groups times active hours, for ever.
 * If anything is going to behave differently at scale it is that one, so it is loaded
 * hardest.
 *
 * <p>Seeding is written as {@code generate_series} rather than as ingest calls. The write
 * path is measured separately and for real; getting a million rows in place is setup, and
 * setup that takes an hour is setup nobody runs twice.
 */
final class ScaleSeeder {

    private final JdbcClient jdbc;

    ScaleSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Distinct faults, with a power-law spread of counts and a scatter of statuses. */
    void groups(int from, int to) {
        jdbc.sql(
                        """
                        insert into event_groups (
                            fingerprint, fingerprint_version, service, platform, level,
                            exception_type, title, culprit, fingerprint_input,
                            status, event_count, first_seen, last_seen)
                        select md5('group-' || i),
                               1,
                               'service-' || (i % 40),
                               case when i % 3 = 0 then 'javascript' else 'java' end,
                               case when i % 11 = 0 then 'warn' else 'error' end,
                               'com.example.Fault' || (i % 120) || 'Exception',
                               'Fault' || (i % 120) || 'Exception: '
                                   || (array['could not reserve stock for cart',
                                             'connection pool exhausted acquiring connection',
                                             'Cannot invoke length() because template is null',
                                             'Invalid array length while decoding segment',
                                             'session evicted before write completed',
                                             'could not price the cart',
                                             'timed out waiting for the ledger',
                                             'unexpected end of stream'])[1 + i % 8]
                                   || ' for id <uuid>',
                               'com.example.pkg' || (i % 50) || '.Class' || (i % 200)
                                   || '#method' || (i % 30),
                               'v1' || chr(10) || 'platform=java' || chr(10) || 'service=s' || i,
                               (array['open','open','open','open',
                                      'resolved','ignored','regressed'])[1 + i % 7],
                               -- Power law: the first groups carry most of the volume.
                               greatest(1, (200000 / (i + 3))::int),
                               now() - make_interval(days => (i % 120)),
                               now() - make_interval(mins => (i * 37) % 172800)
                          from generate_series(:from, :to) as i
                        on conflict do nothing
                        """)
                .param("from", from)
                .param("to", to)
                .update();
    }

    /**
     * Hourly counts. Every group gets {@code hours} buckets, which is what makes this the
     * biggest table.
     */
    void rollups(int hours) {
        jdbc.sql(
                        """
                        insert into event_rollups (group_id, bucket_start, event_count)
                        select g.id,
                               date_trunc('hour', now()) - make_interval(hours => h),
                               1 + ((g.id + h) % 40)
                          from event_groups g
                          cross join generate_series(0, :hours - 1) as h
                        on conflict (group_id, bucket_start) do nothing
                        """)
                .param("hours", hours)
                .update();
    }

    /** Raw events inside the retention window, attached to groups. */
    void events(int count) {
        jdbc.sql(
                        """
                        insert into events (
                            event_id, received_at, service, level, message,
                            group_id, fingerprint, fingerprint_version, platform,
                            exception_type, stacktrace, release)
                        select gen_random_uuid(),
                               now() - make_interval(mins => (i * 3) % 20160),
                               g.service, g.level,
                               g.title,
                               g.id, g.fingerprint, 1, g.platform,
                               g.exception_type,
                               'com.example.Fault: boom' || chr(10)
                                   || repeat('\tat com.example.Class.method(Class.java:42)' || chr(10), 12),
                               '1.' || (i % 30) || '.0'
                          from generate_series(1, :count) as i
                          join lateral (
                              select id, service, level, title, fingerprint, platform, exception_type
                                from event_groups
                               -- Weighted at the head, so the busiest groups own most rows.
                               order by id
                               offset (i * i) % greatest(1, (select count(*) from event_groups))
                               limit 1
                          ) g on true
                        """)
                .param("count", count)
                .update();
    }

    /** Detector verdicts, one per judged bucket, including the ones that declined to fire. */
    void observations(int count) {
        jdbc.sql(
                        """
                        insert into detector_observations (
                            group_id, bucket_start, detector, is_active, fired, observed,
                            baseline, score, threshold, outcome, scored_at)
                        select r.group_id, r.bucket_start,
                               (array['ewma','zscore','poisson'])[1 + (r.group_id % 3)],
                               (r.group_id % 3) = 0,
                               r.event_count > 20,
                               r.event_count, 4.0, r.event_count / 4.0, 3.0,
                               (array['true_positive','false_positive',
                                      'true_negative','false_negative'])[1 + (r.group_id % 4)],
                               now()
                          from event_rollups r
                         order by r.group_id, r.bucket_start
                         limit :count
                        on conflict do nothing
                        """)
                .param("count", count)
                .update();
    }

    /** Alerts, so the pages that read them have something to read. */
    void alerts(int count) {
        jdbc.sql(
                        """
                        insert into alerts (
                            group_id, kind, detector, bucket_start, observed, baseline,
                            score, threshold, title, created_at, delivery_state)
                        select g.id,
                               (array['spike','new_group','silence','regression'])[1 + (g.id % 4)],
                               'ewma',
                               date_trunc('hour', now()) - make_interval(hours => (g.id % 500)),
                               42, 4.0, 10.0, 3.0,
                               g.title,
                               now() - make_interval(hours => (g.id % 500)),
                               (array['sent','disabled','pending','failed'])[1 + (g.id % 4)]
                          from event_groups g
                         order by g.id
                         limit :count
                        """)
                .param("count", count)
                .update();
    }

    /** Statistics have to be current or every plan below is a guess about the wrong table. */
    void analyze() {
        jdbc.sql("analyze event_groups, events, event_rollups, detector_observations, alerts")
                .update();
    }

    long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    String totalSize() {
        return jdbc.sql(
                        """
                        select pg_size_pretty(sum(pg_total_relation_size(relid)))
                          from pg_stat_user_tables
                        """)
                .query(String.class)
                .single();
    }
}
