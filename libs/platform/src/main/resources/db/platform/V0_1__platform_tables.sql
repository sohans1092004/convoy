-- Standard tables in every service schema (services.md): outbox, inbox, idempotency keys,
-- aggregate versions and parked events. Created in the service's own schema by its own DB user.
create table outbox (
    id                bigserial primary key,
    event_id          uuid not null unique,
    event_type        text not null,
    aggregate_id      uuid not null,
    aggregate_version bigint not null,
    payload           jsonb not null,
    occurred_at       timestamptz not null,
    correlation_id    uuid not null,
    causation_id      uuid,
    traceparent       text,
    created_at        timestamptz not null default clock_timestamp(),
    delivered_at      timestamptz
);
create index outbox_undelivered on outbox (id) where delivered_at is null;

create table inbox (
    consumer    text not null,
    event_id    uuid not null,
    received_at timestamptz not null default clock_timestamp(),
    primary key (consumer, event_id)
);

create table idempotency_keys (
    key            text not null,
    caller         text not null,
    request_sha256 text not null,
    status         int,
    content_type   text,
    response       bytea,
    created_at     timestamptz not null default clock_timestamp(),
    completed_at   timestamptz,
    primary key (key, caller)
);

create table aggregate_versions (
    producer     text not null,
    aggregate_id uuid not null,
    last_version bigint not null,
    primary key (producer, aggregate_id)
);

create table parked_events (
    consumer          text not null,
    event_id          uuid not null,
    aggregate_id      uuid not null,
    aggregate_version bigint not null,
    expected_version  bigint not null,
    parked_at         timestamptz not null default clock_timestamp(),
    primary key (consumer, event_id)
);
