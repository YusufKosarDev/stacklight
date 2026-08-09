import { sql } from "@/lib/db";

export type GroupStatus = "open" | "resolved" | "ignored" | "regressed";

export type GroupSummary = {
  id: number;
  title: string;
  service: string;
  platform: string;
  level: string;
  status: GroupStatus;
  culprit: string | null;
  degraded_reason: string | null;
  event_count: number;
  first_seen: string;
  last_seen: string;
};

export type StoredFrame = {
  module: string | null;
  declaringClass: string | null;
  function: string | null;
  file: string | null;
  line: number;
  inApp: boolean;
};

export type GroupDetail = GroupSummary & {
  fingerprint: string;
  fingerprint_version: number;
  fingerprint_input: string;
  exception_type: string | null;
  frames: StoredFrame[] | null;
  sampled_count: number;
  release_first: string | null;
  release_last: string | null;
  resolved_at: string | null;
  resolved_in_release: string | null;
  regressed_at: string | null;
  regressed_in_release: string | null;
  sample_message: string | null;
  sample_stacktrace: string | null;
  sample_received_at: string | null;
};

export type SimilarGroup = {
  id: number;
  title: string;
  event_count: number;
  score: number;
};

/** One point of a trend. `label` is already formatted for the axis. */
export type Bucket = { label: string; iso: string; count: number };

export type Range = "24h" | "7d" | "30d";

export type StorageStatus = {
  events_bytes: number;
  rollups_bytes: number;
  total_bytes: number;
  event_rows: number;
  rollup_rows: number;
  oldest_event: string | null;
  last_sweep_at: string | null;
  last_sweep_source: string | null;
  last_sweep_window_days: number | null;
  last_sweep_deleted: number | null;
};

const UTC = "YYYY-MM-DD HH24:MI:SS";

export async function listGroups(): Promise<GroupSummary[]> {
  return (await sql()`
    select id, title, service, platform, level, status, culprit, degraded_reason,
           event_count::int as event_count,
           to_char(first_seen at time zone 'utc', ${UTC}) as first_seen,
           to_char(last_seen  at time zone 'utc', ${UTC}) as last_seen
      from event_groups
     order by last_seen desc
     limit 100
  `) as GroupSummary[];
}

/**
 * Last 24 hourly counts for every group at once.
 *
 * One query for the whole list rather than one per row: the sparklines are a column
 * of the table, not a detail view, and a query per row turns a page render into a
 * hundred round trips.
 */
export async function listSparklines(): Promise<Map<number, number[]>> {
  const rows = (await sql()`
    select r.group_id,
           extract(epoch from (date_trunc('hour', now()) - r.bucket_start)) / 3600 as hours_ago,
           r.event_count::int as event_count
      from event_rollups r
     where r.bucket_start > date_trunc('hour', now()) - interval '24 hours'
  `) as { group_id: number; hours_ago: number; event_count: number }[];

  const byGroup = new Map<number, number[]>();
  for (const row of rows) {
    const series = byGroup.get(row.group_id) ?? new Array(24).fill(0);
    const index = 23 - Math.round(Number(row.hours_ago));
    if (index >= 0 && index < 24) {
      series[index] = row.event_count;
    }
    byGroup.set(row.group_id, series);
  }
  return byGroup;
}

export async function getGroup(id: number): Promise<GroupDetail | null> {
  const rows = (await sql()`
    select g.id, g.title, g.service, g.platform, g.level, g.status, g.culprit,
           g.degraded_reason, g.fingerprint, g.fingerprint_version, g.fingerprint_input,
           g.exception_type, g.frames,
           g.event_count::int as event_count,
           g.sampled_count::int as sampled_count,
           g.release_first, g.release_last, g.resolved_in_release, g.regressed_in_release,
           to_char(g.first_seen at time zone 'utc', ${UTC}) as first_seen,
           to_char(g.last_seen  at time zone 'utc', ${UTC}) as last_seen,
           to_char(g.resolved_at  at time zone 'utc', ${UTC}) as resolved_at,
           to_char(g.regressed_at at time zone 'utc', ${UTC}) as regressed_at,
           sample.message    as sample_message,
           sample.stacktrace as sample_stacktrace,
           to_char(sample.received_at at time zone 'utc', ${UTC}) as sample_received_at
      from event_groups g
      left join lateral (
             select message, stacktrace, received_at
               from events
              where group_id = g.id and stacktrace is not null
              order by received_at desc
              limit 1
           ) sample on true
     where g.id = ${id}
  `) as GroupDetail[];

  return rows[0] ?? null;
}

/**
 * The trend for one group, read from the rollups and never from `events`.
 *
 * This is the reason the rollup table exists. Raw events are deleted after two weeks,
 * so a chart built on them would go flat exactly where the history starts being worth
 * something. Empty buckets are produced by the database rather than inferred here, so
 * a quiet hour is drawn as a zero instead of disappearing and squeezing the axis.
 */
export async function getGroupSeries(
  id: number,
  range: Range,
): Promise<Bucket[]> {
  if (range === "24h") {
    return (await sql()`
      select to_char(bucket at time zone 'utc', 'HH24:MI')          as label,
             to_char(bucket at time zone 'utc', ${UTC})             as iso,
             coalesce(r.event_count, 0)::int                        as count
        from generate_series(date_trunc('hour', now()) - interval '23 hours',
                             date_trunc('hour', now()),
                             interval '1 hour') as bucket
        left join event_rollups r on r.group_id = ${id} and r.bucket_start = bucket
       order by bucket
    `) as Bucket[];
  }

  const days = range === "7d" ? 6 : 29;
  return (await sql()`
    select to_char(bucket at time zone 'utc', 'DD Mon')   as label,
           to_char(bucket at time zone 'utc', ${UTC})     as iso,
           coalesce(sum(r.event_count), 0)::int           as count
      from generate_series(date_trunc('day', now()) - make_interval(days => ${days}),
                           date_trunc('day', now()),
                           interval '1 day') as bucket
      left join event_rollups r
             on r.group_id = ${id}
            and date_trunc('day', r.bucket_start) = bucket
     group by bucket
     order by bucket
  `) as Bucket[];
}

export async function findSimilarGroups(
  id: number,
  title: string,
): Promise<SimilarGroup[]> {
  return (await sql()`
    select id, title, event_count::int as event_count,
           round(similarity(title, ${title})::numeric, 2)::float8 as score
      from event_groups
     where id <> ${id}
       and similarity(title, ${title}) > 0.3
     order by similarity(title, ${title}) desc
     limit 5
  `) as SimilarGroup[];
}

export type Alert = {
  id: number;
  group_id: number;
  kind: "spike" | "new_group" | "regression";
  detector: string | null;
  observed: number | null;
  baseline: number | null;
  score: number | null;
  title: string;
  service: string;
  created_at: string;
  delivery_state: "pending" | "sent" | "failed" | "disabled";
  delivery_attempts: number;
  last_error: string | null;
};

export type DetectorRow = {
  detector: string;
  is_active: boolean;
  fired: number;
  judged: number;
  pending: number;
  tp: number;
  fp: number;
  fn: number;
  tn: number;
};

export async function listAlerts(limit = 50): Promise<Alert[]> {
  return (await sql()`
    select a.id, a.group_id, a.kind, a.detector, a.observed, a.baseline, a.score,
           a.title, a.delivery_state, a.delivery_attempts, a.last_error,
           g.service,
           to_char(a.created_at at time zone 'utc', ${UTC}) as created_at
      from alerts a join event_groups g on g.id = a.group_id
     order by a.created_at desc
     limit ${limit}
  `) as Alert[];
}

export async function listAlertsForGroup(groupId: number): Promise<Alert[]> {
  return (await sql()`
    select a.id, a.group_id, a.kind, a.detector, a.observed, a.baseline, a.score,
           a.title, a.delivery_state, a.delivery_attempts, a.last_error,
           g.service,
           to_char(a.created_at at time zone 'utc', ${UTC}) as created_at
      from alerts a join event_groups g on g.id = a.group_id
     where a.group_id = ${groupId}
     order by a.created_at desc
     limit 10
  `) as Alert[];
}

/**
 * How each detector has actually done, on identical data.
 *
 * Every detector judged every bucket; only one was allowed to act. Because the
 * verdicts of the others were recorded anyway, the comparison is a query rather than
 * an argument. Nothing here is filtered, including the runs where a shadow beats the
 * detector currently in charge.
 */
export async function getDetectorScorecard(): Promise<DetectorRow[]> {
  return (await sql()`
    select detector,
           (array_agg(is_active order by created_at desc))[1]        as is_active,
           count(*) filter (where fired)::int                        as fired,
           count(*) filter (where outcome is not null)::int          as judged,
           count(*) filter (where outcome is null)::int              as pending,
           count(*) filter (where outcome = 'true_positive')::int    as tp,
           count(*) filter (where outcome = 'false_positive')::int   as fp,
           count(*) filter (where outcome = 'false_negative')::int   as fn,
           count(*) filter (where outcome = 'true_negative')::int    as tn
      from detector_observations
     group by detector
     order by detector
  `) as DetectorRow[];
}

/**
 * What retention has been doing.
 *
 * Retention succeeds by leaving nothing behind, which makes it exactly the kind of
 * thing that can quietly stop working. Surfacing storage use next to the last sweep
 * turns "it should be running" into something anyone can check.
 */
export async function getStorageStatus(): Promise<StorageStatus> {
  // Scalar subqueries throughout: each yields null when there is nothing to report,
  // so an empty retention_runs table produces a row of nulls rather than no row.
  const rows = (await sql()`
    select pg_total_relation_size('events')        as events_bytes,
           pg_total_relation_size('event_rollups') as rollups_bytes,
           pg_database_size(current_database())    as total_bytes,
           (select count(*)::int from events)        as event_rows,
           (select count(*)::int from event_rollups) as rollup_rows,
           (select to_char(min(received_at) at time zone 'utc', ${UTC}) from events)
             as oldest_event,
           (select to_char(ran_at at time zone 'utc', ${UTC})
              from retention_runs order by ran_at desc limit 1) as last_sweep_at,
           (select source
              from retention_runs order by ran_at desc limit 1) as last_sweep_source,
           (select window_days
              from retention_runs order by ran_at desc limit 1) as last_sweep_window_days,
           (select deleted_events::int
              from retention_runs order by ran_at desc limit 1) as last_sweep_deleted
  `) as StorageStatus[];

  return rows[0];
}
