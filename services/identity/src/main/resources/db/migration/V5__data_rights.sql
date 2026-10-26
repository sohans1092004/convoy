-- P5.D (DPDP): data export and erasure, coordinated here; every other service contributes or confirms its part.
create table data_exports (
    id           uuid primary key,
    user_id      uuid not null references users(id),
    state        text not null default 'PENDING' check (state in ('PENDING', 'READY', 'EXPIRED')),
    requested_at timestamptz not null default clock_timestamp(),
    ready_at     timestamptz,
    object_key   text
);
create index data_exports_user on data_exports(user_id, requested_at desc);

-- One row per contributing service; deleted once the single file is assembled (the file itself lives 24 h in S3).
create table data_export_parts (
    export_id   uuid not null references data_exports(id),
    service     text not null,
    data        jsonb not null,
    received_at timestamptz not null default clock_timestamp(),
    primary key (export_id, service)
);

create table erasures (
    id           uuid primary key,
    user_id      uuid not null references users(id),
    state        text not null default 'PENDING' check (state in ('PENDING', 'COMPLETED')),
    requested_at timestamptz not null default clock_timestamp(),
    completed_at timestamptz
);
create unique index one_open_erasure on erasures(user_id) where state = 'PENDING';

create table erasure_confirmations (
    erasure_id   uuid not null references erasures(id),
    service      text not null,
    confirmed_at timestamptz not null default clock_timestamp(),
    primary key (erasure_id, service)
);
