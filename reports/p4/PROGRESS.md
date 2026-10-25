# P4 progress

- 18:31Z P4.0 started on p4/money-orders (from main after merging PR #5, approved). Demo dealerships moved from Flyway (V2, V5) to deploy/local/seed/dealerships.sql run by dealer DemoSeed when DEMO=true; compose demo duration defaults; .env.example/dev-env DEMO=true; DealerIT runs the seed + guards migrations against demo data (pass). Local DB: V2/V5 history rows removed (local repair only).
