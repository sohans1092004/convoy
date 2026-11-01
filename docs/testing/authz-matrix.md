# Authorisation matrix and test IDs (PLAN §4.8)

All tests run in CI against real service boundaries (Testcontainers + the gateway) from P1 onward; each ID is one test case. Endpoint-level IDs (`AZ-<endpoint>-A/-D/-X`) are listed per endpoint in [api-outline.md](../architecture/api-outline.md); their meaning:
- **`-A` allow:** each listed role succeeds on a resource it owns/belongs to.
- **`-D` deny:** every unlisted role gets 401 (anonymous) or 403; requests with forged `X-User-*` / `X-Convoy-Session` headers or a JWT for another `aud` are rejected.
- **`-X` cross-tenant:** a listed role gets **404** on a resource belonging to another buyer / dealership / pool it is not part of.

## Roles
| Role | Who | Source |
|---|---|---|
| anonymous | no session | — |
| buyer | registered buyer | identity `user_roles` |
| dealer_staff | staff of exactly one dealership | identity `dealership_staff` |
| dealer_admin | admin of exactly one dealership (also has dealer_staff rights) | identity `dealership_staff` |
| platform_admin | Convoy admin | identity `user_roles` |
| service | another Convoy service with a service token (`service:<name>` in api-outline) | identity `service_clients` |
| razorpay | webhook caller authenticated by HMAC | payment |
| service:demo-bots | the public demo's simulated-dealer runner (P6.B, tools/simulator/demo-bots.mjs; lite only) | identity `service_clients`, only when `CLIENT_SECRET_DEMO_BOTS` is set |

A user cannot hold `buyer` together with `dealer_staff`/`dealer_admin` for pool commands (AZ-INT-02).

## Role × resource × action
A = allow, O = allow on own resource only (own account / own membership / own order), D = allow for own dealership only, P = allow if the pool membership or invitation condition holds, — = deny.

| Resource | Action | anonymous | buyer | dealer_staff | dealer_admin | platform_admin | Test IDs |
|---|---|---|---|---|---|---|---|
| Catalogue models/variants/estimates | read | A | A | A | A | A | AZ-E020-A, AZ-E023-A |
| Catalogue prices / rulesets | write | — | — | — | — | A (audited) | AZ-E027-D, AZ-E028-D |
| Saved cars, preferences | read/write | — | O | — | — | — | AZ-E024-D, AZ-E031-D |
| Pool public snapshot (counts, state, deadlines) | read | — | A | P (invited) | P (invited) | A | AZ-E033-A, AZ-E033-X |
| Pool member list / identities | read | — | — | — | — | A (audited) | **AZ-T1**, AZ-T7 |
| Membership / commitment | create/leave/accept | — | O | — | — | — | AZ-E034-D, AZ-INT-02 |
| Another buyer's membership / payment | read | — | — | — | — | A (audited) | **AZ-T4** |
| Quote (draft, versions, diff) | read/write | — | — | D | D | A (audited) | AZ-E067-X, **AZ-T5** |
| Quote cancel after FINAL | write | — | — | — | D | — | AZ-E066-D |
| Quote comparison (visible versions, breakdown) | read | — | P (pool member) | — | — | A | AZ-E069-X |
| Own rank per variant | read | — | — | D | D | A | AZ-RNK-01..03 |
| Clarification (ask / answer) | write | — | P (committed member, FINAL quote) | D (answer, own quote) | D (answer, own quote) | — | AZ-E071-X, AZ-E072-X |
| Clarification answers | read | — | P (pool member; own open questions) | D (all of own quote) | D | — | AZ-E070-X |
| Competitor quote values / ranks | read | — | — | — | — | A (audited) | **AZ-T3**, AZ-T5 |
| Buyer contact details | read | — | O | after booking, own dealership's bookings only, purpose-limited fields | same | A (audited) | **AZ-T2** |
| Order / confirmation / voucher | read/write | — | O | — | — | A (read, audited) | AZ-E081-X, AZ-E082-X |
| Booking (dealer actions) | write | — | — | D | D | A (cancel via dispute) | AZ-E088-X |
| Booking cancel (buyer) | write | — | O | — | — | — | AZ-E086-X |
| Dispute | open/respond/appeal | — | O | D (respond) | D (respond) | A (assign/resolve) | AZ-E093-X |
| Dispute evidence | read | — | O | D | D | A (audited) | AZ-E093-X, AZ-T7 |
| Refund queue / manual resolve | read/write | — | — | — | — | A (+ second, different admin; AMENDMENT-001) | AZ-INT-08 |
| Dealer analytics | read | — | — | — | D | A | **AZ-T5** |
| Marketplace analytics (E169) | read | — | — | — | — | A | AZ-E169-D |
| Invoice total (E101) | write | — | O (delivered booking, once) | — | — | — | AZ-E101-X |
| Staff invite/revoke | write | — | — | — | D | A | AZ-E012-X, **AZ-T6** |
| Reviews | write | — | O (delivered booking only) | — | — | hide only | AZ-INT-07 |
| Audit log | read | — | — | — | — | A (access itself audited) | AZ-E140-D, **AZ-T7** |

## Named tests
| ID | PLAN §4.8 | Test |
|---|---|---|
| AZ-T1 | 1 | Dealer staff of an invited dealership calls every pool and realtime endpoint (E033, E060, E120) and searches responses for buyer ids/emails/names: none present; no endpoint lists members for dealer roles. |
| AZ-T2 | 2 | Dealer staff with a valid quote id calls E070-style buyer endpoints, E072, E087 for a pool whose buyers have not booked with it: no buyer email/phone/name appears; after a booking with *that* dealership, only purpose-limited fields of *that* buyer appear. |
| AZ-T3 | 3 | Buyer accepts dealer A's quote (E037); dealer B's endpoints (E060, E067, E068, E121 stream) reveal nothing about the acceptance or A's values: response bodies identical to before the acceptance except own-quote fields. |
| AZ-T4 | 4 | Buyer X requests buyer Y's membership (E039 with Y's pool), order (E081), payment (E112), voucher (E085): all 404; list endpoints never include Y. |
| AZ-T5 | 5 | Staff of dealership D1 requests D2's quotes (E067), submits on D2's quote (E063), reads D2's analytics (E058) and bookings (E087): all 404/filtered. |
| AZ-T6 | 6 | Dealer admin revokes staff S (E012) at t0; S polls E060 every 1 s and holds an SSE connection (E121): first rejection ≤ t0 + 30 s, zero successes after it, SSE closed ≤ t0 + 30 s. |
| AZ-T7 | 7 | Admin reads sensitive data (E041 member identities, E093 evidence, E114, E140): each read writes exactly one `sensitive_access` row with actor, resource, correlation id; the row is in the hash chain. |
| AZ-RNK-01 | — | Rank response has exactly `{variant_id, rank, of_n, rule_version, computed_at}`. |
| AZ-RNK-02 | — | No rank exists or is returned during the sealed round. |
| AZ-RNK-03 | — | Dealer cannot obtain another dealership's rank by quote id (404). |
| AZ-SVC-01 | — | Service endpoints reject human JWTs and gateway JWTs with wrong `aud`. |
| AZ-SVC-02 | — | Token endpoint issues tokens only with the client's registered audiences/scopes. |
| AZ-SVC-03 | — | payment `/internal/deposits` rejects a valid service token lacking `deposits:create`. |
| AZ-INT-01 | integrity | Same buyer cannot join twice or hold two active commitments for one model (DB constraint, not just app check). |
| AZ-INT-02 | integrity | A user with a dealer role cannot join/commit to any pool. |
| AZ-INT-03 | — | Forged identity headers from the browser are stripped by the BFF and rejected by services. |
| AZ-INT-06 | integrity | Razorpay webhook with bad signature → 400, no state change; replayed valid webhook → no second effect. |
| AZ-INT-07 | integrity | Review without a delivered booking with that dealership → 403 review-not-eligible; second review for same booking → 409. |
| AZ-INT-08 | integrity | Every manual refund resolution, ledger adjustment or override requires approval by a different admin, whatever the amount (AMENDMENT-001); same admin approving → 403; automatic rule-driven refunds need no approval. |
