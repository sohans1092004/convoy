-- Demo seed (P4.0): SYNTHETIC verified dealerships for the local stack, CI and DEMO=true lite deploys only.
-- Never a Flyway migration: a real environment must never receive fake dealers. Run by dealer's DemoSeed when
-- dealer.demo=true (env DEMO) and this file is mounted (DEMO_SEED_DEALERSHIPS). Idempotent. Not real businesses.
-- d001: the P1 journey dealership (dealer1/dealer2/dealer3/dealeradmin1 in users.json); d002-d004: the dealer simulator (P3.F).
insert into dealerships(id, trade_name, legal_name, gstin, city, state_code, brands, status, synthetic, active_staff_count, verified_at)
values ('0190f3a0-0000-7000-8000-00000000d001', 'Convoy Test Motors, Pune (synthetic)', 'Synthetic test dealership - not a real business',
        null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp())
on conflict (id) do nothing;

insert into dealerships(id, trade_name, legal_name, gstin, city, state_code, brands, status, synthetic, active_staff_count, verified_at) values
  ('0190f3a0-0000-7000-8000-00000000d002', 'Simulated Motors A, Pune (synthetic)', 'Synthetic simulator dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp()),
  ('0190f3a0-0000-7000-8000-00000000d003', 'Simulated Motors B, Pune (synthetic)', 'Synthetic simulator dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp()),
  ('0190f3a0-0000-7000-8000-00000000d004', 'Simulated Motors C, Pune (synthetic)', 'Synthetic simulator dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp())
on conflict (id) do nothing;
