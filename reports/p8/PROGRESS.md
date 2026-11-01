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
