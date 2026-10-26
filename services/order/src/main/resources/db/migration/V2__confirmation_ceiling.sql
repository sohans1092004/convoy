-- P4.A: a confirmation records the exact ceiling price (cash payable) and the CEILING snapshot it came from (tier-resolution.md).
alter table orders add column ceiling_snapshot_id uuid references price_snapshots(id);
alter table orders add column confirmed_ceiling_paise bigint check (confirmed_ceiling_paise > 0);
-- Orders confirmed before P4 confirmed against their CEILING snapshot (the confirm API checked its hash): record it.
update orders o set ceiling_snapshot_id = s.id, confirmed_ceiling_paise = (s.output->>'cash_payable_paise')::bigint
    from price_snapshots s where s.order_id = o.id and s.kind = 'CEILING' and o.confirmed_at is not null;
alter table orders add constraint confirmed_records_ceiling check (confirmed_at is null or (ceiling_snapshot_id is not null and confirmed_ceiling_paise is not null));
