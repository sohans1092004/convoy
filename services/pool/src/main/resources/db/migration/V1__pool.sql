-- pool schema (services.md §pool). Counts are updated in the same transaction as the membership row.
create table pools (
    id                      uuid primary key,
    model_id                uuid not null,
    model_slug              text not null,
    brand                   text not null,
    city                    text not null,
    state_code              text not null,
    accepted_variant_ids    uuid[] not null,
    variants                jsonb not null,             -- variant facts from catalog at creation (sent to dealers with the round)
    min_members             int not null check (min_members >= 1),
    state                   text not null,
    end_reason              text,
    round_set               int not null default 1,
    purchase_month          text not null,            -- YYYY-MM (IST)
    forming_deadline        timestamptz not null,
    dealer_wait_deadline    timestamptz not null,
    decision_deadline       timestamptz,
    confirmation_deadline   timestamptz,
    purchase_window_end     timestamptz not null,
    interested_count        int not null default 0 check (interested_count >= 0),
    committed_count         int not null default 0 check (committed_count >= 0),
    last_order_version_seen bigint not null default 0,
    version                 bigint not null default 1,
    created_by              uuid not null,
    created_at              timestamptz not null default clock_timestamp()
);
create index pools_open on pools (model_slug, city, purchase_month) where state in ('FORMING', 'READY_FOR_QUOTES', 'QUOTE_OPEN');

create table memberships (
    id                  uuid primary key,
    pool_id             uuid not null references pools(id),
    buyer_id            uuid not null,
    model_id            uuid not null,
    state               text not null,
    current_payment_id  uuid,
    terms_version       text,
    accepted_quote_id   uuid,
    accepted_variant_id uuid,
    release_reason      text,
    choice_deadline     timestamptz,
    version             bigint not null default 1,
    created_at          timestamptz not null default clock_timestamp(),
    unique (pool_id, buyer_id)
);
-- One active commitment per buyer per model (membership.yaml notes).
create unique index one_active_commitment on memberships (buyer_id, model_id) where state in ('COMMIT_PENDING', 'COMMITTED', 'ACCEPTED');

create table rounds (
    id        uuid primary key,
    pool_id   uuid not null references pools(id),
    round_set int not null,
    round_no  int not null,
    kind      text not null check (kind in ('SEALED', 'FINAL')),
    state     text not null,
    deadline  timestamptz not null,
    closed_at timestamptz,
    version   bigint not null default 1,
    unique (pool_id, round_set, round_no)
);

create table round_invitations (
    round_id      uuid not null references rounds(id),
    dealership_id uuid not null,
    primary key (round_id, dealership_id)
);

-- Replica of FINAL quotes (from the final-round summary / QuoteFinalised) for acceptance checks.
create table quote_replica (
    quote_id      uuid primary key,
    pool_id       uuid not null references pools(id),
    dealership_id uuid not null,
    state         text not null,
    variant_ids   uuid[] not null
);

create table quote_acceptance_counts (
    pool_id  uuid not null references pools(id),
    quote_id uuid not null,
    count    int not null default 0 check (count >= 0),
    primary key (pool_id, quote_id)
);
