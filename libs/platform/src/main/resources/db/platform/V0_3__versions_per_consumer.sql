-- P2: last applied version is per consumer, like inbox and parked_events (two consumers can share a schema).
alter table aggregate_versions add column consumer text not null default '';
alter table aggregate_versions drop constraint aggregate_versions_pkey;
alter table aggregate_versions add primary key (consumer, producer, aggregate_id);
alter table aggregate_versions alter column consumer drop default;
