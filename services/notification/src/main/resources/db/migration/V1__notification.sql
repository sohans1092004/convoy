-- Notification (wave 2, P5.A). Events arrive unordered (standard queues, AMENDMENT-010), so recipients are a graph that
-- fills in over time and every message event fans out again whenever its part of the graph grows.

-- Recipient graph: key -> target, where a target is another key or 'user:<uuid>'.
-- pool:<id> -> user:<buyer> carries the membership versions: a buyer gets a pool event only if it is newer than their
-- latest commit and they have not left since (from_version > to_version).
create table links (
    key text not null,
    target text not null,
    from_version bigint,
    to_version bigint,
    primary key (key, target)
);
create index links_target on links(target);

-- Every message-worthy event, kept so a late link can still fan it out.
create table events (
    event_id uuid primary key,
    key text not null,
    kind text not null,
    version bigint not null,
    payload jsonb not null,
    occurred_at timestamptz not null
);
create index events_key on events(key);

-- In-app inbox: one row per (user, event) = exactly one message per event per user.
create table notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    event_id uuid not null references events(event_id),
    kind text not null,
    created_at timestamptz not null default clock_timestamp(),
    read_at timestamptz,
    unique (user_id, event_id)
);
create index notifications_user on notifications(user_id, created_at desc);

-- Email: one row per notification; retried with backoff, DEAD after the last attempt (the email channel's dead letter).
create table deliveries (
    notification_id uuid not null references notifications(id),
    channel text not null check (channel in ('EMAIL')),
    status text not null default 'PENDING' check (status in ('PENDING', 'SENT', 'DEAD', 'SKIPPED')),
    attempts int not null default 0,
    next_attempt_at timestamptz not null default clock_timestamp(),
    last_error text,
    sent_at timestamptz,
    primary key (notification_id, channel)
);
create index deliveries_due on deliveries(next_attempt_at) where status = 'PENDING';

-- Per user and channel; no row = enabled. Payment and refund messages ignore it.
create table preferences (
    user_id uuid not null,
    channel text not null check (channel in ('IN_APP', 'EMAIL')),
    enabled boolean not null,
    primary key (user_id, channel)
);
