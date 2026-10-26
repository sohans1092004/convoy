# API outline (P1–P4 scope, plus P5 endpoints marked)

External paths are as seen by the gateway (the BFF calls `/api` + path). `/internal/**` is reachable only with a **service token** (client credentials from identity, per-service audience and scope) and never routed from the BFF. `/webhooks/razorpay` is routed without a session and authenticated by HMAC signature.

Conventions (apply to every row, not repeated):
- Errors are RFC 9457 `application/problem+json`, types from [problem-types.yaml](problem-types.yaml). Every endpoint may return `unauthenticated` (401), `forbidden` (403), `rate-limited` (429), `validation-failed` (400) and `dependency-unavailable` (503); the **Errors** column lists the endpoint-specific ones.
- **Idem** = `Idempotency-Key` header required (`idempotency-key-missing` 428 if absent; `idempotency-key-reused` 422 on body mismatch). **If-Match** = aggregate version required (`precondition-required` 428; `version-conflict` 409).
- Resources not visible to the caller return `not-found` (404), never 403 (no existence leaks).
- Every response includes `Server-Time`; aggregate reads include `ETag: "<version>"`.
- Roles: `anonymous`, `buyer`, `dealer_staff`, `dealer_admin`, `platform_admin`, `service:<caller>`, `razorpay` (see [auth-contract.md](../architecture/auth-contract.md)). `authenticated` = any human role.
- **Authz tests**: `AZ-<id>-A` allowed roles succeed on their own resource; `AZ-<id>-D` every other role is rejected (401/403) and caller-supplied identity headers are ignored; `AZ-<id>-X` an allowed role is denied (404) on another tenant's resource. Named tests (`AZ-T1`…`AZ-T7`, `AZ-RNK-*`, …) are defined in [authz-matrix.md](../testing/authz-matrix.md).

| ID | Service | Method | Path | Roles | Idem | If-Match | Errors | Phase | Authz tests |
|---|---|---|---|---|---|---|---|---|---|
| E001 | identity | POST | /auth/otp/start | anonymous | no | no | rate-limited | P1 | AZ-E001-A |
| E002 | identity | POST | /auth/otp/verify | anonymous | no | no | unauthenticated, rate-limited | P1 | AZ-E002-A |
| E003 | identity | POST | /auth/passkeys/register/options | authenticated | no | no | - | P1 | AZ-E003-A, AZ-E003-D |
| E004 | identity | POST | /auth/passkeys/register | authenticated | no | no | validation-failed | P1 | AZ-E004-A, AZ-E004-D |
| E005 | identity | POST | /auth/passkeys/authenticate | anonymous | no | no | unauthenticated | P1 | AZ-E005-A |
| E151 | identity | POST | /auth/passkeys/authenticate/options | anonymous | no | no | - | P2 | AZ-E151-A |
| E152 | identity | POST | /internal/sessions/check | service:realtime | no | no | - | P2 | AZ-E152-A, AZ-E152-D, AZ-T6 |
| E006 | identity | POST | /auth/logout | authenticated | no | no | - | P1 | AZ-E006-A, AZ-E006-D |
| E007 | identity | GET | /me | authenticated | no | no | - | P1 | AZ-E007-A, AZ-E007-D |
| E008 | identity | POST | /internal/sessions/validate | service:gateway | no | no | unauthenticated | P1 | AZ-E008-A, AZ-E008-D, AZ-SVC-01 |
| E009 | identity | POST | /internal/oauth/token | service:any | no | no | unauthenticated | P1 | AZ-E009-A, AZ-E009-D, AZ-SVC-02 |
| E010 | identity | POST | /dealerships/{id}/staff/invites | dealer_admin | yes | no | - | P3 | AZ-E010-A, AZ-E010-D, AZ-E010-X |
| E011 | identity | POST | /staff-invites/{token}/accept | authenticated | yes | no | not-found | P3 | AZ-E011-A, AZ-E011-D |
| E012 | identity | DELETE | /dealerships/{id}/staff/{userId} | dealer_admin | yes | no | not-found | P2 | AZ-E012-A, AZ-E012-D, AZ-E012-X, AZ-T6 |
| E013 | identity | GET | /dealerships/{id}/staff | dealer_admin | no | no | - | P3 | AZ-E013-A, AZ-E013-D, AZ-E013-X |
| E014 | identity | PUT | /me/consents/{purpose} | authenticated | no | no | - | P5 | AZ-E014-A, AZ-E014-D |
| E015 | identity | POST | /me/data-export | authenticated | yes | no | - | P5 | AZ-E015-A, AZ-E015-D |
| E016 | identity | POST | /me/erasure | authenticated | yes | no | - | P5 | AZ-E016-A, AZ-E016-D |
| E017 | identity | POST | /internal/erasure/{id}/confirm | service:any | yes | no | not-found | P5 | AZ-E017-A, AZ-E017-D |
| E020 | catalog | GET | /catalog/models | anonymous | no | no | - | P1 | AZ-E020-A |
| E021 | catalog | GET | /catalog/models/{slug} | anonymous | no | no | not-found | P1 | AZ-E021-A |
| E022 | catalog | GET | /catalog/variants/compare | anonymous | no | no | validation-failed | P1 | AZ-E022-A |
| E023 | catalog | GET | /catalog/variants/{id}/price-estimate | anonymous | no | no | not-found, quote-invalid | P1 | AZ-E023-A |
| E029 | catalog | GET | /internal/models/{slug} | service:pool | no | no | not-found | P1 | AZ-E029-A, AZ-E029-D |
| E024 | catalog | GET | /me/saved-cars | buyer | no | no | - | P2 | AZ-E024-A, AZ-E024-D |
| E025 | catalog | PUT | /me/saved-cars/{modelId} | buyer | no | no | not-found | P2 | AZ-E025-A, AZ-E025-D |
| E026 | catalog | DELETE | /me/saved-cars/{modelId} | buyer | no | no | - | P2 | AZ-E026-A, AZ-E026-D |
| E027 | catalog | PUT | /admin/catalog/variants/{id}/prices/{city} | platform_admin | yes | yes | version-conflict | P5 | AZ-E027-A, AZ-E027-D, AZ-T7 |
| E028 | catalog | POST | /admin/catalog/rulesets | platform_admin | yes | no | validation-failed | P5 | AZ-E028-A, AZ-E028-D |
| E030 | pool | GET | /me/pool-suggestions | buyer | no | no | - | P2 | AZ-E030-A, AZ-E030-D |
| E031 | pool | PUT | /me/preferences | buyer | no | no | validation-failed | P2 | AZ-E031-A, AZ-E031-D |
| E043 | pool | GET | /me/preferences | buyer | no | no | - | P2 | AZ-E043-A, AZ-E043-D |
| E044 | pool | GET | /internal/pools/{id}/access | service:realtime | no | no | - | P2 | AZ-E044-A, AZ-E044-D |
| E045 | pool | GET | /internal/pools/{id}/version | service:realtime | no | no | not-found | P2 | AZ-E045-A, AZ-E045-D |
| E032 | pool | POST | /pools | buyer | yes | no | active-commitment-exists | P1 | AZ-E032-A, AZ-E032-D |
| E033 | pool | GET | /pools/{id} | buyer, dealer_staff, dealer_admin, platform_admin | no | no | not-found | P1 | AZ-E033-A, AZ-E033-D, AZ-E033-X, AZ-T1 |
| E034 | pool | POST | /pools/{id}/members | buyer | yes | no | already-member, rejoin-limit-reached, invalid-transition, deadline-passed | P1 | AZ-E034-A, AZ-E034-D, AZ-INT-01 |
| E035 | pool | POST | /pools/{id}/commitment | buyer | yes | no | active-commitment-exists, invalid-transition, deadline-passed | P1 | AZ-E035-A, AZ-E035-D, AZ-INT-01 |
| E036 | pool | DELETE | /pools/{id}/members/me | buyer | yes | yes | invalid-transition, deadline-passed, version-conflict | P1 | AZ-E036-A, AZ-E036-D |
| E037 | pool | PUT | /pools/{id}/members/me/acceptance | buyer | yes | yes | not-a-member, quote-not-final, deadline-passed, version-conflict | P1 | AZ-E037-A, AZ-E037-D, AZ-T3 |
| E038 | pool | DELETE | /pools/{id}/members/me/acceptance | buyer | yes | yes | deadline-passed, version-conflict | P1 | AZ-E038-A, AZ-E038-D |
| E039 | pool | GET | /pools/{id}/members/me | buyer | no | no | not-found | P1 | AZ-E039-A, AZ-E039-D, AZ-T4 |
| E040 | pool | POST | /admin/pools/{id}/cancel | platform_admin | yes | yes | invalid-transition, version-conflict | P4 | AZ-E040-A, AZ-E040-D |
| E041 | pool | GET | /admin/pools | platform_admin | no | no | - | P5 | AZ-E041-A, AZ-E041-D, AZ-T7 |
| E042 | pool | PUT | /pools/{id}/members/me/reround-choice | buyer | yes | yes | invalid-transition, deadline-passed, version-conflict | P2 | AZ-E042-A, AZ-E042-D, AZ-E042-X |
| E050 | dealer | POST | /dealerships | authenticated | yes | no | validation-failed | P3 | AZ-E050-A, AZ-E050-D, AZ-INT-02 |
| E051 | dealer | POST | /dealerships/{id}/documents | dealer_admin | yes | no | - | P3 | AZ-E051-A, AZ-E051-D, AZ-E051-X |
| E052 | dealer | GET | /dealerships/{id} | anonymous | no | no | not-found | P3 | AZ-E052-A |
| E053 | dealer | POST | /admin/dealerships/{id}/verify | platform_admin | yes | yes | invalid-transition, version-conflict | P3 | AZ-E053-A, AZ-E053-D |
| E153 | dealer | GET | /admin/dealerships | platform_admin | no | no | - | P3 | AZ-E153-A, AZ-E153-D |
| E154 | order | POST | /bookings/{id}/confirm-delivery | buyer | yes | no | invalid-transition, not-found | P4 | AZ-E154-A, AZ-E154-D, AZ-E154-X |
| E155 | order | GET | /dealer/bookings/{id}/contact | dealer_staff, dealer_admin | no | no | not-found | P4 | AZ-E155-A, AZ-E155-D, AZ-E155-X |
| E156 | order | GET | /admin/disputes | platform_admin | no | no | - | P4 | AZ-E156-A, AZ-E156-D |
| E157 | identity | GET | /internal/users/{id}/contact | service:order, service:notification | no | no | not-found | P4 | AZ-E157-A, AZ-E157-D |
| E158 | notification | POST | /me/notifications/read-all | authenticated | no | no | - | P5 | AZ-E158-A, AZ-E158-D |
| E159 | notification | GET | /me/notification-preferences | authenticated | no | no | - | P5 | AZ-E159-A, AZ-E159-D |
| E160 | notification | GET | /me/push-subscriptions/key | authenticated | no | no | - | P5 | AZ-E160-A, AZ-E160-D |
| E161 | notification | PUT | /me/push-subscriptions | authenticated | no | no | validation-failed, dependency-unavailable | P5 | AZ-E161-A, AZ-E161-D |
| E162 | notification | DELETE | /me/push-subscriptions | authenticated | no | no | - | P5 | AZ-E162-A, AZ-E162-D |
| E163 | audit | GET | /admin/audit | platform_admin | no | no | - | P5 | AZ-E163-A, AZ-E163-D |
| E164 | identity | GET | /me/data-export/{id} | authenticated | no | no | not-found | P5 | AZ-E164-A, AZ-E164-D, AZ-E164-X |
| E165 | identity | POST | /internal/exports/{id}/parts | service:any | no | no | not-found | P5 | AZ-E165-A, AZ-E165-D |
| E166 | identity | GET | /me/erasure/{id} | authenticated | no | no | not-found | P5 | AZ-E166-A, AZ-E166-D, AZ-E166-X |
| E168 | identity | GET | /me/consents | authenticated | no | no | - | P5 | AZ-E168-A, AZ-E168-D |
| E054 | dealer | POST | /admin/dealerships/{id}/suspend | platform_admin | yes | yes | version-conflict | P3 | AZ-E054-A, AZ-E054-D |
| E055 | dealer | GET | /internal/eligibility | service:pool | no | no | - | P1 | AZ-E055-A, AZ-E055-D |
| E056 | dealer | POST | /bookings/{bookingId}/review | buyer | yes | no | review-not-eligible | P4 | AZ-E056-A, AZ-E056-D, AZ-E056-X, AZ-INT-07 |
| E057 | dealer | GET | /dealerships/{id}/reviews | anonymous | no | no | not-found | P4 | AZ-E057-A |
| E058 | dealer | GET | /dealer/analytics | dealer_admin | no | no | - | P5 | AZ-E058-A, AZ-E058-D, AZ-T5 |
| E060 | quote | GET | /dealer/inbox | dealer_staff, dealer_admin | no | no | - | P1 | AZ-E060-A, AZ-E060-D, AZ-T1 |
| E061 | quote | POST | /dealer/pools/{poolId}/quotes | dealer_staff, dealer_admin | yes | no | round-not-open, deadline-passed, not-found | P1 | AZ-E061-A, AZ-E061-D, AZ-E061-X |
| E062 | quote | PUT | /dealer/quotes/{id}/draft | dealer_staff, dealer_admin | no | yes | invalid-transition, version-conflict | P1 | AZ-E062-A, AZ-E062-D, AZ-E062-X |
| E063 | quote | POST | /dealer/quotes/{id}/submit | dealer_staff, dealer_admin | yes | yes | deadline-passed, quote-invalid, invalid-tiers, discount-exceeds-limit, version-conflict | P1 | AZ-E063-A, AZ-E063-D, AZ-E063-X, AZ-T5 |
| E064 | quote | POST | /dealer/quotes/{id}/revisions | dealer_staff, dealer_admin | yes | yes | deadline-passed, quote-invalid, invalid-tiers, invalid-transition, version-conflict | P3 | AZ-E064-A, AZ-E064-D, AZ-E064-X |
| E065 | quote | POST | /dealer/quotes/{id}/withdraw | dealer_staff, dealer_admin | yes | yes | deadline-passed, invalid-transition, version-conflict | P3 | AZ-E065-A, AZ-E065-D, AZ-E065-X |
| E066 | quote | POST | /dealer/quotes/{id}/cancel | dealer_admin | yes | yes | invalid-transition, version-conflict | P3 | AZ-E066-A, AZ-E066-D, AZ-E066-X |
| E067 | quote | GET | /dealer/quotes/{id} | dealer_staff, dealer_admin | no | no | not-found | P1 | AZ-E067-A, AZ-E067-D, AZ-E067-X, AZ-T5 |
| E068 | quote | GET | /dealer/quotes/{id}/rank | dealer_staff, dealer_admin | no | no | not-found | P3 | AZ-E068-A, AZ-E068-D, AZ-E068-X, AZ-RNK-01, AZ-RNK-02, AZ-RNK-03 |
| E069 | quote | GET | /pools/{id}/quotes | buyer | no | no | not-found | P1 | AZ-E069-A, AZ-E069-D, AZ-E069-X |
| E070 | quote | GET | /pools/{id}/quotes/{quoteId} | buyer | no | no | not-found | P1 | AZ-E070-A, AZ-E070-D, AZ-E070-X, AZ-T2 |
| E071 | quote | POST | /pools/{id}/quotes/{quoteId}/clarifications | buyer | yes | no | invalid-transition | P5 | AZ-E071-A, AZ-E071-D, AZ-E071-X |
| E072 | quote | POST | /dealer/clarifications/{id}/answer | dealer_staff, dealer_admin | yes | no | invalid-transition | P5 | AZ-E072-A, AZ-E072-D, AZ-E072-X, AZ-T2 |
| E073 | quote | GET | /internal/rounds/{id}/summary | service:pool | no | no | not-found | P1 | AZ-E073-A, AZ-E073-D |
| E074 | quote | POST | /dealer/price-preview | dealer_staff, dealer_admin | no | no | quote-invalid | P3 | AZ-E074-A, AZ-E074-D |
| E080 | order | GET | /me/orders | buyer | no | no | - | P1 | AZ-E080-A, AZ-E080-D |
| E081 | order | GET | /orders/{id} | buyer | no | no | not-found | P1 | AZ-E081-A, AZ-E081-D, AZ-E081-X, AZ-T4 |
| E082 | order | POST | /orders/{id}/confirm | buyer | yes | yes | deadline-passed, capacity-reached, deposit-not-captured, invalid-transition, version-conflict | P1 | AZ-E082-A, AZ-E082-D, AZ-E082-X |
| E083 | order | POST | /orders/{id}/decline | buyer | yes | no | invalid-transition | P4 | AZ-E083-A, AZ-E083-D, AZ-E083-X |
| E084 | order | POST | /orders/{id}/cancel | buyer | yes | yes | invalid-transition, version-conflict | P4 | AZ-E084-A, AZ-E084-D, AZ-E084-X |
| E085 | order | GET | /orders/{id}/voucher | buyer | no | no | not-found, invalid-transition | P1 | AZ-E085-A, AZ-E085-D, AZ-E085-X |
| E086 | order | POST | /bookings/{id}/cancel | buyer | yes | yes | invalid-transition, version-conflict | P4 | AZ-E086-A, AZ-E086-D, AZ-E086-X |
| E087 | order | GET | /dealer/bookings | dealer_staff, dealer_admin | no | no | - | P1 | AZ-E087-A, AZ-E087-D, AZ-T2, AZ-T5 |
| E088 | order | POST | /dealer/bookings/{id}/confirm | dealer_staff, dealer_admin | yes | yes | deadline-passed, invalid-transition, version-conflict | P1 | AZ-E088-A, AZ-E088-D, AZ-E088-X |
| E089 | order | PUT | /dealer/bookings/{id}/delivery | dealer_staff, dealer_admin | yes | yes | invalid-transition, version-conflict | P4 | AZ-E089-A, AZ-E089-D, AZ-E089-X |
| E090 | order | POST | /dealer/bookings/{id}/deliver | dealer_staff, dealer_admin | yes | no | invalid-transition, forbidden | P4 | AZ-E090-A, AZ-E090-D, AZ-E090-X |
| E091 | order | POST | /dealer/bookings/{id}/cancel | dealer_admin | yes | yes | invalid-transition, version-conflict | P4 | AZ-E091-A, AZ-E091-D, AZ-E091-X |
| E092 | order | POST | /orders/{id}/disputes | buyer | yes | no | invalid-transition, deadline-passed | P4 | AZ-E092-A, AZ-E092-D, AZ-E092-X |
| E093 | order | GET | /disputes/{id} | buyer, dealer_staff, dealer_admin, platform_admin | no | no | not-found | P4 | AZ-E093-A, AZ-E093-D, AZ-E093-X, AZ-T7 |
| E094 | order | POST | /disputes/{id}/responses | buyer, dealer_staff, dealer_admin | yes | no | invalid-transition | P4 | AZ-E094-A, AZ-E094-D, AZ-E094-X |
| E095 | order | POST | /disputes/{id}/appeal | buyer | yes | no | deadline-passed, invalid-transition | P4 | AZ-E095-A, AZ-E095-D, AZ-E095-X |
| E096 | order | POST | /disputes/{id}/withdraw | buyer | yes | no | invalid-transition | P4 | AZ-E096-A, AZ-E096-D, AZ-E096-X |
| E097 | order | POST | /disputes/{id}/evidence-uploads | buyer, dealer_staff, dealer_admin | yes | no | validation-failed | P4 | AZ-E097-A, AZ-E097-D, AZ-E097-X |
| E098 | order | POST | /admin/disputes/{id}/assign | platform_admin | yes | no | invalid-transition | P4 | AZ-E098-A, AZ-E098-D |
| E099 | order | POST | /admin/disputes/{id}/request-info | platform_admin | yes | no | invalid-transition | P4 | AZ-E099-A, AZ-E099-D |
| E100 | order | POST | /admin/disputes/{id}/resolve | platform_admin | yes | no | invalid-transition, second-approval-required | P4 | AZ-E100-A, AZ-E100-D, AZ-T7 |
| E101 | order | PUT | /bookings/{id}/invoice | buyer | yes | no | invalid-transition | P4 | AZ-E101-A, AZ-E101-D, AZ-E101-X |
| E102 | order | GET | /internal/pools/{id}/resolution | service:pool | no | no | not-found | P4 | AZ-E102-A, AZ-E102-D |
| E110 | payment | POST | /internal/deposits | service:pool | yes | no | dependency-unavailable | P1 | AZ-E110-A, AZ-E110-D, AZ-SVC-03 |
| E111 | payment | POST | /payments/{id}/checkout-callback | buyer | no | no | webhook-signature-invalid, not-found | P1 | AZ-E111-A, AZ-E111-D, AZ-E111-X |
| E112 | payment | GET | /me/payments/{id} | buyer | no | no | not-found | P1 | AZ-E112-A, AZ-E112-D, AZ-E112-X, AZ-T4 |
| E113 | payment | POST | /webhooks/razorpay | razorpay | no | no | webhook-signature-invalid | P1 | AZ-E113-A, AZ-E113-D, AZ-INT-06 |
| E114 | payment | GET | /admin/refunds | platform_admin | no | no | - | P2 | AZ-E114-A, AZ-E114-D, AZ-T7 |
| E115 | payment | POST | /admin/refunds/{id}/retry | platform_admin | yes | no | invalid-transition | P2 | AZ-E115-A, AZ-E115-D |
| E116 | payment | POST | /admin/refunds/{id}/resolve-manually | platform_admin | yes | no | invalid-transition, second-approval-required | P4 | AZ-E116-A, AZ-E116-D, AZ-INT-08 |
| E117 | payment | POST | /admin/refunds/{id}/approve | platform_admin | yes | no | forbidden, second-approval-required | P4 | AZ-E117-A, AZ-E117-D, AZ-INT-08 |
| E118 | payment | GET | /admin/reconciliation/runs | platform_admin | no | no | - | P4 | AZ-E118-A, AZ-E118-D |
| E120 | realtime | GET | /realtime/pools/{id}/stream | buyer, dealer_staff, dealer_admin | no | no | not-found | P2 | AZ-E120-A, AZ-E120-D, AZ-E120-X, AZ-T1 |
| E121 | realtime | GET | /realtime/dealer/stream | dealer_staff, dealer_admin | no | no | - | P2 | AZ-E121-A, AZ-E121-D, AZ-T6 |
| E122 | realtime | GET | /realtime/me/stream | buyer | no | no | - | P2 | AZ-E122-A, AZ-E122-D |
| E130 | notification | GET | /me/notifications | authenticated | no | no | - | P5 | AZ-E130-A, AZ-E130-D |
| E131 | notification | POST | /me/notifications/{id}/read | authenticated | no | no | not-found | P5 | AZ-E131-A, AZ-E131-D, AZ-E131-X |
| E132 | notification | PUT | /me/notification-preferences | authenticated | no | no | - | P5 | AZ-E132-A, AZ-E132-D |
| E140 | audit | GET | /admin/audit | platform_admin | no | no | - | P5 | AZ-E140-A, AZ-E140-D, AZ-T7 |
| E141 | audit | POST | /admin/audit/verify-chain | platform_admin | no | no | - | P5 | AZ-E141-A, AZ-E141-D |
| E142 | audit | GET | /admin/metrics | platform_admin | no | no | - | P5 | AZ-E142-A, AZ-E142-D |
| E150 | gateway | GET | /.well-known/jwks.json | anonymous | no | no | - | P1 | AZ-E150-A |

## Notes per group
- **Pool snapshot (E033)** returns counts, state, deadlines, accepted variants, own membership (buyer) — never member identities (AZ-T1). Dealers of invited dealerships see counts only.
- **Comparison (E069/E070)** returns only quotes visible to a committed member of that pool: SUBMITTED/REVISED versions (sealed from other dealers, visible to buyers), FINAL, with full breakdown and evidence per line. Dealer contact details are not included before consent (AZ-T2 is the reverse direction).
- **Webhook (E113)**: raw-body HMAC check before parsing; returns 200 for duplicates; 400 `webhook-signature-invalid` otherwise. Not behind the session gateway filter, but behind WAF (full) / Caddy (lite) with a body-size limit (64 KB).
- **Price preview (E074)** runs the Java engine on a draft without persisting; the quote builder normally runs the TypeScript engine locally and calls E074 on submit-review to cross-check (mismatch → blocking error and alarm).
- Pagination: cursor-based (`?cursor=&limit=`, limit ≤ 100) on all list endpoints.
- **E030** takes the saved model slugs as repeated `?model=` (from catalog E024), so pool needs no call to catalog. **E043-E045** were added in P2 (SPEC §6, contract additions in P2).
