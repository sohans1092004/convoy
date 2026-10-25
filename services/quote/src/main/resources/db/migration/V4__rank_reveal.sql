-- Ranks computed when the sealed round closes stay hidden until the final round opens (owner's P3.C follow-up).
alter table rank_snapshots add column revealed boolean not null default true;
