-- order schema (services.md §order), P1 subset. order is the saga orchestrator (ADR-004).
create table quote_replica (
    quote_id          uuid primary key,
    pool_id           uuid not null,
    dealership_id     uuid not null,
    state             text not null,
    final_version_no  int not null,
    min_fulfilment    int not null,
    max_units         int,
    ruleset_version   text not null,
    tiers             jsonb not null,
    variant_inputs    jsonb not null
);

create table quote_capacity (
    quote_id        uuid primary key,
    max_units       int,
    confirmed_count int not null default 0 check (confirmed_count >= 0)
);

create table payment_replica (
    buyer_id    uuid not null,
    pool_id     uuid not null,
    payment_id  uuid not null,
    captured_at timestamptz not null,
    primary key (buyer_id, pool_id, payment_id)
);

create table pool_resolutions (
    pool_id               uuid not null,
    round_set             int not null,
    state                 text not null check (state in ('PENDING', 'DONE')),
    confirmation_deadline timestamptz not null,
    resolved_at           timestamptz,
    bookings_count        int,
    void_quote_count      int,
    version               bigint not null default 1,
    primary key (pool_id, round_set)
);

create table orders (
    id                    uuid primary key,
    pool_id               uuid not null,
    round_set             int not null,
    buyer_id              uuid not null,
    quote_id              uuid not null,
    variant_id            uuid not null,
    state                 text not null,
    confirmation_deadline timestamptz not null,
    confirmed_at          timestamptz,
    cancelled_at          timestamptz,
    decline_reason        text,
    cancel_reason         text,
    version               bigint not null default 1,
    created_at            timestamptz not null default clock_timestamp(),
    unique (pool_id, buyer_id, round_set)
);

create table price_snapshots (
    id              uuid primary key,
    order_id        uuid not null references orders(id),
    kind            text not null check (kind in ('CEILING', 'FINAL')),
    tier_index      int not null,
    inputs          jsonb not null,
    output          jsonb not null,
    ruleset_version text not null,
    sha256          text not null,
    computed_at     timestamptz not null default clock_timestamp(),
    unique (order_id, kind)
);

create table bookings (
    id                      uuid primary key,
    order_id                uuid not null unique references orders(id),
    dealership_id           uuid not null,
    state                   text not null,
    voucher_code_hash       text not null,
    voucher_token           text not null,       -- signed QR token (ES256); shown only to the buyer
    final_snapshot_id       uuid not null references price_snapshots(id),
    promised_delivery_date  date,
    dealer_confirm_deadline timestamptz not null,
    version                 bigint not null default 1,
    created_at              timestamptz not null default clock_timestamp()
);

create table saga_steps (
    order_id        uuid not null references orders(id),
    step            text not null check (step in ('HOLD_DEPOSIT', 'RESERVE_STOCK')),
    status          text not null check (status in ('PENDING', 'DONE', 'FAILED')),
    attempts        int not null default 1,
    next_attempt_at timestamptz not null,
    last_error      text,
    primary key (order_id, step)
);

create table voucher_keys (
    kid         text primary key,
    private_jwk text not null,   -- lite limitation: local key in the service schema (KMS in full)
    created_at  timestamptz not null default clock_timestamp()
);
