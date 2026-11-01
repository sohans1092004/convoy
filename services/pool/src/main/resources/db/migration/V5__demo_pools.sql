-- P6.B public demo: a demo visitor's pools are theirs alone (one forming pool per model, city, month and owner);
-- nobody else finds or is suggested them, and only the owner's demo dealership plus the shared simulated dealers are invited.
alter table pools add column demo_owner uuid;
drop index one_forming_pool;
create unique index one_forming_pool on pools (model_slug, city, purchase_month, coalesce(demo_owner, '00000000-0000-0000-0000-000000000000'::uuid)) where state = 'FORMING';
