# Savings: what Convoy may claim (PLAN §2.5)

Four labels, **never merged, never summed**. Each is computed from stored price snapshots (pricing-model.md §6) for the *same configuration* (same variant, city, mandatory items, insurance cover).

| # | Label (UI text) | Definition | Source data | May show "₹X saved"? |
|---|---|---|---|---|
| 1 | **Dealer-stated discount** | `discount.amount_paise` of the quote version, as entered by the dealer | quote version | **No.** Shown as "Dealer discount ₹X (as stated by the dealer)" |
| 2 | **Difference vs reference price** | `catalogue_estimate.cash_payable − quote.cash_payable` where the catalogue estimate uses the dated catalogue ex-showroom (source + date shown) and the same rules version | catalogue snapshot + quote snapshot | **No.** Shown as "₹X below the catalogue estimate dated 24 Oct 2026" (or "above"). Hidden if the catalogue price is UNVERIFIED |
| 3 | **Difference vs best other quote** | `best_other.cash_payable − this.cash_payable`, best other = lowest cash payable among *other* dealers' FINAL quotes in the same pool for the same variant and configuration mode (BUYER_CONFIG) | quote snapshots in pool | **Yes**, only if ≥ 1 other valid FINAL quote exists: "₹X saved vs the next-best offer in this pool (Dealer B)" |
| 4 | **Realised saving** | `reference.cash_payable − invoice_total` where `invoice_total` is entered or uploaded by the buyer after delivery and the buyer confirms it | buyer invoice confirmation | **Yes**, only after buyer confirmation: "You saved ₹X vs the catalogue estimate, based on your invoice" |

Rules:
- "₹X saved" appears **only** for labels 3 and 4, always naming the comparison ("vs …").
- Negative differences are shown honestly ("₹X more than …"), never hidden or zeroed.
- Marketplace metrics aggregate each label separately and, when computed from synthetic dealers/buyers, carry the label **"simulated"** (product/metrics.md).
- Label 4 requires an invoice amount; no invoice → no realised saving claim. The quoted-vs-final price pair is recorded for fake-discount detection (threat-model.md).
- The comparison always uses cash payable (not net cost) unless the UI explicitly says "net of TCS".
