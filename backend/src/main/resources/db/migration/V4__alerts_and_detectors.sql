-- Anomaly detection, its scorecard, and the alert outbox.

-- Every detector's verdict on every bucket it was asked about, whether or not it
-- fired and whether or not it was the one in charge. This is what makes choosing a
-- detector a measurement instead of an opinion: the alternatives are run on the same
-- data at the same time, and their records are directly comparable afterwards.
create table detector_observations (
    id           bigint      generated always as identity primary key,
    group_id     bigint      not null references event_groups (id) on delete cascade,
    bucket_start timestamptz not null,
    detector     text        not null,

    -- Whether this detector was the configured one when the observation was made.
    -- The others are shadows: they see the same input and their verdict is recorded,
    -- but only the active one is allowed to raise an alert.
    is_active    boolean     not null,

    fired        boolean     not null,
    observed     integer     not null,
    baseline     double precision,
    score        double precision,
    threshold    double precision,
    created_at   timestamptz not null default now(),

    -- Filled in later, once enough time has passed to judge with hindsight the
    -- detector did not have. Null means not yet old enough to score.
    outcome         text,
    scored_at       timestamptz,
    oracle_baseline double precision,

    constraint detector_observations_unique unique (group_id, bucket_start, detector),
    constraint detector_observations_outcome_check
        check (outcome is null or outcome in
               ('true_positive', 'false_positive', 'true_negative', 'false_negative'))
);

create index detector_observations_pending_idx
    on detector_observations (bucket_start) where outcome is null;
create index detector_observations_scorecard_idx on detector_observations (detector, outcome);

-- Alerts are written in the same transaction as the event that caused them, and
-- delivered afterwards on a best-effort basis. The row is the record; the email is a
-- copy of it. If delivery never succeeds the alert is still here, which is the
-- property an instance that sleeps mid-send needs.
create table alerts (
    id           bigint      generated always as identity primary key,
    group_id     bigint      not null references event_groups (id) on delete cascade,
    kind         text        not null,
    detector     text,
    bucket_start timestamptz,
    observed     integer,
    baseline     double precision,
    score        double precision,
    threshold    double precision,
    title        text        not null,
    created_at   timestamptz not null default now(),

    delivery_state    text        not null default 'pending',
    delivery_attempts integer     not null default 0,
    delivered_at      timestamptz,
    last_error        text,

    constraint alerts_kind_check check (kind in ('spike', 'new_group', 'regression')),
    constraint alerts_delivery_check
        check (delivery_state in ('pending', 'sent', 'failed', 'disabled'))
);

create index alerts_created_at_idx on alerts (created_at desc);
create index alerts_group_idx on alerts (group_id, created_at desc);
-- Partial index: the drain only ever looks for pending rows, and there are normally
-- none, so the index stays close to empty.
create index alerts_pending_idx on alerts (created_at) where delivery_state = 'pending';
