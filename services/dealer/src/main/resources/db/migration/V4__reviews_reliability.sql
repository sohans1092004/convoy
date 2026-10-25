-- P4.E: reviews (only a buyer with a delivered booking, once per booking) and the reliability score (AMENDMENT-009, closes Q-036).

-- Replica of BookingDelivered: who may review whom.
create table delivered_bookings (
    booking_id    uuid primary key,
    dealership_id uuid not null references dealerships(id),
    buyer_id      uuid not null,
    delivered_at  timestamptz not null
);

create table reviews (
    id            uuid primary key,
    booking_id    uuid not null unique references delivered_bookings(booking_id),
    dealership_id uuid not null references dealerships(id),
    buyer_id      uuid not null,
    rating        int not null check (rating between 1 and 5),
    body          text check (char_length(body) <= 2000),
    created_at    timestamptz not null default clock_timestamp()
);

-- One row per FINAL commitment outcome: honoured (delivered) or not (dealer cancelled, did not confirm, stock gone, FINAL quote withdrawn).
create table reliability_events (
    source_event_id uuid primary key,
    dealership_id   uuid not null references dealerships(id),
    honoured        boolean not null,
    cause           text not null,
    occurred_at     timestamptz not null default clock_timestamp()
);
create index reliability_window on reliability_events (dealership_id, occurred_at);
