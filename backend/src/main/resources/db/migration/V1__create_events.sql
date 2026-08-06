-- Step 0 schema: raw event landing table only.
-- No fingerprinting, no grouping, no aggregates -- those arrive in later migrations.

create table events (
    id          bigint      generated always as identity primary key,
    event_id    uuid        not null,
    received_at timestamptz not null default now(),
    service     text        not null,
    level       text        not null,
    message     text        not null,
    payload     jsonb,

    -- Idempotency anchor. The column and constraint are cheap now and require a
    -- backfill later, so they land with the first migration even though nothing
    -- reads them yet.
    constraint events_event_id_key unique (event_id)
);

-- The dashboard reads the newest events first.
create index events_received_at_idx on events (received_at desc);
