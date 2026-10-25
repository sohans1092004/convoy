-- P4.A: a confirmation records the exact ceiling price (cash payable) and the CEILING snapshot it came from (tier-resolution.md).
alter table orders add column ceiling_snapshot_id uuid references price_snapshots(id);
alter table orders add column confirmed_ceiling_paise bigint check (confirmed_ceiling_paise > 0);
alter table orders add constraint confirmed_records_ceiling check (confirmed_at is null or (ceiling_snapshot_id is not null and confirmed_ceiling_paise is not null));
