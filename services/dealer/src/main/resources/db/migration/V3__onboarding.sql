-- P3.A: dealer self-registration and admin verification. PENDING becomes PENDING_VERIFICATION; REJECTED is new.
alter table dealerships drop constraint dealerships_status_check;
update dealerships set status = 'PENDING_VERIFICATION' where status = 'PENDING';
alter table dealerships add constraint dealerships_status_check check (status in ('PENDING_VERIFICATION', 'VERIFIED', 'REJECTED', 'SUSPENDED'));
alter table dealerships add column registered_by uuid, add column verified_by uuid, add column rejection_reason text,
    add column created_at timestamptz not null default clock_timestamp();
create unique index dealerships_gstin on dealerships (gstin) where gstin is not null;

-- Append-only audit of verification decisions (who, what, why, when).
create table dealership_audit (
    id            uuid primary key,
    dealership_id uuid not null references dealerships(id),
    action        text not null check (action in ('REGISTERED', 'APPROVED', 'REJECTED')),
    actor         uuid not null,
    reason        text,
    at            timestamptz not null default clock_timestamp()
);
create index dealership_audit_by_dealership on dealership_audit (dealership_id, at);
