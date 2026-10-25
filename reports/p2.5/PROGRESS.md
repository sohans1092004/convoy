# P2.5 progress

- 10:12Z P2.5.A started on p2.5/hardening (branched from p2/pools-realtime; PR #3 still open). Plan: AMENDMENT-008 doc, Q-045 rejoin, Q-043 admin passkeys, Q-044 session kill + event eviction, AZ-T6 x3.
- 10:17Z AMENDMENT-008 recorded in SPEC.md. Q-045 done in code: rejoin as MEM-T01 on the LEFT row, leave_count (V4), 409 rejoin-limit-reached after 2 leaves; spec validator 14/14, pool ITs pass (new rejoin test). W3 k6 now does join/leave/rejoin cycles.
- 10:17Z Next: Q-043 (platform_admin only via passkey sessions), then Q-044 (revocation deletes sessions + event eviction).
- 11:53Z Q-043 done: sessions.auth_method (V3); platform_admin only on passkey sessions; admin-recovery runbook; auth-contract updated. Q-044 code done: revoke deletes sessions + SessionRevoked per session; realtime consumes it and relays on Redis "sessions:revoked"; gateway drops sess:<hash>. identity/realtime/gateway/pool ITs pass.
- 11:53Z Next: rebuild containers, AZ-T6 x3, W3 smoke with rejoin.
- 12:02Z AZ-T6 x3 after AMENDMENT-008: API refused 2.03/2.02/1.01 s (401, 0 successes after; 1 s poll resolution), stream closed 0.63/1.40/0.95 s. W3 smoke with rejoin: counts exact (22), 19 rejoins refused as expected. Committing; CI next.
- 12:28Z CI on PR #4 failed: payment MF-2 (test waited for exactly +1 duplicate; SQS at-least-once can give +2) and realtime "redis back" (fixed 4 s sleep vs 4.9 s resubscription). Fixed both tests (>= 1 duplicate; wait for psub connections); revocation channel renamed agg:sessions:revoked. Local: payment pass, scenario pass. Re-running CI.
- 12:41Z P2.5.A DONE: CI green on PR #4 (run 40193292904, commit ce861a0, all jobs incl. e2e).
