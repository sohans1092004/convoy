-- quote schema (services.md §quote), P1 subset.
create table rounds_replica (
    round_id               uuid primary key,
    pool_id                uuid not null,
    round_set              int not null,
    round_no               int not null,
    kind                   text not null,
    deadline               timestamptz not null,       -- immutable replica, enforced with this DB's clock (Q-006)
    state                  text not null default 'OPEN',
    invited_dealership_ids uuid[] not null,
    state_code             text not null,
    variants               jsonb not null,
    committed_count        int not null
);
create index rounds_by_pool on rounds_replica (pool_id, round_set, round_no);

create table quotes (
    id              uuid primary key,
    pool_id         uuid not null,
    round_set       int not null,
    dealership_id   uuid not null,
    state           text not null,
    draft           jsonb not null default '{}',
    current_version int not null default 0,
    final_version   int,
    expire_reason   text,
    created_by      uuid not null,
    submitted_at    timestamptz,
    version         bigint not null default 1,
    created_at      timestamptz not null default clock_timestamp()
);
create unique index one_live_quote on quotes (pool_id, round_set, dealership_id) where state not in ('EXPIRED', 'WITHDRAWN', 'VOID');

create table quote_versions (
    quote_id        uuid not null references quotes(id),
    version_no      int not null,
    inputs          jsonb not null,
    snapshots       jsonb not null,
    snapshot_sha256 text not null,
    ruleset_version text not null,
    committed_at    timestamptz not null default clock_timestamp(),
    committed_by    uuid not null,
    primary key (quote_id, version_no)
);

create table stock_reservations (
    booking_id uuid primary key,
    quote_id   uuid not null references quotes(id),
    variant_id uuid not null,
    colour     text not null,
    at         timestamptz not null default clock_timestamp()
);

-- Replicas for the buyer comparison: committed members, acceptance counts, confirmations.
create table member_replica (
    pool_id  uuid not null,
    buyer_id uuid not null,
    state    text not null,
    primary key (pool_id, buyer_id)
);
create table count_replica (
    pool_id   uuid not null,
    quote_id  uuid not null,
    accepted  int not null default 0,
    confirmed int not null default 0,
    primary key (pool_id, quote_id)
);

create table audit_trail (
    id       bigserial primary key,
    quote_id uuid not null,
    actor    uuid not null,
    action   text not null,
    at       timestamptz not null default clock_timestamp(),
    detail   jsonb
);
