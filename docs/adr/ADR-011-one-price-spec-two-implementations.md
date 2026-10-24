# ADR-011: One price spec, Java + TypeScript implementations, shared vectors

**Status:** Accepted (PLAN §5.3, §7)

## Context
The quote builder must show a live price summary in the browser, while the services compute the authoritative price. Two codebases computing money independently will drift unless they are forced to agree.

## Decision
- `pricing-model.md` and `tier-resolution.md` are the spec.
- `spec-tools/price_ref.py` is the executable reference.
- `spec-tools/vectors/*.json` are the contract.
- A Java library (used by catalog, quote and order) and a TypeScript package (used by web) must each reproduce every vector exactly in CI: both the expected object and the snapshot hash.
- The server (Java) result is authoritative. The browser result is a preview, checked against E074 before submit.

## Alternatives considered
- **Compute on the server only, with an API call per keystroke:** a single implementation, but it adds latency and load on every edit and degrades the UX offline or on slow networks.
- **One implementation compiled to both platforms** (Kotlin Multiplatform; WASM from Rust or Java; or GraalJS running the TS engine in the JVM): removes drift by construction, but adds a toolchain and debugging complexity, and still needs the vectors.
- **Rules as data, interpreted on both sides (a JSON rules engine):** flexible, but the interpreter itself becomes two implementations.

## Consequences
- Adding a rule requires:
  1. a spec change (after P0, through an ADR or amendment);
  2. an update to the reference engine;
  3. new vectors;
  4. both implementations passing.
- Vectors use synthetic inputs: they verify the arithmetic, not market facts.
