# Lite: Oracle VM (India) talking to SNS/SQS/S3 in Sydney

The AWS account may use only ap-southeast-2. The lite host is an Oracle Always Free VM in your home region (India),
so every SNS/SQS/S3 call crosses India → Sydney.

**Measured** (2026-10-31, from this laptop in India, TCP connect = 1 round trip, median of 5):
`sqs/sns/s3.ap-southeast-2` **386–393 ms**, `sqs.ap-south-1` (Mumbai, for comparison) **37 ms**.
The laptop path is a residential connection, possibly through Cloudflare WARP; a datacentre-to-datacentre path from
an Oracle Mumbai/Hyderabad VM is usually 150–200 ms. Re-measure on the VM (same `curl -w '%{time_connect}'`) in P6.D.
Calls reuse kept-alive TLS connections, so the cost per call is about one round trip (R), not the TLS setup.

## What the round trip adds (R = 0.18 s datacentre estimate, 0.39 s measured here)
| Path | Calls on it | Added vs Mumbai | Added at R = 0.18 / 0.39 s |
|---|---|---|---|
| Outbox → SNS (relay polls the outbox every 200 ms, publishes in batches of 10) | 1 PublishBatch | ≈ R | 0.18 / 0.39 s |
| SNS → SQS (inside Sydney) | none from the VM | 0 | 0 |
| SQS → consumer (a 20 s long poll is already open; the message travels back) | half a round trip | ≈ R/2 | 0.09 / 0.20 s |
| DeleteMessageBatch after processing | 1, after the work is done | not on the visible path | — |
| **Event visible to a consumer** (relay wait + publish + delivery) | | ≈ 0.25 s + 1.5 R | **≈ 0.5 / 0.85 s** (≈ 0.3 s locally) |
| **Live update in the browser** (pool command → outbox → realtime consumer → Redis → SSE) | same chain | +1.5 R | **+0.3 / +0.6 s** |
| Service start-up topology check (≈ 5 calls per queue, sequential; audit consumes 7 queues) | ≈ 35 | ≈ 35 R | +6 / +14 s for audit; ≤ +5 s for most |
| Throughput per poller (receive + delete per batch of ≤ 10) | 2 per batch (2R per ≤ 10 messages) | | ≈ 28 / 13 messages/s per queue |

## Does it matter for the demo?
No:
- Demo rounds last 90 s (sealed) and 20 s (final), and decisions 60 s. Half a second more before a count, a quote
  or a status appears is within what a person reads as "live". Deadlines are judged on the database clock, never on
  message arrival.
- The demo makes a few events per minute, far below ~13 messages/s per queue even at the measured 0.39 s.
- Start-up is slower by seconds (the 56 s laptop cold start becomes roughly 60–70 s); deploys roll one service at a time anyway.

It would matter for P7: latency and capacity claims come only from the full profile, which runs in Sydney next to SQS.
Lite makes no latency claims (deployment-profiles.md). If the VM ever lands far worse than 0.4 s RTT, the fix is a
VM in a region nearer Sydney (Oracle Always Free is tied to the home region, so that means a new tenancy), not code.
