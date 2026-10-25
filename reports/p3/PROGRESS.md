# P3 progress

- 13:10Z P3.A started on p3/dealers-quotes (branched from p2.5/hardening; PRs #3/#4 open). Dealer registration + GSTIN, admin verification, staff invites E010/E011, ESLint.
- 13:16Z Dealer onboarding built: E050 register (GSTIN shape + mod-36 check, PENDING_VERIFICATION), E153 admin queue, E053 approve/reject (reason required, If-Match, audited, DealershipVerified with registered_by). Dealer ITs pass except API drift (regen pending). Next: identity invites E010/E011 + grant admin on DealershipVerified.
- 13:21Z Identity invites built: E010 (admin of same dealership, token hashed, expiry 72 h, one use, bound to the invited email, StaffInvited), E011 accept (404 if unknown/used/expired/other email), DealershipVerified -> registrant becomes dealer_admin. Identity + dealer ITs pass (incl. authz matrix for E010/E011/E050/E053/E153); web client regenerated. ESLint 9.39.5 + eslint-config-next 16.4.3 being added.
- 13:28Z ESLint added (9.39.5 + eslint-config-next 16.4.3, flat config, zero warnings, CI node job): 30 findings -> 9 fixed (unused imports, anonymous default export), rest suppressed per line with reasons (react-compiler rules on intentional patterns). Dealer + identity ITs and web unit pass. Running the P1 journey once (360 px).
- 13:41Z P1 journey (360 px) passed in 4.0 min (first run failed only at the Jaeger trace check: Jaeger had been OOM-killed 6 h earlier; restarted). Committed 9de7011, pushed. P3.A done within the time box.
- 13:42Z P3.B started (quote lifecycle backend). Surveying existing quote service.
- 13:51Z P3.B built: E064 revise (immutable v n+1, fresh snapshots, diff in audit), E065 withdraw until deadline, E066 admin cancel after FINAL (after_final), content checks (validity date, stock colour/date, dealer-confirmed stock evidence), quote_versions immutable by trigger; fixed a sealed leak (buyers could read SUBMITTED quotes while the round was open: now FINAL only). Tests written; running quote ITs.
- 14:00Z Quote ITs pass except drift before regen; web quote form wired to real revise/withdraw (typed client caught the old 501-era calls); Stock.evidence optional (NON_NULL). Re-running quote ITs, then the P1 journey once.
- 14:13Z P3.B DONE: P1 journey passed (3.9 min) on the 2nd run; 1st failed only at the Jaeger trace check (Jaeger OOM-killed again at 256 MB: in-memory store unbounded). Committed 964fa0e, pushed.
- 14:18Z P3.C started; first P3.B carry-overs: Jaeger max_traces, P4 list, draft PR.

## Carried to P4
- Post-FINAL cancellation effects (QUO-T06, cancellation-rules.md): on `QuoteWithdrawn{after_final: true}` order must cancel the affected orders/bookings, refund deposits, auto-open disputes, and decrement the dealer's reliability score. P3.B emits the event; nothing consumes these effects yet.

