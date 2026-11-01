# Postmortems and drill write-ups

Every failure we wrote up, and every failure test or drill with a result. They live next to the phase that produced them;
this page only links them. Measured numbers are summarised in [EVIDENCE.md](../EVIDENCE.md).

## Incidents and failed runs

| Date | What failed | Write-up |
|---|---|---|
| 2026-10-31 | Full-profile smoke1: CloudFront refused (account not verified); abort path and destroy inputs broken | [P6_REPORT.md §5](../../reports/P6_REPORT.md#5-the-full-profile-smoke-run-with-its-cost) |
| 2026-10-31 | Reaper canaries 1 and 2: IAM actions without tag scoping, a stale state lock; reaper fix applied but unproven | [P6_REPORT.md §5](../../reports/P6_REPORT.md#5-the-full-profile-smoke-run-with-its-cost) |
| 2026-10-31 | smoke2: db-init needed role membership on RDS; every Java service then exited at startup, logs lost with the stack | [P6_REPORT.md §5](../../reports/P6_REPORT.md#5-the-full-profile-smoke-run-with-its-cost) |
| 2026-11-01 | smoke3–smoke5: Flyway credentials, a service URL hard-coded to localhost, an SCP denying the reaper schedule | [P6_REPORT.md §5](../../reports/P6_REPORT.md#5-the-full-profile-smoke-run-with-its-cost) |
| 2026-11-01 | smoke6: laptop lost its connection mid-apply; state recovered, teardown verified after several resumes | [P6_REPORT.md §5](../../reports/P6_REPORT.md#5-the-full-profile-smoke-run-with-its-cost) |
| 2026-11-01 | smoke7–smoke9: ECS Service Connect names unresolvable; full profile parked, with next steps | [P6_REPORT.md §5](../../reports/P6_REPORT.md#full-profile-parked-2026-11-01-your-decision-after-smoke9) |
| 2026-11-01 | Local prod rehearsal: missing SNS permission, gzip ETag making versioned commands fail with 428 | [P6_REPORT.md §5](../../reports/P6_REPORT.md#5-the-full-profile-smoke-run-with-its-cost) |
| 2026-11-01 | Lite host first deploy: iptables assumption, smoke check before the first certificate, placeholder age key | [P6_REPORT.md §4](../../reports/P6_REPORT.md#4-the-lite-host-live-interim-ec2-2026-11-01-vm-numbers-not-laptop-numbers) |
| 2026-11-01 | deploy-lite never deployed (secrets never set); lite-ec2 plan wanted to replace the live host; SSH lockout after an IP change | [p8/PROGRESS.md](../../reports/p8/PROGRESS.md) (P8.C) |
| 2026-11-02 | Vacuous Storybook page-state stories (all 24 rendered their error state and passed) | [p8/PROGRESS.md](../../reports/p8/PROGRESS.md) (P8.C) |
| 2026-11-01 | Live page-speed "regression": two bad Lighthouse runs, ruled out as a code change by an A/B | [p8/lighthouse-ab-local.md](../../reports/p8/lighthouse-ab-local.md) |

Bugs found and fixed inside each phase: [P1 §6](../../reports/P1_REPORT.md#6-bugs-found-and-fixed),
[P2 §6](../../reports/P2_REPORT.md#6-bugs-found-and-fixed), [P3 §5](../../reports/P3_REPORT.md#5-bugs-found-and-fixed),
[P4 §5](../../reports/P4_REPORT.md#5-bugs-found), [P5 §5](../../reports/P5_REPORT.md#5-bugs-found).

## Failure tests and drills with results

| Drill | Where it ran | Write-up |
|---|---|---|
| Messaging failures MF-1..MF-6 (crash before publish, duplicates, crash before ack, out of order, poison message, offline consumer) | laptop (LocalStack) + CI | [p2/messaging-failure-results.md](../../reports/p2/messaging-failure-results.md) |
| Realtime instance killed under 1,000 streams (W5 storm) | laptop | [P2_REPORT.md §5](../../reports/P2_REPORT.md#5-measurements), [p2/w5.json](../../reports/p2/w5.json) |
| Session revocation (AZ-T6) | laptop | [p2.5/revocation.json](../../reports/p2.5/revocation.json) |
| Saga crash at each of the 4 boundaries | laptop + CI | [P4_REPORT.md §4](../../reports/P4_REPORT.md#4-test-results-including-the-vectors-executed) |
| Notification down: buyers unaffected, backlog delivered once | laptop | `tools/notification/isolation.sh`, [P5_REPORT.md](../../reports/P5_REPORT.md) |
| Lite host reboot and backup → restore drill | live lite host | [P6_REPORT.md §4](../../reports/P6_REPORT.md#4-the-lite-host-live-interim-ec2-2026-11-01-vm-numbers-not-laptop-numbers) |
| Real Razorpay test-mode capture (refund blocked by the account) | Razorpay test mode | [p2/razorpay-real-run.md](../../reports/p2/razorpay-real-run.md) |

**Not run:** the AWS drills of P7.C (kill a service with 2 tasks each, RDS Multi-AZ failover; `scripts/p7-drill.sh`)
need a working full profile, which is parked. No write-ups exist for them.
