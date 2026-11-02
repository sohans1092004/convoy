# U.1 progress: P0 fixes on the live demo, and deploys through Systems Manager

All live results are on the public demo, `https://convoy-main.duckdns.org`, at commit `6267fda`, on 2026-11-02.

## P0 fixes (PR #13)
Estimates by variant on the live API, Mumbai / Pune / Delhi / Bengaluru:

| Model | Result | Listed in discover |
|---|---|---|
| Creta | 5/5 in all four | Yes |
| Hector | 4/4 in all four | Yes |
| Dzire | 4/4, 4/4, 3/4, 4/4 | Yes |
| Ertiga | 1/4, 1/4, 0/4, 4/4 | Yes, except Delhi |
| Swift | 4/4, 4/4, 3/4, 4/4 | Yes |
| Brezza | 3/3 in all four | Yes |
| Windsor EV | 0/4 in all four, by rule | No |

Owner decisions: no hybrid-as-petrol until a source exists; keep Hector's 1451 cc with its note; Windsor EV stays hidden.

Not proven live: the simulated dealers price one variant each, so the compare page's variant picker has never appeared on the live site. Unit tests cover how it renders; no test accepts a second variant through the real API. The failed name lookup message has not been triggered live.

## Systems Manager migration
| Step | Result |
|---|---|
| IAM role and instance profile (`AmazonSSMManagedInstanceCore` only) | Applied: 3 added, 1 changed, 0 destroyed. Site stayed up. |
| Agent registration | Agent 3.3.4793.0 was already on the host. It registered as `Online` only after `snap restart amazon-ssm-agent`, about 8 minutes after the profile was attached. |
| `convoy-deploy` document | Version 2 is active. |
| Deploy through SSM | 21 s, no rollback, containers left running. Same commit as already live, so no image changed. |
| Journey after that deploy | PASS once, 4.4 min. |
| `restore-check.sh` through SSM | Newest backup restored, 125 tables and 3,252 rows; 62 tables differ, every one with more rows live than in the backup; 10 restore errors, all role-grant `permission denied`, as in P6. |
| Port 22 closed | Applied: 0 added, 1 changed, 0 destroyed. The security group now lists only 80 and 443. Connections to 22 from the laptop time out; 80 and 443 connect. |
| Journey with port 22 closed | PASS once, 4.4 min. |

The laptop's IP had already left the allowed rule before port 22 closed, so the timeout alone would not prove it. The security group listing is the proof. No scan was run from a second machine.

## What went wrong on the way
Each failed before anything on the host changed.
1. The first document was rejected: `allowedPattern is not a valid regex: invalid repeat count: {1,8192}`. AWS's regex engine does not take a repeat that large, and Terraform's validation does not check document content. The limit moved to `maxChars`.
2. The first deploy failed on its first line: SSM runs the document with `sh`, which is `dash` on Ubuntu and has no `pipefail`. Fixed with `set -eu`, then checked under Ubuntu 24.04's `sh` in Docker.
3. `OpenAPI` documents I patched by hand failed CI's exact-match test in PR #13. They were replaced with what the services serve.

## Still to do
- The deploy key is still in `/home/deploy/.ssh/authorized_keys` on the host, though no port reaches it.
- The first deploy that pulls new images through SSM happens with the next release.
- The Session Manager plugin is not installed on the laptop, so `aws ssm start-session` has not been used.
