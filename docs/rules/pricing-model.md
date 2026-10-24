# Canonical price model (PLAN §2.4)

One pure, versioned function `compute(input) -> output` used by the catalogue, the quote builder, comparison, acceptance, confirmation and tier resolution. Reference implementation: [`spec-tools/price_ref.py`](../../spec-tools/price_ref.py). Contract: [`spec-tools/vectors/pricing.json`](../../spec-tools/vectors/pricing.json) (54 cases). The Java (`catalog`/`quote`/`order` services) and TypeScript (web) engines must reproduce every vector's `expected` object and `snapshot_sha256` exactly (ADR-011).

Ruleset version in force: **`2026.10.24-p0`** (all files in [`docs/data/rules/`](../data/rules/) carry it; the engine refuses a mismatched input with `RULESET_VERSION_MISMATCH`).

## 1. Money, rates, rounding (ADR-012)
- Money is **integer paise** everywhere (inputs, outputs, DB `BIGINT`, JSON numbers, events). No floating point in any engine.
- Rates are **integer basis points** (1 % = 100 bp).
- `pct(amount, bp)` = `amount × bp / 10000`, **rounded half up to the whole rupee**, returned in paise. Integer form: `q, r = divmod(amount × bp, 1_000_000)`; if `2r ≥ 1_000_000` then `q += 1`; result `q × 100`. All inputs to `pct` are ≥ 0, so half-up = half-away-from-zero.
- **Every percentage-derived line is rounded independently at the moment it is computed**, in this order: road tax → road-tax cess (computed on the *rounded* tax) → IDV → OD (on the rounded IDV) → insurance GST (on rounded OD + TP) → TCS. Totals are plain integer sums of already-rounded lines; totals are never rounded again (Q-018).
- Dealer-entered amounts are taken as given (paise precision allowed, e.g. vector P005 uses ₹10,00,000.01).

## 2. Inputs
```json
{
  "ruleset_version": "2026.10.24-p0",
  "mode": "BUYER_CONFIG | RANKING",
  "state": "MH | DL | KA",
  "vehicle": {"variant_id": "...", "fuel": "PETROL|DIESEL|CNG|HYBRID|EV", "engine_cc": 1197,
              "ex_showroom_paise": 58390000, "ex_showroom_evidence": "DEALER_CONFIRMED|CATALOGUE_ESTIMATE|UNVERIFIED"},
  "discount": {"amount_paise": 0, "base": "EX_SHOWROOM", "evidence": "DEALER_CONFIRMED"},
  "registration": null | {"amount_paise": 0, "evidence": "..."},
  "insurance": null | {"od_paise": 0, "tp_paise": 0, "gst_paise": 0, "evidence": "...", "declared_cover": {"idv_bp": 9500, "addons": []}, "binding": true},
  "insurance_addons": [{"code": "ZERO_DEP", "label": "...", "amount_paise": 0, "selected": true, "evidence": "..."}],
  "fees": [{"code": "FASTAG", "label": "...", "amount_paise": 0, "basis": "...", "evidence": "..."}],
  "accessories": [{"code": "...", "label": "...", "amount_paise": 0, "required": true, "selected": true, "evidence": "..."}],
  "extended_warranty": null | {"amount_paise": 0, "selected": false, "evidence": "..."},
  "other_charges": [{"code": "HANDLING", "label": "...", "amount_paise": 0, "evidence": "..."}]
}
```
`null` for `registration` / `insurance` means "not supplied by the dealer": the engine fills a **catalogue estimate** from the state rules.

`declared_cover` and `binding` on `insurance` only matter in `RANKING` mode: the dealer figure is used for ranking only if `binding` is true and `declared_cover` is exactly the baseline (`idv_bp` = 10000 − `idv_depreciation_bp`, `addons` empty). Otherwise the engine uses the baseline estimate and warns `DEALER_INSURANCE_NOT_RANKABLE` (AMENDMENT-004). In `BUYER_CONFIG` mode the dealer figure is always shown.

## 3. Components

| Line code | Formula | Evidence of the line | In cash payable? |
|---|---|---|---|
| `EX_SHOWROOM` | input (exact variant + city, with catalogue source/effective date when it comes from the catalogue) | input evidence | yes |
| `DISCOUNT` | `−discount.amount_paise`; base must be `EX_SHOWROOM` (Q-014); `0 ≤ d ≤ ⌊ex × 2500 / 10000⌋` (25 % cap, Q-014) | input evidence | yes (negative) |
| *vehicle price* | `vp = ex − d` (not a line; reported as `vehicle_price_paise`) | weakest(ex, discount) | — |
| `REGISTRATION` | dealer amount when supplied | input evidence | yes |
| `ROAD_TAX` | estimate when no dealer amount: `pct(vp, slab(state, fuel, vp))`, capped at `max_tax_paise` (MH ₹30 lakh). Slab upper bounds inclusive. Base = `vp` (Q-015) | weakest(rule evidence, vp evidence, CATALOGUE_ESTIMATE) | yes |
| `ROAD_TAX_CESS` | KA only: `pct(ROAD_TAX, 1100) + 100000` | as ROAD_TAX | yes |
| `REGISTRATION_FEE` | ₹600 (rto.yaml `common`) | CATALOGUE_ESTIMATE | yes |
| `SMART_CARD_FEE` | ₹200 (rto.yaml `common`, sources conflict on applicability) | UNVERIFIED | yes |
| `INSURANCE_OD/TP/GST` | dealer amounts, or estimate: `IDV = pct(ex, 9500)`; `OD = pct(IDV, 200)`; `TP` by engine cc (≤1000: ₹6,521; ≤1500: ₹10,640; >1500: ₹24,596, 3-year); `GST = pct(OD + TP, 1800)`. EV estimate refused (`INSURANCE_ESTIMATE_UNAVAILABLE`) | dealer evidence, or weakest(CATALOGUE_ESTIMATE, ex evidence) | yes (baseline cover = comprehensive yr-1 OD + 3-yr TP) |
| `INSURANCE_ADDON:<code>` | input; flag `OPTIONAL` | input | only if `selected` and mode = BUYER_CONFIG |
| `FEE:<code>` | input with `basis` text (FASTag, etc.) | input | yes |
| `ACCESSORY:<code>` | input; `required:true` → flag `DEALER_REQUIRED` + warning `REQUIRED_ACCESSORY:<code>`; otherwise flag `OPTIONAL` | input | required: always; optional: only if `selected` and BUYER_CONFIG |
| `EXTENDED_WARRANTY` | input; flag `OPTIONAL` | input | only if `selected` and BUYER_CONFIG |
| `CHARGE:<code>` | input; codes in `questionable_charge_codes` (`HANDLING`) get flag `QUESTIONABLE` + warning — still payable, flagged for the buyer to query | input | yes |
| `TCS` | `vp > 100000000 ? pct(vp, 100) : 0` — **strictly exceeding** ₹10,00,000, 1 % of the **whole** vehicle consideration (see §5) | vp evidence | yes |

Rule evidence mapping (from the `*_evidence` labels in the rule files): `VERIFIED → DEALER_CONFIRMED`-strength (a verified statute does not weaken a line), `SECONDARY`/`ASSUMPTION → CATALOGUE_ESTIMATE`, `CONFLICTING → UNVERIFIED` (estimate still shown, labelled), `UNVERIFIED → refuse` (error; dealer must supply the figure). Evidence strength order: `DEALER_CONFIRMED > CATALOGUE_ESTIMATE > UNVERIFIED`; a computed line takes the **weakest** of its inputs. This is the rule-based evidence indicator of PLAN §3.1(6) — not a score.

## 4. Outputs and the two headline figures
```json
{"status": "OK", "vehicle_price_paise": 0, "lines": [{"code": "...", "amount_paise": 0, "evidence": "...", "in_cash_payable": true, "flags": []}],
 "cash_payable_paise": 0, "tcs_paise": 0, "estimated_net_cost_paise": 0, "warnings": ["..."]}
```
or `{"status": "INVALID", "errors": ["..."]}` (sorted, de-duplicated; no totals).

- **Cash payable at purchase** = Σ `amount_paise` of lines with `in_cash_payable = true` (includes TCS — it is cash paid now).
- **Estimated net cost** = cash payable − TCS, shown with the copy: *"TCS of ₹X is collected by the dealer and is generally claimable as a credit against your income tax. Estimated net cost assumes you can claim it. Not tax advice."*
- There is never a single opaque "effective price".

Error codes: `RULESET_VERSION_MISMATCH`, `EX_SHOWROOM_MISSING`, `UNKNOWN_FUEL`, `UNKNOWN_STATE`, `DISCOUNT_BASE_UNSUPPORTED`, `DISCOUNT_EXCEEDS_LIMIT`, `NEGATIVE_OR_INVALID_AMOUNT:<code>`, `INVALID_EVIDENCE:<code>`, `REGISTRATION_ESTIMATE_UNAVAILABLE`, `INSURANCE_ESTIMATE_UNAVAILABLE`, `ENGINE_CC_MISSING`. Warnings: `REGISTRATION_ESTIMATED`, `INSURANCE_ESTIMATED`, `REQUIRED_ACCESSORY:<code>`, `QUESTIONABLE_CHARGE:<code>`, `TCS_APPLIES`.

**Validation at quote submit** (QUO-T02/T03): the engine must return `OK` for every variant in the quote, in both modes, for every tier's discount; otherwise `422 quote-invalid` (or `discount-exceeds-limit`) with the engine error codes in `errors[]`.

## 5. TCS (re-verified 2026-10-24) — [`tcs.yaml`](../data/rules/tcs.yaml)
- **Rule:** Income-tax Act, 2025, **s.394(1) Table Sl. No. 6** (formerly s.206C(1F) of the 1961 Act): *"Sale consideration exceeding ten lakh rupees in case of — motor vehicle"*, collected by the **seller**, rate **1 %**. In force **1 April 2026** (the Act's commencement); sales before that date fall under s.206C(1F) with the same wording.
- **Compared amount:** sale consideration of the vehicle = ex-showroom (GST-inclusive) − dealer discount on the vehicle (Q-001; GST inclusion has no post-GST CBDT clarification — recorded as open).
- **Condition:** strictly greater than ₹10,00,000 ("exceeding"). ₹10,00,000.00 → ₹0 (P004); ₹10,00,000.01 → ₹10,000 (P005).
- **Amount:** 1 % of the full consideration, not the excess; rounded half up to the rupee (Q-002).
- Assumes the buyer furnishes PAN (higher non-PAN rate not verified; UI states the assumption).

## 6. Stored calculation snapshot
Every calculation persisted by a service (catalogue estimate cached per variant/city/ruleset, each quote version × variant × tier, ceiling and final price per order) stores:

| Field | Content |
|---|---|
| `inputs` | the exact input object above |
| `ruleset_version` | copied from inputs |
| `output` | the exact engine output |
| `computed_at` | UTC timestamp (DB clock) — **not** part of the hash |
| `sha256` | hex SHA-256 of canonical JSON of `{"inputs", "output", "ruleset_version"}` |

Canonical JSON: keys sorted, separators `,` and `:` with no whitespace, UTF-8, non-ASCII not escaped, integers only. For Convoy's data (ASCII keys, no floats) this equals RFC 8785 (JCS); Java/TS implementations may use a JCS library. A quote accepted under yesterday's rules is explained by re-rendering its stored snapshot, never by recomputing with today's rules; recomputation with the stored `ruleset_version` must give the same hash (tested in P1 against an archived ruleset).

## 7. Hand-verified vectors
Computed by hand below; [`gen_vectors.py`](../../spec-tools/gen_vectors.py) refuses to write vectors if the engine disagrees (`HAND_VERIFIED`).

**P001 — MH petrol, Swift LXi ex-showroom ₹5,83,900, no discount, 1197 cc, registration and insurance estimated**
1. vp = 5,83,900 − 0 = ₹5,83,900 → MH petrol slab ≤ ₹10 lakh = 11 %.
2. Road tax = 5,83,900 × 11 % = ₹64,229.00 → ₹64,229.
3. Registration fee ₹600; smart card ₹200.
4. IDV = 5,83,900 × 95 % = ₹5,54,705.00. OD = 5,54,705 × 2 % = ₹11,094.10 → ₹11,094.
5. TP (1197 cc ≤ 1500) = ₹10,640. GST = (11,094 + 10,640) × 18 % = 21,734 × 0.18 = ₹3,912.12 → ₹3,912.
6. TCS: ₹5,83,900 does not exceed ₹10 lakh → ₹0.
7. Cash payable = 5,83,900 + 64,229 + 600 + 200 + 11,094 + 10,640 + 3,912 + 0 = **₹6,74,575** (67457500 paise). Net cost = ₹6,74,575.

**P005 — vehicle price ₹10,00,000.01, dealer registration ₹1,20,000, dealer insurance ₹30,000 + ₹10,640 + ₹7,315**
1. vp = 100000001 paise > 100000000 → TCS applies.
2. TCS = 100000001 × 100 / 10000 = 1000000.01 paise; `divmod(10000000100, 1000000)` = (10000, 100) → remainder below half → ₹10,000.
3. Cash = 10,00,000.01 + 1,20,000 + 30,000 + 10,640 + 7,315 + 10,000 = **₹11,77,955.01** (117795501 paise). Net = ₹11,67,955.01.

**P007 — ex-showroom ₹10,50,050, discount ₹50,000, same dealer figures as P005**
1. vp = ₹10,00,050 > ₹10 lakh. TCS = 1 % = ₹10,000.50 → half up → **₹10,001**.
2. Cash = 10,50,050 − 50,000 + 1,20,000 + 30,000 + 10,640 + 7,315 + 10,001 = **₹11,78,006**. Net = ₹11,68,005.

**P021 — KA petrol ₹4,99,000, 998 cc, estimates**
1. vp ₹4,99,000 ≤ ₹5 lakh → 13 %: road tax = ₹64,870.00.
2. Cess = 64,870 × 11 % = ₹7,135.70 → ₹7,136; + ₹1,000 fixed = ₹8,136.
3. Fees ₹600 + ₹200. IDV = ₹4,74,050; OD = ₹9,481.00; TP (≤1000 cc) = ₹6,521; GST = 16,002 × 18 % = ₹2,880.36 → ₹2,880.
4. Cash = 4,99,000 + 64,870 + 8,136 + 600 + 200 + 9,481 + 6,521 + 2,880 = **₹5,91,688**. TCS ₹0.

**P029 — RANKING mode: MH petrol ₹8,00,000, discount ₹20,000, dealer registration ₹95,000, insurance ₹15,000 + ₹10,640 + ₹4,615, required mud flaps ₹2,000, FASTag ₹500; optional seat covers ₹12,000, extended warranty ₹15,000, zero-dep ₹4,000 all *selected* but excluded in ranking**
1. vp = ₹7,80,000 → TCS ₹0.
2. Cash (ranking) = 8,00,000 − 20,000 + 95,000 + 15,000 + 10,640 + 4,615 + 500 + 2,000 = **₹9,07,755**. (P030, same input in BUYER_CONFIG, adds 12,000 + 15,000 + 4,000 = ₹9,38,755.)

**P037 — largest allowed discount: ex ₹8,00,000, discount ₹2,00,000 (= ⌊8,00,000 × 25 %⌋), dealer registration ₹66,000, insurance ₹12,000 + ₹10,640 + ₹4,075**
1. Cap = 80000000 × 2500 / 10000 = 20000000 paise = ₹2,00,000 → allowed (P038 with +1 paise is rejected).
2. Cash = 6,00,000 + 66,000 + 12,000 + 10,640 + 4,075 = **₹6,92,715**.

## 8. Not modelled in P0 (documented limits)
Company-owned registrations, BH-series, non-PAN TCS rate, state EV incentives/subsidies, HSRP/number-plate fees (dealer itemises as `FEE:*`), Delhi MCD parking charge, scrappage certificate concessions, loan/hypothecation charges, insurer-specific OD pricing.
