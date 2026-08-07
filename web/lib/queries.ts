import { sql } from "@/lib/db";

export type GroupSummary = {
  id: number;
  title: string;
  service: string;
  platform: string;
  level: string;
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

const UTC = "YYYY-MM-DD HH24:MI:SS";

export async function listGroups(): Promise<GroupSummary[]> {
  return (await sql()`
    select id,
           title,
           service,
           platform,
           level,
           culprit,
           degraded_reason,
           event_count::int as event_count,
           to_char(first_seen at time zone 'utc', ${UTC}) as first_seen,
           to_char(last_seen  at time zone 'utc', ${UTC}) as last_seen
      from event_groups
     order by last_seen desc
     limit 100
  `) as GroupSummary[];
}

export async function getGroup(id: number): Promise<GroupDetail | null> {
  const rows = (await sql()`
    select g.id,
           g.title,
           g.service,
           g.platform,
           g.level,
           g.culprit,
           g.degraded_reason,
           g.fingerprint,
           g.fingerprint_version,
           g.fingerprint_input,
           g.exception_type,
           g.frames,
           g.event_count::int as event_count,
           to_char(g.first_seen at time zone 'utc', ${UTC}) as first_seen,
           to_char(g.last_seen  at time zone 'utc', ${UTC}) as last_seen,
           sample.message      as sample_message,
           sample.stacktrace   as sample_stacktrace,
           to_char(sample.received_at at time zone 'utc', ${UTC}) as sample_received_at
      from event_groups g
      left join lateral (
             select message, stacktrace, received_at
               from events
              where group_id = g.id
              order by received_at desc
              limit 1
           ) sample on true
     where g.id = ${id}
  `) as GroupDetail[];

  return rows[0] ?? null;
}

/**
 * Groups whose titles read like this one's.
 *
 * Trigram similarity, computed by Postgres against a GIN index. It catches what a
 * fingerprint cannot: the same fault reached through a renamed method, or one refactor
 * away from an existing group. No model is involved, and the result is reproducible.
 */
export async function findSimilarGroups(
  id: number,
  title: string,
): Promise<SimilarGroup[]> {
  return (await sql()`
    select id,
           title,
           event_count::int as event_count,
           round(similarity(title, ${title})::numeric, 2)::float8 as score
      from event_groups
     where id <> ${id}
       and similarity(title, ${title}) > 0.3
     order by similarity(title, ${title}) desc
     limit 5
  `) as SimilarGroup[];
}
