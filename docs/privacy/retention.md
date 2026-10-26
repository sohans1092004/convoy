# Retention and erasure (P5.D, DPDP Act 2023 and Rules 2025)

**Legal review flagged.** The periods below are taken from the statutes cited (accessed 2026-10-26, secondary sources
reproducing the text; check against the official gazette before relying on them). Nothing here is legal advice.

On account deletion (E016 → `ErasureRequested`) every service applies its `convoy.privacy` settings (its `application.yml`):
**delete**, or **pseudonymise** (the user id is replaced by a random id unique to that service and user, free text by the
person is cleared, ids inside other columns and in published outbox payloads are rewritten). Identity tracks every
confirmation (E017) and erases itself last; the `users` row stays as a tombstone with no personal data.
Each service's retention job (`Privacy.enforceRetention`, daily) deletes kept rows once their period has passed.

| Category | Where | On deletion | Kept for | Source |
|---|---|---|---|---|
| Account: email, language, roles, passkeys, sessions, consents | identity | delete (tombstone row keeps only the id) | until deletion | — |
| Saved cars | catalog `saved_cars` | delete | until deletion | — |
| Matching preferences | pool `preferences` | delete | until deletion | — |
| Messages: inbox, email/push deliveries, push subscriptions, preferences | notification | delete | until deletion | — |
| Data export files | identity `data_exports`, S3 `convoy-data-exports` | delete | 24 h after ready (cleanup job) | owner decision P5.D |
| Payments, refunds, ledger | payment `payments`, `refunds`, `journal_*` | pseudonymise | **8 years** from the payment; whole journal entries go together, so the ledger stays balanced | Companies Act 2013 s.128(5) (books of account, 8 financial years); CGST Act 2017 s.36 (72 months) is shorter |
| Orders, bookings, price snapshots, contact consents and releases | order | pseudonymise | **8 years** from the order | Companies Act 2013 s.128(5) |
| Disputes, history, evidence records | order `disputes`, `dispute_*` | pseudonymise | **2 years** after resolution | Consumer Protection Act 2019 s.69 (complaints within 2 years of the cause of action; delay can be condoned) |
| Pool participation | pool `memberships`, `pools.created_by`; quote `member_replica` | pseudonymise | with the payments they back (no separate job yet) | follows the payments |
| Reviews | dealer `reviews` | pseudonymise, text cleared | while the dealer is listed | — |
| Dealer reliability inputs | dealer `delivered_bookings` | pseudonymise | 90-day window in use (AMENDMENT-009); rows kept | — |
| Staff and admin actions | dealer, quote, payment, order audit columns | pseudonymise | with the record they belong to | — |
| Audit log | audit `audit_log` | not changed (append-only, hash chain); it holds ids only, which no longer resolve to a person once identity has erased the account | **at least 1 year** (DPDP Rules 2025 r.6(1)(e)); kept 8 years because it records the payments above | DPDP Rules 2025 r.6(1)(e); Companies Act s.128(5) |

Not yet enforced (known gaps): pruning the audit log (first rows reach 8 years in 2034; needs an owner-role command that
keeps the chain verifiable from a signed checkpoint), deleting dispute evidence files from S3 together with their rows,
and a separate period for pool participation.

Sources: [DPDP Rules r.6](https://dpdpa.com/dpdparules/rule6.html), [DSCI safeguards FAQ](https://www.dsci.in/files/content/documents/2025/reasonable-security-safeguards.pdf),
[Companies Act s.128](https://www.incometaxindia.gov.in/w/section-128-81), [CGST s.36](https://www.knowyourgst.com/gstlaw/cgst-act/36-period-of-retention-of-accounts-36/),
[Consumer Protection Act s.69](https://www.advocatekhoj.com/library/bareacts/consumerprotection2019/69.php).
