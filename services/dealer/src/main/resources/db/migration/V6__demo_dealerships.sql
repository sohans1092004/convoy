-- P6.B public demo: a visitor's own dealership, owned by the scenario's buyer, invited only to that buyer's pools (E055).
alter table dealerships add column demo_owner uuid;
create index dealerships_demo on dealerships(created_at) where demo_owner is not null;
