-- Time dimension, volume control, and the state a group can be in.

-- Hourly counts per group. This is the table that has to survive raw events
-- being deleted: a sparkline drawn from `events` would go flat the moment
-- retention ran, which is exactly when the history becomes worth having.
create table event_rollups (
    group_id     bigint      not null references event_groups (id) on delete cascade,
    bucket_start timestamptz not null,
    event_count  integer     not null default 0,

    primary key (group_id, bucket_start),
    constraint event_rollups_count_positive check (event_count > 0)
);

create index event_rollups_bucket_idx on event_rollups (bucket_start desc);

alter table events add column release text;

alter table event_groups
    -- 'regressed' is a distinct state rather than a flag on 'open': a group that
    -- came back after being called fixed is a different situation from one that
    -- was never fixed, and the difference is the whole point of tracking it.
    add column status               text        not null default 'open',
    add column release_first        text,
    add column release_last         text,
    add column resolved_at          timestamptz,
    add column resolved_in_release  text,
    add column regressed_at         timestamptz,
    add column regressed_in_release text,

    -- Events counted in the rollup but not stored as rows, because the group was
    -- over its hourly cap. Recorded so the page can say so instead of quietly
    -- showing a trend built on more events than it can show.
    add column sampled_count        bigint      not null default 0,

    add constraint event_groups_status_check
        check (status in ('open', 'resolved', 'ignored', 'regressed'));

create index event_groups_status_idx on event_groups (status, last_seen desc);

-- Retention is invisible by nature: it succeeds by leaving nothing behind. This
-- table is how it can be shown to have run at all.
create table retention_runs (
    id             bigint      generated always as identity primary key,
    ran_at         timestamptz not null default now(),
    source         text        not null,
    window_days    integer     not null,
    deleted_events bigint      not null,
    events_bytes   bigint
);

create index retention_runs_ran_at_idx on retention_runs (ran_at desc);

-- Events that arrived before grouping existed. They carry no group, so nothing
-- can display them and nothing can count them; retention would remove them in
-- two weeks regardless.
delete from events where group_id is null;

-- Release was already being sent inside the payload before it had a column.
update events
   set release = payload ->> 'release'
 where payload ? 'release';

-- Backfill the rollups from the events that are still here, so the time series
-- starts populated rather than pretending history began at this migration.
insert into event_rollups (group_id, bucket_start, event_count)
select group_id, date_trunc('hour', received_at), count(*)
  from events
 where group_id is not null
 group by group_id, date_trunc('hour', received_at);

update event_groups g
   set release_first = first_release.release,
       release_last  = last_release.release
  from (select distinct on (group_id) group_id, release
          from events where release is not null order by group_id, received_at asc) first_release,
       (select distinct on (group_id) group_id, release
          from events where release is not null order by group_id, received_at desc) last_release
 where g.id = first_release.group_id
   and g.id = last_release.group_id;
