# Services and ownership (PLAN §4.1–4.2, ADR-001)

11 services, all designed now, built in waves. Each owns one PostgreSQL schema with its own DB user and **no grants on other schemas** (CI test queries `information_schema.role_table_grants`). Flyway migrations per service. Cross-service consistency only via events (outbox/inbox) and the order saga; no distributed transactions. Redis is never authoritative (ADR-010).

Standard tables in every schema that writes events or receives them (omitted below): `outbox(id bigserial, event_id uuid unique, event_type, aggregate_id, aggregate_version, payload jsonb, created_at, delivered_at null)`, `inbox(consumer text, event_id uuid, received_at, primary key(consumer, event_id))`, `idempotency_keys(key text, caller uuid, request_sha256 text, status int, response jsonb, created_at, primary key(key, caller))`, `parked_events(...)` for version gaps. All ids `uuid` (v7), all timestamps `timestamptz` UTC, money `bigint` paise, versions `bigint`.

Endpoints: see [api-outline.md](api-outline.md). Events: see [event-catalogue.yaml](event-catalogue.yaml). Failure behaviour assumes the gateway, the service's own DB schema and SQS are up unless stated.

---

## gateway
- **Wave 1.** Spring Cloud Gateway. Stateless.
- **Owns:** no tables. Redis: session-validation cache (≤ 30 s TTL), rate-limit buckets.
- **API:** routes `/api/**` from web (BFF) to services; `/realtime/**` to realtime; JWKS at `/.well-known/jwks.json`; `/webhooks/razorpay` routed to payment without session (signature-verified by payment).
- **Events:** consumes `SessionRevoked`, `StaffRevoked` (evict session cache). Produces none.
- **Scaling signal:** requests/s and p95 latency; CPU.
- **Dependencies down:** identity down → cached sessions keep working until TTL (≤ 30 s), then `503 dependency-unavailable` for authenticated routes (fail closed); a target service down → that route returns 503 after circuit opens (Resilience4j), others unaffected; Redis down → rate limiting falls back to per-instance in-memory buckets, session cache bypassed (every request validates with identity).

## identity
- **Wave 1.** Users, sessions, roles, dealership staff membership, service clients, consent, erasure coordination.
- **Tables:** `users(id, email_hash, email_enc, phone_enc null, name_enc, locale, created_at, status)`, `user_roles(user_id, role)`, `passkeys(id, user_id, credential_id, public_key, sign_count, created_at)`, `otp_challenges(id, email_hash, code_hash, expires_at, attempts)`, `sessions(id_hash, user_id, created_at, last_seen_at, expires_at, revoked_at null, user_agent_hash)`, `dealership_staff(dealership_id, user_id, role, status, invited_by, invited_at, revoked_at)`, `staff_invites(id, dealership_id, email_hash, role, token_hash, expires_at, accepted_at)`, `service_clients(client_id, secret_hash, audiences[], scopes[])`, `signing_keys(kid, public_jwk, private_ref (KMS), status, not_before, retire_after)`, `consents(user_id, purpose, granted, notice_version, at)` (append-only), `erasure_requests(id, user_id, requested_at, status)`, `erasure_confirmations(erasure_id, service, confirmed_at, retained_categories[])`.
- **API:** OTP start/verify, passkey register/authenticate, session validate (gateway only), logout, me, staff invite/accept/revoke, service token (client credentials), JWKS source, consent, export/erasure requests.
- **Events produced:** `UserRegistered`, `SessionRevoked`, `StaffInvited`, `StaffRevoked`, `ErasureRequested`. Consumed: none.
- **Scaling signal:** login rate (OTP bursts); session-validate QPS is absorbed by the gateway cache.
- **Dependencies down:** SES down → OTP emails queue in notification (login delayed, not lost); KMS down → no new key rotation, existing keys keep signing (gateway holds the active key handle).

## catalog
- **Wave 1.** Models, variants, city prices, rulesets, catalogue price estimates.
- **Tables:** `brands(id, name)`, `models(id, brand_id, name, body_type, slug)`, `variants(id, model_id, name, fuel, transmission, engine_cc null, battery_kwh null, seats, status)`, `variant_prices(id, variant_id, city, ex_showroom_paise, evidence, source_url, source_title, accessed_on, effective_from, notes)`, `rulesets(version, published_at, sha256, rto jsonb, tcs jsonb, insurance jsonb, meta jsonb)`, `price_estimates(variant_id, city, ruleset_version, snapshot jsonb, sha256)` (cache of engine output), `colours(variant_id, name)`.
- **API:** list/search models, model detail, variant comparison, price estimate per variant + city, saved cars (buyer), admin catalogue editor (P5).
- **Events produced:** `CatalogPriceUpdated`, `RulesetPublished`. Consumed: none.
- **Scaling signal:** read QPS; CloudFront cache hit ratio (catalogue pages cacheable 5 min).
- **Dependencies down:** none at read time (self-contained); Redis down → reads hit PostgreSQL (W1 measures miss path).

## pool
- **Wave 1.** Pools, memberships, acceptances, rounds, pool deadlines, matching suggestions.
- **Tables:** `pools(id, model_id, city, state_code, accepted_variant_ids uuid[], min_members, state, end_reason, round_set, forming_deadline, dealer_wait_deadline, decision_deadline, confirmation_deadline, purchase_window_end, interested_count, committed_count, last_order_version_seen, version, created_by, created_at)`, `memberships(id, pool_id, buyer_id, model_id, state, current_payment_id, terms_version, accepted_quote_id, accepted_variant_id, release_reason, version, created_at, unique(pool_id, buyer_id))` + partial unique `(buyer_id, model_id) where state in ('COMMIT_PENDING','COMMITTED','ACCEPTED')`, `rounds(id, pool_id, round_set, round_no, kind, state, deadline, closed_at, version, unique(pool_id, round_set, round_no))`, `round_invitations(round_id, dealership_id)`, `quote_replica(quote_id, pool_id, state, variant_ids)`, `quote_acceptance_counts(pool_id, quote_id, count)`, `preferences(buyer_id, city, purchase_month, variant_ids, colours, fuels, transmissions)`.
- **API:** create pool, pool snapshot (versioned, counts), suggestions, join/commit/leave, accept/withdraw acceptance, admin cancel, internal round summary consumer, eligibility check via dealer.
- **Events produced:** all pool events (FIFO topic). Consumed: `PoolReady` (self, wake-up), `PaymentCaptured`, `PaymentFailed`, `PaymentVoided`, `QuoteFinalised`, `QuoteWithdrawn`, `QuoteExpired`, `BookingsIssued`, `AllBookingsClosed`.
- **Calls (sync):** payment `POST /internal/deposits`; quote `GET /internal/rounds/{id}/summary`; dealer `GET /internal/eligibility`.
- **Scaling signal:** membership mutation rate on hot pools (row-lock contention on `pools`), p95 join latency.
- **Dependencies down:** payment down → commit returns 503 (no membership change), join-interested/leave still work; quote down → round transitions deferred by the sweeper (deadline still enforced by quote's own replica once it is back — no late quote can commit); dealer down → round opening deferred; realtime/notification/audit down → no effect on mutations (outbox buffers).

## dealer
- **Wave 1.** Dealerships, verification, stock declarations (catalogue of what a dealership sells), reviews, reliability score, dealer simulator configuration.
- **Tables:** `dealerships(id, legal_name, trade_name, gstin, city, state_code, brand_ids uuid[], status (PENDING_VERIFICATION|VERIFIED|REJECTED|SUSPENDED), registered_by, verified_by, verified_at, rejection_reason, reliability_score, version)`, `verification_documents(id, dealership_id, s3_key, kind, uploaded_at)`, `reviews(id, dealership_id, booking_id unique, buyer_id, rating, text, created_at, hidden_by_admin)`, `review_eligibility(booking_id, buyer_id, dealership_id, delivered_at)`, `reliability_events(id, dealership_id, cause, delta, at)`, `simulator_profiles(dealership_id, strategy, margin_floor_bp, stock jsonb)`.
- **API:** onboarding, admin verify/suspend, eligibility (internal), dealership profile, reviews (buyer write if eligible; public read), analytics for dealer admin (P5, own dealership only).
- **Events produced:** `DealershipVerified`, `DealershipSuspended`, `ReviewPosted`, `ReliabilityScoreChanged`. Consumed: `StaffInvited`, `StaffRevoked`, `QuoteWithdrawn`, `QuoteVoided`, `StockUnavailable`, `BookingIssued`, `BookingDelivered`, `BookingCancelled`, `DisputeOpened`, `DisputeResolved`.
- **Scaling signal:** low; admin and onboarding traffic.
- **Dependencies down:** S3 down → document upload fails (retry); everything else local.

## quote
- **Wave 1.** Quotes, immutable versions, tiers, stock claims, round replicas, ranking, clarifications (P1 feature).
- **Tables:** `quotes(id, pool_id, round_set, dealership_id, state, final_version_no, min_fulfilment, max_units, valid_until, withdraw_reason, after_final, expire_reason, void_reason, version, unique(pool_id, round_set, dealership_id) where state not in terminal)`, `quote_versions(quote_id, version_no, inputs jsonb, snapshots jsonb, snapshot_sha256, diff jsonb, committed_at, committed_by)`, `quote_variant_tiers(quote_id, version_no, variant_id, tier_index, min, max, discount_paise)`, `stock_claims(quote_id, version_no, variant_id, colour, units, available_from, dealer_confirmed)`, `stock_reservations(booking_id unique, quote_id, variant_id, colour)`, `rounds_replica(round_id, pool_id, round_set, round_no, kind, deadline, invited_dealership_ids)`, `rank_snapshots(pool_id, variant_id, quote_id, rank, of_n, computed_at)`, `clarifications(id, quote_id, pool_id, buyer_id, question_type, text, answer, answered_by, answered_at)`, `audit_trail(id, quote_id, actor, action, at, detail)`.
- **API:** dealer inbox (invited pools), draft/submit/revise/withdraw/cancel, version diff, own rank, buyer comparison (FINAL/visible quotes of pools the buyer belongs to), clarifications, internal round summary.
- **Events produced:** quote events. Consumed: `QuoteRoundOpened`, `FinalRoundOpened`, `RoundClosed`, `AcceptancesFrozen`, `PoolClosed`, `TierResolved`, `StockReservationRequested`, `CatalogPriceUpdated`, `RulesetPublished`, `StaffRevoked`, `DealershipSuspended`.
- **Scaling signal:** **deadline bursts** (W4: synchronised submissions in the final 5 s), DB commit latency.
- **Dependencies down:** catalog down → submit still works (ruleset and catalogue prices replicated locally via events); pool down → submissions continue until the replica deadline; realtime down → dealers fall back to polling.

## order
- **Wave 1.** Orders (saga orchestrator), confirmation, tier resolution, bookings, vouchers, disputes, invoice confirmation.
- **Tables:** `orders(id, pool_id, round_set, buyer_id, quote_id, variant_id, state, confirmation_deadline, confirmed_at, cancelled_at, decline_reason, cancel_reason, version, unique(pool_id, buyer_id, round_set))`, `quote_capacity(quote_id, max_units, confirmed_count)`, `price_snapshots(id, order_id, kind (CEILING|FINAL), inputs, output, ruleset_version, sha256, computed_at)`, `pool_resolutions(pool_id, round_set, state, resolved_at, bookings_count, void_quote_count)`, `saga_steps(order_id, step, status, attempts, next_attempt_at, last_error)`, `bookings(id, order_id unique, dealership_id, state, voucher_code_hash, final_snapshot_id, promised_delivery_date, scheduled_date, dealer_confirm_deadline, overdue_flag, delivered_at, cancel_reason, invoice_total_paise, version)`, `disputes(id, order_id, booking_id, dealership_id, category, state, opened_by, owner_id, awaiting_party, respond_by, outcome, appeal_until, appeal_count, version)`, `dispute_history(dispute_id, actor, from_state, to_state, note, at)`, `dispute_evidence(dispute_id, s3_key, content_type, size, uploaded_by)`, `quote_replica(quote_id, state, dealership_id, min_fulfilment, max_units, tiers jsonb, final_version_no)`, `payment_replica(buyer_id, pool_id, payment_id, state)`.
- **API:** my orders, order timeline, confirm/decline/cancel, voucher (signed QR token), bookings (buyer cancel; dealer confirm/schedule/deliver/cancel), disputes (buyer/dealer/admin), invoice confirmation, internal resolution status.
- **Events produced:** order events. Consumed: `AcceptancesFrozen`, `QuoteFinalised`, `QuoteWithdrawn`, `StockReserved`, `StockUnavailable`, `PaymentCaptured`, `DepositHeld`, `DepositHoldRejected`, `RulesetPublished`.
- **Scaling signal:** resolution bursts at confirmation deadlines; saga step backlog.
- **Dependencies down:** payment down → confirmation still works (deposit already captured); `DepositHoldRequested` waits in the outbox/queue; saga step retries; quote down → stock reservation step retries, bookings stay ISSUED (dealer cannot confirm until RESERVE_STOCK done — W7).

## payment
- **Wave 1.** Razorpay test-mode orders, webhooks, refunds, ledger, reconciliation. Isolated (separate task, narrow IAM, its own secret).
- **Tables:** `payments(id, deposit_key unique, buyer_id, pool_id, round_set, amount_paise, state, razorpay_order_id, razorpay_payment_id, refund_on_capture, booking_id, captured_at, version)`, `refunds(id, payment_id unique, state, reason_code, related_reasons text[], source_account, amount_paise, razorpay_refund_id, attempts, next_attempt_at, manual, resolved_by, version)`, `webhook_inbox(event_id pk, event_type, received_at, signature_ok)`, `journal_entries(id, kind, payment_id, refund_id, posted_at)`, `journal_lines(entry_id, account, dr_paise, cr_paise)`, `reconciliation_runs(id, started_at, finished_at, mismatches)`, `reconciliation_items(run_id, class, payment_id, refund_id, detail, resolved_by)`.
- **API:** internal create deposit; checkout callback; Razorpay webhook; my payment status; admin refunds queue / retry / manual resolve; admin reconciliation.
- **Events produced:** payment events. Consumed: `MemberLeft`, `MemberReleased`, `AcceptancesFrozen`, `PoolClosed`, `DepositRefundRequested`, `DepositHoldRequested`, `BookingDelivered`, `ErasureRequested`.
- **Scaling signal:** webhook rate (W6), refund worker backlog.
- **Dependencies down:** Razorpay down → commit returns 503 (no order), refunds back off and retry; pool/order down → events queue; webhooks are accepted independently of other services.

## realtime
- **Wave 2** (polling fallback before). SSE fan-out; holds no business state.
- **Owns:** no business tables. Its schema holds only the platform tables (`inbox`, `aggregate_versions`, `parked_events`): two or more realtime tasks share one SQS queue, and the stale/gap rule for its FULL pool subscription needs shared state that Redis may lose (P2). In-memory per-connection bounded queues; Redis Pub/Sub for cross-instance fan-out.
- **API:** `GET /realtime/pools/{id}/stream`, `GET /realtime/dealer/stream`, `GET /realtime/me/stream` (SSE, auth via gateway JWT).
- **Events consumed:** pool, quote, order, payment events listed in the catalogue (to push version-stamped deltas), `SessionRevoked`, `StaffRevoked` (drop connections). Produces none.
- **Scaling signal:** open connections per task (target ≤ 5,000), fan-out lag.
- **Dependencies down:** Redis down → only same-instance fan-out; clients still converge via snapshot (realtime-recovery.md). If realtime itself is down, UIs poll snapshots every 10 s.

## notification
- **Wave 2.** In-app and email notifications (SMTP: Mailpit locally, the SES SMTP interface on AWS; AMENDMENT-011); web push P1.
- **Tables (as built in P5.A):** `links(key, target, from_version, to_version)` (recipient graph: pool/order/quote/booking/dispute -> buyer), `events(event_id, key, kind, version, payload, occurred_at)`, `notifications(id, user_id, event_id, kind, created_at, read_at, unique(user_id, event_id))`, `deliveries(notification_id, channel, status PENDING|SENT|DEAD|SKIPPED, attempts, next_attempt_at, last_error, sent_at)`, `preferences(user_id, channel IN_APP|EMAIL, enabled)`. Templates (en + Hindi draft) are a versioned resource file, not a table.
- **API:** my notifications (E130 list, E131 mark read, E158 mark all), preferences (E159 read, E132 set). Payment and refund messages ignore preferences. Web push (P5.B): per-device subscriptions (E160 key, E161 subscribe, E162 unsubscribe); a payload-free VAPID push per message to subscribed devices, the service worker fetches the text itself.
- **Events consumed:** user-facing events (see catalogue). Produces none.
- **Scaling signal:** **queue depth / oldest message age** (W8: 100,000 in 30 min).
- **Dependencies down:** SMTP (SES) down → deliveries retry with backoff, DEAD after the last attempt; in-app still written; nothing upstream blocks.

## audit
- **Wave 3.** Append-only audit log fed by every outbox (all events), sensitive-access log, admin read models and analytics views.
- **Tables:** `audit_log(seq identity, event_id unique null, event_type, producer, aggregate_type, aggregate_id, aggregate_version, actor, correlation_id, sensitive bool, occurred_at, recorded_at, payload jsonb, prev_hash, hash)`: one hash chain (`row_hash`), app role `audit` has INSERT/SELECT only, a trigger refuses UPDATE/DELETE/TRUNCATE, the schema is owned by `audit_owner` (migrations). Sensitive access (viewer reads, export downloads) is a row with `sensitive = true` in the same chain (P5.C), not a second table. Read models and analytics views: later in P5.
- **API:** E163 admin audit search (every read is audited). Chain verification is a command: `docker compose run --rm audit verify-chain` (exit 1 on the first broken link). Runbook: [audit.md](../runbooks/audit.md).
- **Events consumed:** all. Produces none.
- **Scaling signal:** ingest lag (outbox → audit), DB write rate.
- **Dependencies down:** audit down → nothing else blocks; events wait in its queue; reconciliation of owner counts vs audit counts detects omissions (PLAN §4.6).

---

## Build waves
| Wave | Services | Notes |
|---|---|---|
| 1 | gateway, identity, catalog, pool, dealer, quote, order, payment | P1 walking skeleton uses thin versions of all eight |
| 2 | realtime, notification | P2 (realtime), P5 (notification); polling + inline "check your email" until then |
| 3 | audit (+ admin read models, analytics views) | P5; until then each service keeps its own `audit_trail` rows |

Placement decisions not explicit in PLAN (Q-021): reviews and reliability → dealer; clarifications → quote; disputes, bookings, invoice confirmation → order; admin read models and analytics views → audit.
