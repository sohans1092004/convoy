# ADR-003: SSE vs WebSockets for realtime

**Status:** Accepted

## Context
Realtime traffic is server → client only: pool counts, round state, quote updates, the dealer's own rank, and order and payment status. Client → server actions are ordinary HTTP mutations with idempotency keys. Targets: 10,000 concurrent connections, plus reconnect storms and slow consumers (PLAN §3.3, §4.5).

## Decision
Server-Sent Events over HTTP/1.1 and HTTP/2, through CloudFront/ALB in the full profile or Caddy in lite. Messages carry versions, heartbeats go every 15 s, each connection has a bounded queue, and clients take a snapshot on reconnect (realtime-recovery.md).

## Alternatives considered
- **WebSockets:** bidirectional with binary frames, and widely supported. They would be needed if the client had to stream to the server, which it doesn't. Costs: custom heartbeat and reconnect logic, sticky sessions and upgrade handling through proxies, and HTTP auth and caching semantics become harder to reuse.
- **Long polling:** the simplest and very robust, but at a 10 s interval 10,000 users generate about 1,000 req/s for polling alone. Kept as the **fallback** mode.
- **A managed service (AppSync, API Gateway WebSocket, IoT Core):** offloads connection handling, but adds per-message or per-connection-minute cost and a second auth model.

## Consequences
- Browsers allow only 6 HTTP/1.1 connections per origin. We use one multiplexed stream per screen and HTTP/2 where available.
- SSE is text-only, which is fine for JSON. `EventSource` reconnects automatically, but we implement our own jittered, fetch-based reconnect so we can add auth headers through the BFF.
