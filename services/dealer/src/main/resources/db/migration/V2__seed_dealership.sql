-- One SYNTHETIC verified dealership for the P1 journey (PLAN honest limits: no real dealers).
-- The id matches deploy/local/seed/users.json (dealer1@convoy.test is its staff member).
insert into dealerships(id, trade_name, legal_name, gstin, city, state_code, brands, status, synthetic, active_staff_count, verified_at)
values ('0190f3a0-0000-7000-8000-00000000d001', 'Convoy Test Motors, Pune (synthetic)', 'Synthetic test dealership - not a real business',
        null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp());
