-- P3.F: three SYNTHETIC verified dealerships driven by the dealer simulator (tools/simulator, docs/product/dealer-simulator.md).
-- Same city and brand as the P1 synthetic dealership, so they are invited to the demo pools. Not real businesses; ids
-- match deploy/local/seed/users.json (one staff member each).
insert into dealerships(id, trade_name, legal_name, gstin, city, state_code, brands, status, synthetic, active_staff_count, verified_at) values
  ('0190f3a0-0000-7000-8000-00000000d002', 'Simulated Motors A, Pune (synthetic)', 'Synthetic simulator dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp()),
  ('0190f3a0-0000-7000-8000-00000000d003', 'Simulated Motors B, Pune (synthetic)', 'Synthetic simulator dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp()),
  ('0190f3a0-0000-7000-8000-00000000d004', 'Simulated Motors C, Pune (synthetic)', 'Synthetic simulator dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp())
on conflict (id) do nothing;
