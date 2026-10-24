# ADR-006: BFF session + gateway-minted internal JWT

**Status:** Accepted (auth-contract.md)

## Context
Browser auth must resist XSS token theft and CSRF. Services must authenticate callers without each one calling identity. Staff revocation must take effect within 30 s. Services must authorise at the resource level.

## Decision
1. The Next.js BFF holds an opaque session id in an HttpOnly, Secure, SameSite=Lax cookie.
2. The BFF forwards that id to the gateway in a header, which it strips from any browser input.
3. The gateway validates the session with identity, caching the result for at most 30 s and evicting it on revocation events.
4. The gateway then mints a short-lived (2 min) ES256 JWT with a single audience: the target service.
5. Services accept only that JWT, reject identity headers, and enforce resource-level authorisation themselves.
6. Service-to-service calls use client-credentials service tokens with an audience and scopes.

## Alternatives considered
- **Browser-held JWT (access + refresh tokens):** stateless and common. But revocation needs short lifetimes plus deny-lists, and tokens kept in JS-accessible storage can be read by injected script.
- **Every service validates the session with identity:** the simplest revocation (instant), but identity becomes a hot dependency on every call.
- **mTLS service-mesh identities only:** strong service identity, but they do not carry end-user identity or roles.
- **A third-party IdP (Cognito, Auth0):** less code, but per-MAU cost and less control over the OTP + passkey UX and data residency.

## Consequences
- Revocation latency is bounded by the cache TTL (≤ 30 s), measured by AZ-T6.
- The gateway is a security-critical component; in the full profile its signing key lives in KMS.
