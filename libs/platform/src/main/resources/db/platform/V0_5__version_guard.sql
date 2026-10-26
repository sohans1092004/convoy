-- P5.0 version guard. Producer side: one event per aggregate version, so a reused version fails at write time (both P4 bugs
-- were events dropped downstream as "stale" because a producer reused a version). Rows written before this migration may share
-- a version; the index covers rows after them (the history stays as delivered).
do $$
declare boundary bigint := coalesce((select max(id) from outbox), 0);
begin
    execute format('create unique index outbox_one_event_per_version on outbox (aggregate_id, aggregate_version) where id > %s', boundary);
end $$;

-- Consumer side: which event was applied at last_version, so a different event at the same version is seen (stale_distinct).
alter table aggregate_versions add column last_event_id uuid;
