-- P4.C: manual refunds need a second, different admin (AMENDMENT-001, REF-T06); reconciliation runs (deposit-contract.md §4);
-- every admin money action is audited.
alter table refunds add column manual       boolean not null default false;
alter table refunds add column requested_by uuid;
alter table refunds add column requested_at timestamptz;
alter table refunds add column manual_note  text;
alter table refunds add column evidence_ref text;
alter table refunds add column approved_by  uuid;
alter table refunds add constraint manual_needs_a_second_admin check (approved_by is null or (requested_by is not null and approved_by <> requested_by));

create table reconciliation_runs (
    id             uuid primary key,
    started_at     timestamptz not null default clock_timestamp(),
    finished_at    timestamptz,
    checked        int not null default 0,
    mismatch_count int not null default 0
);

create table reconciliation_mismatches (
    id             uuid primary key,
    run_id         uuid not null references reconciliation_runs(id),
    mismatch_class text not null,
    payment_id     uuid references payments(id),
    detail         text not null,
    created_at     timestamptz not null default clock_timestamp()
);

create table admin_audit (
    id     bigserial primary key,
    actor  uuid not null,
    action text not null,
    target uuid not null,
    detail text,
    at     timestamptz not null default clock_timestamp()
);
