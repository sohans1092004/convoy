-- P4.B saga state, consent and contact release; P4.D booking delivery and cancellation; P4.E disputes.

-- One persisted saga per pool round: FROZEN -> RESOLVED -> DEPOSITS_HELD -> BOOKINGS_ISSUED -> CONTACTS_RELEASED.
create table sagas (
    pool_id    uuid not null,
    round_set  int not null,
    step       text not null check (step in ('FROZEN', 'RESOLVED', 'DEPOSITS_HELD', 'BOOKINGS_ISSUED', 'CONTACTS_RELEASED')),
    updated_at timestamptz not null default clock_timestamp(),
    primary key (pool_id, round_set)
);
insert into sagas(pool_id, round_set, step)
    select pool_id, round_set, case when state = 'DONE' then 'CONTACTS_RELEASED' else 'FROZEN' end from pool_resolutions;

alter table pool_resolutions add column all_closed_at timestamptz;

alter table bookings add column voucher_active   boolean not null default false;
alter table bookings add column scheduled_date   date;
alter table bookings add column delivery_slot    text;
alter table bookings add column reschedule_count int not null default 0;
alter table bookings add column delivered_at     timestamptz;
alter table bookings add column overdue_flag     boolean not null default false;
alter table bookings add column cancel_reason    text;
alter table bookings add column cancelled_at     timestamptz;
alter table bookings add column cancelled_by     text;
update bookings set voucher_active = true;

-- The buyer agrees, when confirming, that their contact details go to this quote's dealer only (if a booking is issued).
create table contact_consents (
    order_id      uuid primary key references orders(id),
    buyer_id      uuid not null,
    dealership_id uuid not null,
    terms_version text not null,
    consented_at  timestamptz not null default clock_timestamp()
);

create table contact_releases (
    booking_id    uuid primary key references bookings(id),
    order_id      uuid not null references contact_consents(order_id),
    buyer_id      uuid not null,
    dealership_id uuid not null,
    released_at   timestamptz not null default clock_timestamp()
);

create table disputes (
    id                uuid primary key,
    order_id          uuid not null references orders(id),
    booking_id        uuid references bookings(id),
    dealership_id     uuid not null,
    buyer_id          uuid not null,
    category          text not null check (category in ('NOT_DELIVERED', 'DELIVERY_OVERDUE', 'PRICE_NOT_HONOURED', 'STOCK_UNAVAILABLE', 'DEALER_CANCELLED',
                                                         'DEALER_NOT_HONOURED', 'WRONG_VARIANT', 'CHARGES_DISPUTE', 'REFUND_NOT_RECEIVED', 'OTHER')),
    description       text not null,
    state             text not null check (state in ('OPEN', 'UNDER_REVIEW', 'AWAITING_PARTY', 'RESOLVED', 'CLOSED', 'WITHDRAWN')),
    opened_by         text not null check (opened_by in ('BUYER', 'SYSTEM')),
    owner_id          uuid,
    awaiting_party    text check (awaiting_party in ('BUYER', 'DEALER')),
    respond_by        timestamptz,
    outcome           text,
    proposed_outcome  text,
    proposed_by       uuid,
    resolved_at       timestamptz,
    appeal_until      timestamptz,
    appeal_count      int not null default 0,
    version           bigint not null default 1,
    created_at        timestamptz not null default clock_timestamp()
);
create unique index one_open_dispute_per_category on disputes (order_id, category) where state in ('OPEN', 'UNDER_REVIEW', 'AWAITING_PARTY');

-- Every dispute transition and message (the resolution history, and the audit trail of admin actions).
create table dispute_history (
    id         bigserial primary key,
    dispute_id uuid not null references disputes(id),
    actor_role text not null,
    actor_id   uuid,
    action     text not null,
    from_state text,
    to_state   text,
    note       text,
    at         timestamptz not null default clock_timestamp()
);

create table dispute_evidence (
    id           uuid primary key,
    dispute_id   uuid not null references disputes(id),
    uploader_role text not null,
    uploader_id  uuid not null,
    object_key   text not null unique,
    file_name    text not null,
    content_type text not null check (content_type in ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes   bigint not null check (size_bytes > 0 and size_bytes <= 10485760),
    created_at   timestamptz not null default clock_timestamp()
);
