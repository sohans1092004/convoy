-- Web push (P5.B). A user opts in per device by subscribing; each subscription is one browser's push endpoint.
-- No payload is sent (the service worker fetches the newest message itself), so p256dh/auth are kept only to
-- allow encrypted payloads later.
create table push_subscriptions (
    endpoint text primary key,
    user_id uuid not null,
    p256dh text not null,
    auth text not null,
    created_at timestamptz not null default clock_timestamp()
);
create index push_subscriptions_user on push_subscriptions(user_id);

alter table deliveries drop constraint deliveries_channel_check;
alter table deliveries add constraint deliveries_channel_check check (channel in ('EMAIL', 'PUSH'));
