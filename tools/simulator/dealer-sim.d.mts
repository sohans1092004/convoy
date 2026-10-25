// Types for dealer-sim.mjs (used by the TypeScript journey).
export interface SimDealer { email: string; dealership_id: string; strategy: string; max_discount_bp: number; addon_price_paise?: number; inventory: { variant_code: string; colour: string; quantity: number; delivery_days: number }[] }
export interface RankView { quote_id: string; ranks: { variant_id: string; rank: number; of_n: number; rule_version: string; computed_at: string }[] }
export interface SimResult { dealer: string; quoteId?: string; skipped?: string; error?: unknown; log: { step: string; status: number }[]; ranks?: (RankView & { when: string })[]; floorDiscount?: number }
export const DEALERS: SimDealer[];
export function rng(seed: number): () => number;
export function plan(dealer: SimDealer, variants: unknown[], seed: number, now?: number): unknown;
export function runDealer(dealer: SimDealer, poolId: string, opts?: { seed?: number; gateway?: string; mailpit?: string; timeoutMs?: number }): Promise<SimResult>;
