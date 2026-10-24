# ADR-012: Money as integer paise

**Status:** Accepted

## Context
Prices in India are quoted in rupees, but invoices can carry paise. Percentages (taxes, TCS, GST, discounts) produce fractional amounts. JavaScript numbers are IEEE-754 doubles.

## Decision
- All money is an integer number of paise:
  - `bigint` in PostgreSQL;
  - `long` in Java;
  - `number` in TypeScript, checked with `Number.isSafeInteger` (safe up to 2^53−1 paise ≈ ₹90 trillion).
- Rates are integer basis points.
- Every percentage-derived line is rounded half up to the whole rupee at a documented step (pricing-model.md §1).
- No floats anywhere: engines, events, APIs or YAML (the spec YAML loader rejects floats).

## Alternatives considered
- **Decimal types (BigDecimal, decimal.js):** exact and expressive, but heavier in TypeScript, easy to misuse through implicit scales, and transported in JSON as strings.
- **Rupees as integers:** loses the paise that real invoices contain.
- **Floating point, rounded at display:** a classic source of off-by-one-paise errors and drift between languages.

## Consequences
- Display formatting (₹ with lakh/crore grouping) happens only at the edge.
- Every rounding point is named in the spec and covered by vectors (P007, P008, P042, P043, P049).
