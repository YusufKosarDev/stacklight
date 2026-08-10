-- A fourth kind of alert: a group that was reporting and stopped.
--
-- Every other kind is raised by something arriving. This one is raised by
-- nothing arriving, which is why it needs a trigger from outside the process --
-- and why the check constraint has to learn a new value before it can exist.
alter table alerts drop constraint alerts_kind_check;

alter table alerts
    add constraint alerts_kind_check
        check (kind in ('spike', 'new_group', 'regression', 'silence'));

-- The sweep asks "which groups were busy and have gone quiet", which reads the
-- rollups by group over a recent window. The existing index is on bucket_start
-- alone, so that question means a scan per sweep.
create index if not exists event_rollups_group_bucket_idx
    on event_rollups (group_id, bucket_start desc);
