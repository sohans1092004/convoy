-- P3.C ranking (ranking-rule.md). Each version stores its per-variant ranking score at commit (versions stay immutable:
-- ADD COLUMN fires no row trigger); rank snapshots are append-only {pool, variant, quote, rank, of_n, computed_at}.
alter table quote_versions add column ranking jsonb not null default '{}';

create table rank_snapshots (
    id            bigserial primary key,
    pool_id       uuid not null,
    round_set     int not null,
    variant_id    uuid not null,
    quote_id      uuid not null,
    dealership_id uuid not null,
    rank          int not null,
    of_n          int not null,
    rule_version  text not null,
    computed_at   timestamptz not null default clock_timestamp()
);
create index rank_latest on rank_snapshots (quote_id, variant_id, id desc);
