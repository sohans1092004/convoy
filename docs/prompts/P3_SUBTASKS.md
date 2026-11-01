# Convoy Phase 3: dealers and quotes (6 small subtasks)

Start a **fresh agent session for each subtask**. Paste the working-style block, then one subtask, and send me the short report.

## Working style (paste above every subtask)

```
You are working on Convoy Phase 3 (dealers and quotes), one subtask at a time. Read PLAN.md, SPEC.md
(+ amendments), reports/P2_REPORT.md and reports/p2.5/PROGRESS.md first. Ground rules: no AWS, no paid
services, integer paise, DB clock decides deadlines, no invented data, secrets only in .env, stop and
ask before changing a frozen rule. Branch: p3/dealers-quotes.

Working style:
- Build first. While building, run only unit tests and the integration tests of the service you
  changed. Keep the Docker stack running; don't restart everything for each change.
- Run the P1 browser journey ONCE at the end of the subtask.
- No load tests, Lighthouse, visual-baseline updates or repeated-flake runs (nightly/P7 handles them).
- When a test fails: read the error, make one targeted fix. If the same test still fails after 2
  attempts, stop and report it. Never revert working code or loosen a test to get green.
- Time box: 60 minutes. If you pass it, commit what works and report.
- Append 2-3 lines to reports/p3/PROGRESS.md every ~20 minutes and print them.

Report (max 15 lines): DONE / TESTS RUN / PROBLEMS / NEEDS FROM ME.
```

---

## P3.A: Dealer onboarding, verification, staff invites (+ ESLint)
- **Dealer registration:** dealership name, city, brands, and a GSTIN with a format and checksum check. New dealerships start as `PENDING_VERIFICATION`.
- **Admin verification queue (passkey admin only):** approve or reject with a reason. Both actions are audited, and only verified dealerships can be invited to rounds.
- **Staff invites:** E010/E011, with an invite link that expires and can be used once, and a role of staff or admin. Revoke already exists.
- **ESLint:** add it to `web` (a free dev dependency) as a required CI check. Fix or suppress existing issues with comments; don't reformat the whole codebase.

**Tests:** identity and dealer integration tests, plus authz tests for the new endpoints.

## P3.B: Quote lifecycle (backend)
- **Quote machine:** follow the YAML (`DRAFT → SUBMITTED → REVISED* → FINAL → EXPIRED | WITHDRAWN | VOID`), and make every invalid transition return its specified error.
- **Content:** a quote names **exact variants** and has line items marked mandatory or optional, tiers, minimum fulfilment, an optional `max_units`, a validity date and stock claims (variant, colour, quantity, availability date, dealer-confirmed). The insurance figure counts for ranking only if it's binding and baseline (AMENDMENT-004).
- **Revisions are immutable versions.** Store a diff for each revision in the audit log, and give buyers a "last updated" time.
- **Withdraw** is allowed until the round deadline; after FINAL, a withdrawal counts as a dealer cancellation (per the cancellation rules).
- **Sealed access:** no other dealer, and no buyer before the round closes, can read a quote's contents.

**Tests:** transition tables for the quote machine, sealed-access authz tests, and price snapshots stored with every submitted version.

## P3.C: Rounds, ranking and deadlines
- **Rounds:** a sealed round followed by one final round, with the timing from config (demo durations stay short).
- **Ranking per `ranking-rule.md`:** rank at the ceiling price, per variant, with the published tie-breaks. A dealer sees only **its own rank plus `of_n`**, never competitors' values.
- **Deadline rule:** a small, targeted test fires 50 parallel submissions around the deadline. Every submission committed before the deadline is kept, and every one committed after is rejected with `deadline-passed`. No load tool is needed.
- **Dealer stream E121** (rank and round updates) through realtime, with per-resource authorisation.

**Tests:** ranking unit tests (ties, single dealer, insurance-binding case), the deadline test, a realtime authz test for E121.

## P3.D: Dealer quote builder UI (the second flagship screen)
- **Live summary:** a persistent summary computed with the TypeScript price engine (preview only; stored prices come from the server).
- **Inputs:** validation of missing or inconsistent items, a clear mandatory/optional split, and a baseline insurance toggle with a binding checkbox that explains ranking.
- **Tier editor:** with a "what if 3 / 7 / 12 buyers confirm" preview, and the "price can only fall" rule shown.
- **Submission:** a confirm dialog, then an immutable submitted view. Revisions show a diff.
- **Round info:** validity and deadline countdowns, and the rank badge with the published ranking rule beside it.
- **Accessibility:** keyboard-navigable dense tables. Stories for each state, with no new visual baselines (nightly will add them).

**Tests:** component tests for the builder logic, and one Playwright run of the dealer flow.

## P3.E: Buyer compare upgrades
- **360 px price breakdown:** stacked cards with every column visible and no horizontal scroll (PLAN §5.1).
- **Per-line evidence labels:** dealer-confirmed, catalogue estimate or unverified.
- **Offer details:** the tier ladder with "price can only fall", the ceiling price plus the labelled projection (AMENDMENT-005), stock confidence and "last updated".
- **Buyer stream E122:** quote-count updates during the round (never contents), and the full comparison once the round closes.
- **Strings:** move the 78 hard-coded strings into next-intl, with Hindi marked as a draft.

**Tests:** component tests, and a check that no quote content reaches a buyer before close (API and stream).

## P3.F: Dealer simulator, multi-dealer journey, P3 report
- **Simulator:** a seedable, deterministic dealer simulator with documented strategies:
  - aggressive
  - conservative
  - slow responder
  - withdraws before the deadline
  - misprices an add-on (flagged)

  It has a stock-limited inventory and a margin floor, and it uses the real dealer APIs, never internal shortcuts.
- **Journey:** extend the P1 journey to 3 dealers (1 human-driven by Playwright, 2 simulated). The buyer compares 3 offers, and ranking and tiers behave as specified.
- **Report:** write `reports/P3_REPORT.md` (a summary of A–F, deviations, bugs, gaps for P4) and open the PR.
