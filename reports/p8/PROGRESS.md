# P8 — Release: progress

## P8.0 Repository safety — done (2026-11-02)

- **Secret scan, full history:** gitleaks v8.21.2 (Docker) over a mirror of every local and GitHub ref
  (32 refs, 188 commits; 173 non-merge commits scanned). 2 findings, both test fixtures, no real secret:
  `DemoIT.java:27`, `IdentityIT.java:30` (`identity.data-key` = base64 of "0123…901", `gw-secret` etc.).
  Extra history greps: private-key headers 0, AKIA/ASIA 0, OCI ocid/fingerprint 0, DuckDNS token 0,
  Razorpay 2 (fake `rzp_test_` id in `tools/razorpay-fake/contract.test.mjs`, not the real key).
  No fixture value matches the laptop `.env` or the lite host `convoy.env`.
- **.gitignore:** added `*.pem`, `*.key`, `*.p12`, `.env.*` (keeps `.env.example`), `*.tsbuildinfo`.
  Every Terraform state/plan/tfvars, `.run/`, `deploy/lite/convoy.env` and `.env` in the working copy is
  ignored (checked with `git check-ignore`); no tracked file matches an ignore rule.
- **Stray files:** untracked `web/tsconfig.tsbuildinfo` (build cache); removed empty, unused
  `deploy/lite/duckdns.env`; P2–P6 subtask prompts moved to `docs/prompts/`; empty `c/Users/...` dir tree
  (local only) deleted. The OCI API key moved out of the repo to `~/.oci/` (by the user).
- **LICENSE:** MIT.
- **Left as is:** `deploy/lite/measure.env` holds a literal `CLIENT_SECRET_DEMO_BOTS` for the laptop
  measurement stack (LocalStack); it differs from the lite host's value.
- **measure.env:** the literal `CLIENT_SECRET_DEMO_BOTS` replaced: `dev-env.sh` generates it into `.env`; only
  `deploy/lite/measure.env.example` is tracked, `lite-measure.sh` copies it (compose config resolves; checked).
- **OCI retry task** restarted on the user's apply, guards unchanged; launched through `conhost.exe --headless` (the
  visible console had been closed twice, exit 0xC000013A). First check logged 2026-11-01 18:54:48Z: OUT_OF_HOST_CAPACITY.

## P8.A Evidence inventory — done (2026-11-02)

- `docs/EVIDENCE.md`: every quotable number with Where / Date / Commit / Source, and the missed or unmeasured targets.
- New measurement: Lighthouse on the live site (`DEMO=1 ... web/scripts/lighthouse.mjs`, reports/p8/lighthouse-live.json):
  LCP 1,446 / 1,133 / 2,065 ms, CLS 0, TBT 194 / 218 / 236 ms (discover / model / pool). TBT gate 200 ms missed on
  model and pool. Site runs 03292c4 (without the P7.D bundle cut). One demo scenario created for the pool page.

## P8.C list (queued, not started)
- Show the deployed commit: `/version` returning the short SHA, plus a small footer line.
- Redeploy the lite host from `main`.
- Re-run live Lighthouse afterwards (`DEMO=1 ... web/scripts/lighthouse.mjs`) and update docs/EVIDENCE.md §5 for the current build.
- Fix: a non-member on a pool page loops on "Reconnecting…" (stream 404). Non-members must not open the stream; 401/403/404 stop retrying.

## P8.B README and architecture docs — done (2026-11-02)
- README.md rewritten: problem/product, live demo + "Try the demo" steps, 6 live screenshots (docs/media, 360 px and
  desktop, 2026-11-02), architecture diagram (docs/architecture/overview.mmd → overview.svg via
  `web/scripts/render-mermaid.mjs`), stack, decisions → ADRs, highlights → EVIDENCE.md, one-command local start
  (`scripts/up.sh`), scope and limits. Previous run/test notes kept under Development.
- docs/ARCHITECTURE.md: services and ownership, outbox/inbox, saga, state machines, deadline rule, price engine and tiers,
  auth, realtime recovery, deployment profiles; links out for detail. Measured numbers only in EVIDENCE.md.
- EVIDENCE.md §6: AWS bill as of 2026-11-01 (owner's Billing figures): gross $3.18 account, ≈ $2.33 Convoy, $0 billed.
- Found: a non-member on a pool page sees "Reconnecting…" forever (the members-only stream answers 404 and the client
  keeps retrying). Realtime itself works (200 and "Live" after joining). Not fixed (no new features in P8).
- Screenshots and the stream probe created 3 demo scenarios on the live host (expire in 24 h).
- `scripts/up.sh` chains the four documented commands; syntax-checked, not run end to end here.

## P8.C Polish and redeploy — in progress (2026-11-02)
- CI was not stuck: every push triggered ci/spec/eol and they passed; "pending" was read seconds after each push. Repo is
  public (Actions minutes not limited).
- "Reconnecting…" fixed: non-members never open the pool stream ("Join to see live updates"); a stream that fails before
  opening is probed once and 401/403/404 stop the retries (polling continues). Tests: `streamRefused` unit tests; Storybook
  pool-empty (non-member) checked directly by Playwright (no EventSource, hint shown); both fail with the fix reverted.
- **Found: the 24 page-state stories were vacuous.** `lib/api.ts` captured `fetch` at module load, so the Storybook mock never
  reached the client and every story rendered its error state; the spec only looked for an error display Storybook 10 no
  longer shows. Fixed (fetch looked up per call) and the spec now fails on console errors; 52/52 pass on the real states.
- `/version` (`{sha, built_at}`) and a footer line "Build <sha> · <time> UTC"; the images workflow bakes both into web.
- Deposit wording: the REFUND_DONE email/in-app text (also sent when a deposit is released after delivery) said "Your refund
  is complete"; now "Your deposit has been returned". Checked in Mailpit after the lifecycle journey.
- 360 px: the journey's per-screen check (catalogue, model, pool, dealer inbox, quote form, compare, orders, confirm, voucher)
  now fails on horizontal scroll and on prices < 12 px or clipped; proved by injecting a wide element and a clipped price.
- `scripts/up.sh` run end to end: exit 0 in 225 s, stack ready 54 s after compose up (laptop). First attempt failed: a second
  checkout reuses the `convoy` Postgres volume, whose passwords belong to the first `.env`; README notes it.
- Lifecycle journey at 360 px (laptop): PASS in 5.0 min, audit chain intact (183 rows).
- PR #10 merged (2278af4) after CI green (31 pass). The automatic `deploy-lite` runs after the #8/#9 merges had failed:
  the GitHub `lite` secrets were never set. Owner's decision: deploys stay on the laptop; port 22 never opened to GitHub.
  `deploy-lite` is now manual-only and refuses with a pointer to the runbook; `scripts/deploy-lite.sh <sha>` deploys.
- SSH timed out: the laptop's public IP had changed. The untargeted lite-ec2 plan wanted to **replace the instance**
  (drift: `associate_public_ip_address` true on the host, false in code); not applied. Targeted plan
  (`-target=aws_security_group.host`: 0 add, 1 change, 0 destroy) applied on the owner's "apply". Drift left as is.
  The lite-ec2 lock file lacked linux hashes (init failed in the Docker shim); added. Runbook documents both.
- Deploy of 2278af4 with the owner-confirmed deploy key: 268 s, no rollback; live `/version`
  `{"sha":"2278af4","built_at":"2026-11-01T19:56:00Z"}`, footer "Build 2278af4".
- Public demo journey on the live site after the redeploy: PASS, 4.5 min (mobile-360).
- Live Lighthouse on 2278af4: TBT 577 / 603 / 535 ms, performance 80 / 83 / 81 (laptop idle); a run 4 min earlier with the
  local stack running gave TBT 630 / 479 / 534. TBT and performance gates missed on all three pages; LCP and CLS met.
  On 03292c4 the same script gave TBT 194 / 218 / 236. Cause not established; not investigated (time box).
- The local compose stack is stopped (containers kept).
