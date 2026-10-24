-- dealer schema (services.md §dealer), P1 subset.
create table dealerships (
    id                 uuid primary key,
    trade_name         text not null,
    legal_name         text not null,
    gstin              text,
    city               text not null,
    state_code         text not null,
    brands             text[] not null,
    status             text not null check (status in ('PENDING', 'VERIFIED', 'SUSPENDED')),
    synthetic          boolean not null default true,
    active_staff_count int not null default 0,
    verified_at        timestamptz,
    reliability_score  int,
    version            bigint not null default 1
);
