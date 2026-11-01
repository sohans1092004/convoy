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
