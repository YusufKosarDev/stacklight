-- Grouping layer over the raw events landed by V1.

-- Trigram matching powers the "similar groups" lookup on a group page. It finds the
-- near misses a fingerprint cannot: the same fault reached through a renamed method, or
-- one refactor away from an existing group.
create extension if not exists pg_trgm;

create table event_groups (
    id                  bigint      generated always as identity primary key,

    -- A group is identified by the hash AND the algorithm version that produced it.
    -- Versions are frozen once in use: a new version opens new groups rather than
    -- rewriting old ones, because a version change re-partitions events instead of
    -- relabelling them. See FingerprinterRegistry for the full reasoning.
    fingerprint         text        not null,
    fingerprint_version integer     not null,

    service             text        not null,
    platform            text        not null,
    level               text        not null,
    exception_type      text,
    title               text        not null,
    culprit             text,

    -- Exact text that was hashed. Stored so a future algorithm can be compared against
    -- this one offline, without replaying the original events.
    fingerprint_input   text        not null,

    -- Frames of the event that opened the group, with the in-app decision that was made
    -- for each. The dashboard reads the database directly and cannot call the ingestion
    -- service, so the parse result is recorded here rather than recomputed in the UI --
    -- which would mean a second implementation of the rules, free to disagree with the
    -- one that actually did the grouping.
    frames              jsonb,

    -- Set when grouping had to fall back to weaker signal than in-app frames.
    degraded_reason     text,

    first_seen          timestamptz not null default now(),
    last_seen           timestamptz not null default now(),
    event_count         bigint      not null default 0,

    constraint event_groups_fingerprint_key unique (fingerprint, fingerprint_version),
    constraint event_groups_count_non_negative check (event_count >= 0)
);

-- The group list is ordered by recency.
create index event_groups_last_seen_idx on event_groups (last_seen desc);

-- Similar-group lookup.
create index event_groups_title_trgm_idx on event_groups using gin (title gin_trgm_ops);

alter table events
    add column group_id            bigint references event_groups (id),
    add column fingerprint         text,
    add column fingerprint_version integer,
    add column platform            text,
    add column exception_type      text,
    add column stacktrace          text;

-- Group detail pages read the newest events of one group.
create index events_group_id_idx on events (group_id, received_at desc);
