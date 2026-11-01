# How Convoy was built

Convoy was built spec-first, one phase at a time, by one developer working with an AI coding agent. Every phase ended
in a written report with its evidence; every number we quote is in [EVIDENCE.md](EVIDENCE.md).

## 1. The process

**Plan, then an external review.** [PLAN.md](../PLAN.md) went through three versions. Version 3 folded in an external
review of version 2. It froze the business rules before any code, fixed circular tier pricing, defined one canonical
pricing and savings model and the full deposit lifecycle, made the messaging contracts precise, split the AWS work by
deployment profile, made frontend quality measurable, and moved the first complete buyer journey early.

**P0: rules and spec, before code.** [SPEC.md](../SPEC.md), the state machines as YAML
([rules/state-machines](rules/state-machines/)), the price model with an executable Python reference and shared
vectors, the event catalogue, the API outline with authorisation test ids, and a validator that checks all of it
against itself ([reports/P0_REPORT.md](../reports/P0_REPORT.md)). After P0, changing a frozen rule needs an ADR or an
`AMENDMENT-###` in SPEC.md, so every change of mind is written down with its reason. There were 13 amendments, for
example a second admin for every manual money action (001), deposits kept by buyer choice on a re-round (002), and
dedupe-only notification subscriptions (010).

**Phases.** P1 walking skeleton (the whole buyer journey, thin), P2 pools/realtime/passkeys/load, P2.5 hardening, P3
dealers and quotes, P4 money and orders, P5 operations (notifications, audit, privacy), P6 deployment, P7 evidence,
P8 release. Each phase was one branch and one PR, merged only with CI green.

**Subtasks.** Each phase was split into subtasks with acceptance checks ([prompts/](prompts/)). The working rules:
build first, run targeted checks, at most two attempts at a failing fix before stopping to ask, a time box, a short
report (done / checks / problems / needs) after each subtask, and a progress log per phase (`reports/p*/PROGRESS.md`).
Anything that costs money, touches the live server or exposes a secret waits for the owner's explicit "apply".

## 2. Honest lessons

**Checks that test nothing.** Two audits found green checks that could not fail. In P5, six were found and fixed,
for example a ledger "balanced" check that was true on an empty ledger, and a journey check whose `try/catch` swallowed
its own failed `expect`. In P8, all 24 Storybook page-state stories turned out to render their error state, because
the API client captured `fetch` before the story's mock replaced it; the spec still passed because it only looked for
an error display that Storybook 10 no longer shows. The lesson we now apply: break the code on purpose and watch the
check fail before trusting it.

**Version reuse.** Consumers drop events whose aggregate version is not newer than the last one applied. A P5 guard
(`unique (aggregate_id, aggregate_version)` on every outbox) found the quote service reusing quote versions for
`RankChanged` and six double-version writes in order: events a consumer would have silently dropped. The same rule was
wrong for notification and audit, where every event is its own message: with several pollers a backlog replays out of
order and the stale rule dropped real messages, so those subscriptions became dedupe-only (AMENDMENT-010, -012).

**Testing the production configuration in the cloud before testing it locally.** The first full-profile smoke runs on
AWS found production-config bugs one per run (each run costs money and time): Flyway without a password on the direct
RDS URL, a service's own URL hard-coded to `localhost`, a database owner that needed role membership on RDS. A local
"prod rehearsal" (the prod profile, a non-superuser database, the real AWS shared resources) then found five more without
spending anything, including a blank endpoint override that broke every service and an ETag rewritten by gzip that made
every versioned command fail with 428. It should have come first.

**The parked full profile.** The full AWS profile applies end to end and all 16 services once reached a stable state,
but service-to-service names through ECS Service Connect kept failing to resolve, and the journey never passed there.
After the last approved attempt it was parked with its next debugging steps written down
([reports/P6_REPORT.md](../reports/P6_REPORT.md) §5). Capacity targets on AWS are therefore unmeasured, and the README
says so.

**The Oracle capacity wait.** The plan put the demo on an Oracle Always Free VM, but the region has had no A1
capacity since the network was created. A scheduled task asks Oracle's capacity report every two minutes and applies
the single-instance plan only when capacity appears; meanwhile the demo runs on an interim AWS host. Waiting is free;
giving up the always-free host would not be.

**Smaller lessons.** A deploy workflow that silently could never deploy (its secrets were never set); a Terraform plan
that wanted to replace the live server over one drifted attribute (now `prevent_destroy`); a laptop IP change that
locked out SSH (now in the runbook); a page-speed "regression" that an A/B on one machine showed to be noise, which is
why EVIDENCE.md keeps all three live runs.
