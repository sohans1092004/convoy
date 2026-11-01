# Convoy Phase 2: split into small subtasks

**How to use:**
1. Save the full `P2_PROMPT.md` into the repo as `docs/prompts/P2.md`, so the agent always has the detailed context.
2. Paste **one subtask at a time**: the "Rules for every subtask" block below plus one subtask block.
3. The agent stops when the subtask is done and prints a short report. Paste that report to me, and I'll check it before you send the next subtask.

If the agent is already in the middle of the full P2 prompt, tell it: *"Stop at the end of what you're doing now, commit, and report using the short format below."* Then continue from the matching subtask.

---

## Rules for every subtask (paste this block above each subtask)

```
You are working on Convoy Phase 2, one subtask at a time. Full context: docs/prompts/P2.md,
PLAN.md, SPEC.md (+ amendments), reports/P1_REPORT.md. Same ground rules as P1:
no AWS, no paid services, integer paise, DB clock decides deadlines, no invented data,
secrets only in .env, stop and ask before changing a frozen rule.

Branch: p2/pools-realtime. Commit when the subtask is done and CI is green.
Do ONLY the subtask below. Don't start the next one.

Progress: every ~20 minutes of work, append 2-3 lines to reports/p2/PROGRESS.md
(what's done, what's next, any blocker) and print the same lines to me.

When done, print this short report (max ~25 lines):
- DONE: what was built (bullets)
- EVIDENCE: tests added/passing (counts), key numbers, commit hash, CI status
- DEVIATIONS: anything different from the spec, and why
- PROBLEMS: bugs found, anything flaky or unfinished
- NEEDS FROM ME: actions or decisions
```

---

## P2.A: Real Razorpay test-mode run
- Using the keys in `.env` and the tunnel and webhook I registered, run the P1 journey once against real Razorpay test mode. Cover **one deposit capture and one refund**.
- Record the payment and refund IDs, the webhook events received and their order, and the timings. List any difference from the fake (payload fields, status names, timing, retries).
- Update the fake and its contract tests to match. Switch `.env` back to the fake.
- **Stop cloudflared** when done, and tell me so I can disable the webhook.

**Done when:** the real capture and refund are recorded in `reports/p2/razorpay-real-run.md`, and the fake contract tests pass with the updates.

## P2.B: Typed APIs
- Replace untyped map responses with DTO records on every P1 endpoint.
- Regenerate the TypeScript client from springdoc and delete the hand-written types in `web`.
- Add a CI step that fails when the generated client is stale.

**Done when:** no endpoint returns `Map`/`Object`, the web build uses only generated types, the P1 E2E still passes, and the CI stale check works (prove it by changing a DTO without regenerating).

## P2.C: Correctness fixes from the P1 review
1. **Atomic idempotency:** store the record (key, request hash, response) in the same transaction as the change. Reusing a key with a different body returns `422 IDEMPOTENCY_KEY_REUSED`. Add a crash test between the commit and the response.
2. **Version semantics:** every consumer applies the stale check (version ≤ last applied → ignore and count it). Only "all event types" subscriptions apply gap detection (park, retry, snapshot). Record the kind of each subscription in the event catalogue, and make `validate_spec.py` check it.
3. **One open pool per key:** a partial unique index on (model, city, month) where state is FORMING. 50 concurrent `POST /pools` must create exactly 1 pool.
4. **AMENDMENT-007:** pool minimum 5 in production and 1 in demo (Q-042); success fee a flat ₹2,500 per delivered booking, recorded as a dealer receivable (Q-041).
5. **Honest UI:** every UI rule whose path still returns 501 shows a clear message. List each place.

**Done when:** all five are in, with tests; the spec validator passes; the P1 E2E passes.

## P2.D: Pool exits and refunds
- Build `EXPIRED` and `NO_QUOTES` from the YAML tables, with automatic full refunds, reason codes and explanations for the buyer.
- **Real refund path:** REQUESTED → INITIATED → REFUNDED, and REFUND_FAILED with backoff, an alarm and an admin queue entry.
- **Late capture:** replace the DLQ outcome with an automatic `LATE_CAPTURE` refund. Test it with a delayed webhook.
- Extend the invalid-transition tests to the new transitions.

**Done when:** tests prove every deposit in an expired or no-quotes pool ends REFUNDED, a forced refund failure retries and lands in the admin queue, late capture is auto-refunded, and the ledger balances in every test.

## P2.E: Saved cars, preferences and pool matching
- **Backend:** saved models; preferences (city, purchase month, acceptable variants, colours, fuel, transmission); matching as a pure function with unit tests; suggestions with an honest status label; "start a pool" when nothing matches.
- **Frontend:** a save button on the model page, a preferences form (React Hook Form + Zod), and a suggestions list on the home page.

**Done when:** the matching unit tests cover edge cases (no match, several matches, closing soon, full pool), the screens work at 360 px and on desktop, and axe is clean.

## P2.F: Realtime service (backend only)
- **Service:** a new `realtime` service behind the gateway route `/stream`, serving SSE. It accepts the internal JWT and authorises each subscription per resource (dealers only see rounds they were invited to, never buyer identities).
- **Events:** it consumes the pool, membership and round events listed for realtime, and fans them out across instances via Redis Pub/Sub.
- **Delivery:** each message carries the aggregate ID and version. Heartbeat every 15 s. Each connection has a bounded queue, and slow consumers are dropped with a reason.
- **Recovery:** clients reconnect, fetch the owner's snapshot endpoint, then apply only newer versions.
- **Compose:** run **2 realtime instances**.

**Done when:** an integration test shows a client on instance A receiving an event processed by instance B; a reconnect after a missed event recovers through the snapshot; a Redis flush mid-session still ends in correct state; and an unauthorised subscription is refused.

## P2.G: Messaging failure tests MF-1 to MF-6
Implement each scenario exactly as written in `messaging-failure-tests.md`, using real fault injection (kill, Toxiproxy, pause, or test-only hooks):
1. crash after commit, before publish
2. broker accepted, relay crashed before marking delivered
3. consumer committed, crashed before acknowledging
4. two events for one pool out of order
5. a poison message: DLQ, alarm and redrive runbook
6. a consumer offline for a long time, then replaying its backlog (record drain time)

Each test asserts **business outcomes**: counts, states and ledger.

**Done when:** all six pass in CI, and the report includes a small table of scenario → expected → observed.

## P2.H: Live pool UI and interaction states
- **Live updates:** an SSE hook with reconnect, snapshot-and-version merge and a polling fallback; a server-time countdown; a "live / reconnecting / delayed" indicator.
- **Optimistic join/leave:** rolls back on error, and never double-counts your own action when the live event arrives.
- **States:** all 8 states (loading, empty, error that keeps input, stale, pending, conflict, offline, success) on the **discover, join and pool pages**. Each has a Storybook story and a Playwright test that triggers it.
- **Accessibility:** a polite ARIA live region for counts and countdowns, and `prefers-reduced-motion`.

**Done when:** the Playwright state tests pass, the P1 journey passes with live updates at both widths, and axe is clean.

## P2.I: Frontend quality gates
- **Visual regression:** Playwright screenshot tests for the design-system components, in light and dark themes, at 360 px and desktop.
- **Hindi:** translate the buyer-journey strings with next-intl. List anything left untranslated; don't machine-translate without a note.
- **Per-route budgets:** record the compressed first-load JS per buyer route from the production build. Set budgets at baseline + 10%, enforced in CI.
- **Lighthouse CI:** mobile, emulated 4G, on the discover, model and pool pages. Record LCP and CLS.

**Done when:** all four run in CI, and the report lists the JS sizes per route and the Lighthouse numbers.

## P2.J: Passkeys and revocation
- **Passkeys:** WebAuthn registration and sign-in alongside email OTP, tested with Playwright's virtual authenticator.
- **Revocation:** revoking a dealer staff member cuts off both API calls and open SSE streams within ≤ 30 s (AZ-T6). Measure the time.

**Done when:** the passkey E2E passes, and the measured revocation time is reported, from API and from SSE.

## P2.K: Local load tests and the P2 report
- **W3:** 1,000 virtual users joining and leaving one pool for 5 minutes. Final counts must equal the membership table and the count delivered through realtime. Record p95 and p99.
- **W5 at local scale:** aim for 2,000 SSE connections, including a reconnect storm (kill one realtime instance) and slow consumers. Record connections held, fan-out p95, recovery time, and memory per 1,000 connections. Don't claim the 10,000 target.
- Record run metadata per `load-workloads.md`.
- Write `reports/P2_REPORT.md` with the full headings from `docs/prompts/P2.md`, collecting the results of P2.A–P2.K. Open the PR.

**Done when:** the W3 counts are exact, the W5 results are recorded (whatever count is reached), the PR is open and CI is green.
