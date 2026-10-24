-- P2 scope B: a buyer's matching preferences (services.md; E031). Empty arrays mean "any".
create table preferences (
    buyer_id       uuid primary key,
    city           text,
    purchase_month text,
    variant_ids    uuid[] not null default '{}',
    colours        text[] not null default '{}',
    fuels          text[] not null default '{}',
    transmissions  text[] not null default '{}',
    updated_at     timestamptz not null default clock_timestamp()
);
