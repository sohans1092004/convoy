-- Audit (wave 3, P5.C): every event of every service, append-only and hash-chained.
-- The application role (${app_role}) may only INSERT and SELECT audit_log; a trigger rejects UPDATE, DELETE and TRUNCATE
-- for everyone, the owner included. A superuser can still disable the trigger: verify-chain is what detects that.

-- Local and test databases: the role is created by deploy/local/postgres/02-audit.sh; a superuser migration (tests) creates it here.
do $$ begin
    if not exists (select 1 from pg_roles where rolname = '${app_role}') and (select rolsuper from pg_roles where rolname = current_user) then
        create role "${app_role}" nologin;
    end if;
end $$;

create table audit_log (
    seq bigint generated always as identity primary key,
    event_id uuid unique,               -- null for entries audit writes itself (viewer reads, export downloads)
    event_type text not null,
    producer text not null,
    aggregate_type text,
    aggregate_id uuid,
    aggregate_version bigint,
    actor text not null,                -- user:<id> from the payload's *_by field, else service:<producer>
    correlation_id uuid,
    sensitive boolean not null,         -- access to or about personal data
    occurred_at timestamptz not null,
    recorded_at timestamptz not null default clock_timestamp(),
    payload jsonb not null,
    prev_hash text not null,
    hash text not null unique
);
create index audit_log_actor on audit_log(actor, seq);
create index audit_log_aggregate on audit_log(aggregate_id, seq);
create index audit_log_type on audit_log(event_type, seq);
create index audit_log_occurred on audit_log(occurred_at);

-- One definition of a row's hash, used when appending and when verifying. Timestamps as epoch text (no session time zone).
create function row_hash(prev text, event_id uuid, event_type text, producer text, aggregate_type text, aggregate_id uuid, aggregate_version bigint,
                         actor text, correlation_id uuid, sensitive boolean, occurred_at timestamptz, payload jsonb) returns text
language sql immutable as $$
    select encode(sha256(convert_to(concat_ws('|', prev, event_id, event_type, producer, aggregate_type, aggregate_id, aggregate_version,
                                              actor, correlation_id, sensitive, extract(epoch from occurred_at), payload::text), 'UTF8')), 'hex')
$$;

create function reject_change() returns trigger language plpgsql as $$
begin
    raise exception 'audit_log is append-only (% refused)', tg_op using errcode = 'insufficient_privilege';
end $$;
create trigger audit_log_append_only before update or delete on audit_log for each row execute function reject_change();
create trigger audit_log_no_truncate before truncate on audit_log for each statement execute function reject_change();

grant usage on schema ${flyway:defaultSchema} to "${app_role}";
grant select, insert on audit_log to "${app_role}";
grant execute on function row_hash to "${app_role}";
-- the platform tables (inbox, outbox, ...) are ordinary working state
grant select, insert, update, delete on outbox, inbox, idempotency_keys, aggregate_versions, parked_events to "${app_role}";
grant usage on all sequences in schema ${flyway:defaultSchema} to "${app_role}";
