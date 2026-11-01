# Convoy Phase 2.5: hardening before P3 (small subtasks)

Merge PR #3 first. Then paste the **rules block** plus **one subtask** at a time, and send me each short report.

## Rules for every subtask (paste above each one)

```
You are working on Convoy Phase 2.5 (hardening), one subtask at a time. Context: PLAN.md, SPEC.md
(+ amendments), reports/P2_REPORT.md, reports/p2/*. Same ground rules as before: no AWS, no paid
services, integer paise, DB clock decides deadlines, no invented data, secrets only in .env,
stop and ask before changing a frozen rule.

Branch: p2.5/hardening. Commit when the subtask is done and CI is green. Do ONLY this subtask.
Every ~20 minutes, append 2-3 lines to reports/p2.5/PROGRESS.md and print them to me.

All load numbers are from this laptop: say so, record run metadata (commit, limits, duration),
and never present them as production capacity.

When done, print a short report (max ~25 lines):
- DONE / EVIDENCE (tests, numbers, commit, CI) / DEVIATIONS / PROBLEMS / NEEDS FROM ME
```

---

## P2.5.A: Apply the three decisions (AMENDMENT-008)
- **Q-045 (rejoin after leaving):** allowed while the pool is FORMING or before the sealed-round deadline, **at most 2 leaves per buyer per pool**. Each rejoin needs a new commitment; the earlier deposit has already been refunded. The limit stops count-gaming. Update the membership machine, vectors and W3's script.
- **Q-043 (passkeys and attestation):**
  - Passkeys are optional for buyers and dealer staff, **required for platform admins**.
  - Use attestation conveyance `none`, which is standard and privacy-friendly for consumer apps.
  - Email OTP stays as recovery, except for admins, whose recovery is documented separately.
- **Q-044 (revocation):** revoking staff **ends all their sessions immediately**. identity deletes the sessions and emits `StaffRevoked`. The gateway and realtime evict cached sessions **on the event**, with the 25 s cache as a fallback. Re-measure AZ-T6 three times; the expected result is about 1–2 s for both API and stream.

**Done when:** the amendment is recorded, tests pass, and the new revocation times are reported.

## P2.5.B: Fail fast, never queue for 40 s
W3 showed requests waiting up to 52 s. Under overload the system must **refuse quickly**, not queue.
1. **Find where the time goes.** Use Jaeger traces of slow joins to break latency into gateway, identity, pool transaction (lock wait vs work) and outbox. Confirm the join transaction makes **no remote calls while holding the pool row lock**; fix it if it does. Report the breakdown before and after.
2. **Load shedding:**
   - per-route concurrency limits or bulkheads at the gateway and in pool, so excess work gets an immediate `503` with `Retry-After`
   - the gateway maps upstream connection drops to `503`, not `500`
   - connection pools sized deliberately, and documented
3. **Cold start:** add readiness gating or warm-up so a restarted gateway or service doesn't receive a burst before it's ready.
4. **For P6:** record the database connection budget (services × tasks × pool size vs Postgres `max_connections`) and the plan for it: PgBouncer on the lite profile; RDS Proxy or PgBouncer on full. Don't build it now.

**Done when:** under the same W3 load, rejected requests return in < 1 s with 503/429 (never a 40 s wait), and the latency breakdown is in the report.

## P2.5.C: Hot-pool capacity, measured properly
1. **Capacity curve:** run W3 with k6 `ramping-arrival-rate` on **one pool** (for example 10 → 400 joins/s). Find the highest rate with **< 1% errors and join p95 < 300 ms**. That rate is the single-pool capacity on this laptop. Counts must stay exact throughout.
2. **Realistic mix (W3-R):** 200 pools of 20–60 buyers with the same total load. This is the shape the product actually expects, so report both.
3. If single-pool capacity is below **50 joins/s**, improve it. Options: shorter lock hold, batching count updates, or keeping the count in a separate counter row. Compare before and after. Exact counts are never traded away.

**Done when:** the capacity curve, the single-pool knee and the W3-R results are reported, with exact counts in every run.

## P2.5.D: Realtime efficiency and stability
1. **Memory per stream:** measure it, and cut it where possible (bounded queue size, response buffers, thread model: confirm virtual threads or switch to a non-blocking model if the numbers justify it). Report KB per stream before and after.
2. **Admission control:** each instance accepts streams only up to a configured limit derived from its measured heap. Beyond that it returns `503` with `Retry-After`, and clients back off with jitter. **No OutOfMemoryError under any load test.**
3. **Coalescing:** for count updates, when a pool's backlog builds, send only the latest version (latest wins). Versions make skipping intermediate counts safe.
4. **Faster recovery after an instance dies:** shorten the SQS visibility timeout and extend it with a heartbeat while a message is processing, so a killed instance's in-flight messages come back in seconds.
5. Re-run W5 at 1,000 streams, then at **2,000** (stop unneeded containers if memory is tight), including the kill storm. Report delivery p95 before and after the storm.

**Done when:** no OOM in any run, rejections are clean 503s, post-storm delivery p95 is < 2 s, and the memory per stream is reported.

## P2.5.E: Flake, mobile layout, i18n, bundle size
1. **MF-2 flake:** find the root cause of the slow LocalStack duplicate delivery. Fix it properly, or document the cause and make the test deterministic, without simply raising the timeout. The test must pass 20 runs in a row in CI.
2. **Price breakdown on 360 px:** stacked cards with every column visible, no horizontal scrolling (PLAN §5.1: "cards on mobile, never a squeezed table"). Update the visual baselines.
3. **i18n:** move the 78 hard-coded strings into next-intl, in English and Hindi drafts. Hindi stays marked as machine-drafted until I review it.
4. **Bundle size:** run a bundle analysis to find what puts about 185 KB on every route. Move work into Server Components, keep zod and the generated client out of client bundles where possible, and lazy-load heavy widgets. Target buyer routes ≤ 170 KB and **Lighthouse LCP < 2.5 s** on discover, model and pool. Report before and after; if the target isn't reached, explain what's left.

**Done when:** MF-2 passes 20/20, the mobile breakdown is fixed, the string count is 0, and the bundle and Lighthouse numbers are reported. Then write `reports/P2.5_REPORT.md` (a summary of A–E) and open the PR.

---

**Your actions, in parallel:**
- **Razorpay refunds:** check the test account settings for refunds, or ask Razorpay support why refunds are disabled. If they become available, ask the agent to rerun just the refund half of P2.A.
- **Hindi review:** read `web/messages/hi.json`, especially the deposit, refund and cancellation wording, or ask someone who reads Hindi to.
