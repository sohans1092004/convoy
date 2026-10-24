# Threat model (STRIDE per service + marketplace integrity)

Scope: the system in deployment-profiles.md. Assets: buyer PII (email, phone, name), sessions, deposits/ledger, sealed quote values and ranks, dealer verification documents, dispute evidence, audit log integrity, price rules. Trust boundaries: browser ↔ CloudFront/web; web ↔ gateway; gateway ↔ services; services ↔ PostgreSQL/Redis/AWS; payment ↔ Razorpay; admin console.

Legend: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege. Test IDs refer to [authz-matrix.md](../testing/authz-matrix.md) and [messaging-failure-tests.md](../testing/messaging-failure-tests.md).

| Service | Threat | Control | Verified by |
|---|---|---|---|
| web/BFF | S: stolen session cookie | HttpOnly+Secure+SameSite=Lax, sliding expiry, session list + revoke, user-agent hash anomaly flag | AZ-E006-A |
| web/BFF | T/E: header injection of identity | BFF strips identity headers; services reject them | AZ-INT-03 |
| web/BFF | I: XSS exfiltration | CSP (nonce-based, no inline), React escaping, no `dangerouslySetInnerHTML` lint rule | CI lint + ZAP baseline (P7) |
| gateway | S: forged internal JWT | ES256, KMS-held key, `aud` per service, 2-min `exp`, JWKS rotation | AZ-SVC-01 |
| gateway | D: request floods, OTP abuse | CloudFront + WAF rate rules (full), Caddy/gateway rate limits per IP and per session, OTP limits per email hash | AZ-E001-A (429 path) |
| gateway | I: stale authorisation after revoke | session cache ≤ 30 s + revocation events | AZ-T6 |
| identity | S: OTP brute force | 6 digits, 5 attempts, 10 min, per-IP and per-email limits, constant-time compare | identity unit tests |
| identity | I: PII at rest | email/phone/name encrypted (KMS data key, envelope), email lookup by keyed hash | schema review |
| identity | R: staff action denial | staff invites/revocations audited with actor | AZ-E012-A |
| catalog | T: price/rules tampering | admin-only, If-Match, ruleset versions immutable + hashed, audit | AZ-E027-D, AZ-T7 |
| pool | T: inflated counts (duplicate joins) | unique (pool, buyer), idempotency keys, one active commitment per model | AZ-INT-01, W3 |
| pool | I: member enumeration | snapshot exposes counts only; no member list endpoint for dealers | AZ-T1 |
| pool | E: dealer staff joining pools | role check (staff accounts cannot hold buyer role in pool commands) | AZ-INT-02 |
| dealer | S: fake dealership | admin verification with GSTIN + documents; unverified dealers are never invited | AZ-E053-D |
| dealer | T: fake reviews | review requires delivered booking for that dealer; one per booking | AZ-INT-07 |
| quote | I: sealed-value / rank leakage | service-side checks per dealership; rank endpoint returns own rank only; comparison only to pool members | AZ-T3, AZ-T5, AZ-RNK-01..03 |
| quote | T: post-deadline submission | DB-clock commit-time rule | W4, deadline tests |
| quote | R: dealer denies a quote version | immutable versions with committed_by, hash, audit | quote version tests |
| order | T: price changed after confirmation | buyer echoes ceiling snapshot hash; final ≤ ceiling invariant | tier vectors, ORD-T02 |
| order | I: contact details leak via quote/booking ids | booking exposes dealer-facing buyer data only after booking issued and only needed fields (name, phone) — purpose-limited | AZ-T2 |
| order | S: voucher forgery | signed QR token (ES256, kid), single-use on delivery | AZ-E090-X |
| payment | S/T: forged webhook, replay | HMAC on raw body, event-id dedupe, amount/order match | AZ-INT-06, W6 |
| payment | T: double refund | unique refund per payment, lookup-before-create with receipt | D19, D21 vectors |
| payment | E: admin refund abuse | second admin above threshold, audit | AZ-INT-08 |
| payment | D: Razorpay outage | 503 on commit (no partial state), refund backoff | W7 |
| realtime | I: cross-pool stream subscription | connect-time authz + periodic re-validation | AZ-E120-X |
| realtime | D: slow consumers / reconnect storm | bounded queues, jittered retry hints | W5 |
| notification | I: email to wrong user | recipient resolved from user id at send time, templates contain no other users' data | template tests |
| notification | D: backlog | SQS + DLQ, oldest-message-age alarm | W8 |
| audit | T: log tampering | INSERT/SELECT role, update/delete trigger, hash chain, chain verification job | E141 |
| audit | R: missing events (omission) | outbox lag + owner-vs-audit count reconciliation (PLAN §4.6) | messaging test 6 |
| all | T: cross-schema access | per-service DB users, no foreign grants (CI query) | CI grant test |
| all | I: secrets in code/logs | Secrets Manager, log redaction filter, gitleaks in CI | CI |

## Marketplace integrity (PLAN §4.8)
| Risk | Control | Detection signal | Owner | Test / evidence |
|---|---|---|---|---|
| Fake or duplicate buyers | OTP rate limits; one active commitment per model; device/IP heuristics flag for review | >N accounts per device/IP hash per day; commits from flagged accounts | identity, pool | AZ-INT-01 |
| Dealers inflating demand | verified dealers only; staff accounts can't join pools; links between staff and buyer accounts (same device/phone/email domain heuristics) flagged | staff↔buyer link flags | dealer, identity, pool | AZ-INT-02 |
| Fake discounts | §2.5 labels; catalogue reference; quoted vs final (invoice) price recorded | dealer discount ≫ catalogue difference; invoice ≠ final | quote, order | savings-model.md |
| Quote leakage | service-side checks; audited reads; ranks without values | sensitive-access log anomalies | quote, audit | AZ-T3, AZ-T5, AZ-RNK-* |
| Collusion / misleading quotes | anomaly flags (identical line items across dealers, identical scores, same submission second) for admin review; **a flag is never proof** | flag queue | quote, audit | P5 admin queue |
| Payment replay | HMAC, idempotency, event dedupe | duplicate counters | payment | W6, D18 |
| Fake reviews | only verified-delivery buyers; one review per booking | — | dealer | AZ-INT-07 |
| Admin abuse | least privilege; sensitive-action audit; second admin for refunds above threshold and money dispute outcomes | sensitive-access log | all | AZ-T7, AZ-INT-08 |

Legal/regulatory items (consumer protection, competition law, dealer contracts, payments, DPDP) are flagged, not resolved (PLAN §1).
