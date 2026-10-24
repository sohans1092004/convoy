-- catalog schema (services.md §catalog). Prices in paise; every price row names its source and evidence.
create table brands (
    id   uuid primary key,
    name text not null unique
);

create table models (
    id           uuid primary key,
    brand_id     uuid not null references brands(id),
    slug         text not null unique,
    name         text not null,
    body_type    text,
    source_url   text not null,
    source_title text not null,
    accessed_on  date not null,
    price_label  text,
    city_basis   text
);

create table variants (
    id           uuid primary key,
    model_id     uuid not null references models(id),
    code         text not null unique,
    name         text not null,
    fuel         text,
    transmission text,
    engine_cc    int,
    note         text,
    status       text not null default 'ACTIVE'
);

create table variant_prices (
    variant_id        uuid not null references variants(id),
    city              text not null,
    state_code        text not null,
    ex_showroom_paise bigint not null check (ex_showroom_paise > 0),
    evidence          text not null check (evidence in ('DEALER_CONFIRMED', 'CATALOGUE_ESTIMATE', 'UNVERIFIED')),
    city_confirmed    boolean not null,
    source_url        text not null,
    accessed_on       date not null,
    primary key (variant_id, city)
);

create table rulesets (
    version      text primary key,
    sha256       text not null,
    content      jsonb not null,
    published_at timestamptz not null default clock_timestamp()
);

-- Stored calculation snapshots: inputs, ruleset version, output, hash (pricing-model.md §Snapshots).
create table price_estimates (
    id              uuid primary key,
    variant_id      uuid not null references variants(id),
    city            text not null,
    ruleset_version text not null,
    inputs          jsonb not null,
    output          jsonb not null,
    sha256          text not null,
    computed_at     timestamptz not null default clock_timestamp(),
    unique (variant_id, city, sha256)
);
