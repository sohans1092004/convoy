-- P2 scope B: a buyer's saved cars (E024-E026).
create table saved_cars (
    buyer_id uuid not null,
    model_id uuid not null references models(id),
    saved_at timestamptz not null default clock_timestamp(),
    primary key (buyer_id, model_id)
);
