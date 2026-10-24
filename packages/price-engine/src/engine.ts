/**
 * Convoy price engine (TypeScript). A line-for-line port of spec-tools/price_ref.py
 * (docs/rules/pricing-model.md, docs/rules/tier-resolution.md). It must reproduce
 * spec-tools/vectors/pricing.json and tiers.json byte for byte (ADR-011).
 *
 * Money is integer paise, rates are integer basis points. No floats: every arithmetic
 * result is checked to stay a safe integer.
 *
 * The web uses this engine for previews only; stored prices always come from the server.
 */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = any;
export type Rules = Record<'ruleset' | 'tcs' | 'rto' | 'insurance-baseline', Json>;

const RANK: Record<string, number> = { DEALER_CONFIRMED: 2, CATALOGUE_ESTIMATE: 1, UNVERIFIED: 0 };
const RULE_EVIDENCE: Record<string, string | null> = {
  VERIFIED: 'DEALER_CONFIRMED', SECONDARY: 'CATALOGUE_ESTIMATE', ASSUMPTION: 'CATALOGUE_ESTIMATE',
  CONFLICTING: 'UNVERIFIED', UNVERIFIED: null,
};

function safe(n: number): number {
  if (!Number.isSafeInteger(n)) throw new RangeError(`unsafe integer ${n}`);
  return n;
}

/** amount x bp / 10000, rounded half up to the whole rupee, returned in paise. amount >= 0. */
export function pct(amountPaise: number, bp: number): number {
  const prod = safe(amountPaise * bp);
  let rupees = Math.floor(prod / 1_000_000);
  const rem = prod - rupees * 1_000_000;
  if (rem * 2 >= 1_000_000) rupees += 1;
  return rupees * 100;
}

function weakest(...levels: string[]): string {
  return levels.reduce((a, b) => (RANK[b] < RANK[a] ? b : a));
}

function slab(slabs: Json[], base: number): number {
  for (const s of slabs) if (s.upto_paise === null || base <= s.upto_paise) return s.rate_bp;
  throw new Error('slab table has no unbounded last slab');
}

function line(code: string, amount: number, evidence: string, inCash = true, flags: string[] = []) {
  return { code, amount_paise: amount, evidence, in_cash_payable: inCash, flags: [...flags].sort() };
}

const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const sortedSet = (xs: string[]) => [...new Set(xs)].sort();

export function rankableInsurance(pol: Json, ins: Json): boolean {
  const cover = pol.declared_cover || {};
  return pol.binding === true && Array.isArray(cover.addons) && cover.addons.length === 0
    && cover.idv_bp === 10000 - ins.idv_depreciation_bp;
}

export function compute(inp: Json, rules: Rules): Json {
  const rs = rules.ruleset, tcs = rules.tcs, rto = rules.rto, ins = rules['insurance-baseline'];
  const errors: string[] = [], warnings: string[] = [];
  const lines: ReturnType<typeof line>[] = [];
  const ranking = inp.mode === 'RANKING';

  const amountOk = (v: unknown, code: string): v is number => {
    if (!isInt(v) || v < 0) { errors.push(`NEGATIVE_OR_INVALID_AMOUNT:${code}`); return false; }
    return true;
  };
  const evidenceOk = (e: unknown, code: string) => {
    if (typeof e !== 'string' || !(e in RANK)) { errors.push(`INVALID_EVIDENCE:${code}`); return false; }
    return true;
  };

  if (inp.ruleset_version !== rs.ruleset_version) errors.push('RULESET_VERSION_MISMATCH');
  const veh = inp.vehicle || {};
  const ex = veh.ex_showroom_paise;
  if (ex === undefined || ex === null) errors.push('EX_SHOWROOM_MISSING');
  else if (amountOk(ex, 'EX_SHOWROOM') && ex === 0) errors.push('EX_SHOWROOM_MISSING');
  const exEv = veh.ex_showroom_evidence;
  evidenceOk(exEv, 'EX_SHOWROOM');
  const fuel = veh.fuel;
  if (!rs.fuels.includes(fuel)) errors.push('UNKNOWN_FUEL');
  const state = inp.state;
  if (typeof state !== 'string' || !(state in rto.states)) errors.push('UNKNOWN_STATE');

  const disc = inp.discount || { amount_paise: 0, base: 'EX_SHOWROOM', evidence: 'DEALER_CONFIRMED' };
  const d = disc.amount_paise;
  if (!rs.discount_bases.includes(disc.base)) errors.push('DISCOUNT_BASE_UNSUPPORTED');
  amountOk(d, 'DISCOUNT');
  evidenceOk(disc.evidence, 'DISCOUNT');
  if (errors.length) return { status: 'INVALID', errors: sortedSet(errors) };
  const maxD = Math.floor(safe(ex * rs.max_discount_bp) / 10000);
  if (d > maxD) return { status: 'INVALID', errors: ['DISCOUNT_EXCEEDS_LIMIT'] };

  const vehiclePrice = ex - d;
  const vpEv = weakest(exEv, disc.evidence);
  lines.push(line('EX_SHOWROOM', ex, exEv));
  lines.push(line('DISCOUNT', -d, disc.evidence));

  // Registration / RTO
  const reg = inp.registration;
  if (reg !== undefined && reg !== null) {
    if (amountOk(reg.amount_paise, 'REGISTRATION') && evidenceOk(reg.evidence, 'REGISTRATION')) {
      lines.push(line('REGISTRATION', reg.amount_paise, reg.evidence));
    }
  } else {
    const st = rto.states[state];
    const ruleEv = RULE_EVIDENCE[st.fuel_evidence[fuel] ?? 'UNVERIFIED'];
    if (ruleEv === null || !(fuel in st.slabs)) {
      errors.push('REGISTRATION_ESTIMATE_UNAVAILABLE');
    } else {
      const baseEv = weakest(ruleEv, vpEv, 'CATALOGUE_ESTIMATE');
      let tax = pct(vehiclePrice, slab(st.slabs[fuel], vehiclePrice));
      if (st.max_tax_paise !== undefined && st.max_tax_paise !== null) tax = Math.min(tax, st.max_tax_paise);
      lines.push(line('ROAD_TAX', tax, baseEv));
      if (st.cess_on_tax_bp) lines.push(line('ROAD_TAX_CESS', pct(tax, st.cess_on_tax_bp) + (st.fixed_cess_paise ?? 0), baseEv));
      const c = rto.common;
      lines.push(line('REGISTRATION_FEE', c.registration_fee_paise, weakest(RULE_EVIDENCE[c.registration_fee_evidence]!, 'CATALOGUE_ESTIMATE')));
      lines.push(line('SMART_CARD_FEE', c.smart_card_fee_paise, RULE_EVIDENCE[c.smart_card_fee_evidence] || 'UNVERIFIED'));
      warnings.push('REGISTRATION_ESTIMATED');
    }
  }

  // Insurance baseline (comprehensive yr-1 OD + 3-yr TP)
  let pol = inp.insurance;
  if (pol !== undefined && pol !== null && ranking && !rankableInsurance(pol, ins)) {
    // AMENDMENT-004: ranking uses the dealer's figure only for binding, exactly-baseline cover.
    warnings.push('DEALER_INSURANCE_NOT_RANKABLE');
    pol = null;
  }
  if (pol !== undefined && pol !== null) {
    for (const [k, code] of [['od_paise', 'INSURANCE_OD'], ['tp_paise', 'INSURANCE_TP'], ['gst_paise', 'INSURANCE_GST']]) {
      if (amountOk(pol[k], code) && evidenceOk(pol.evidence, code)) lines.push(line(code, pol[k], pol.evidence));
    }
  } else {
    const cc = veh.engine_cc;
    if (fuel === 'EV') errors.push('INSURANCE_ESTIMATE_UNAVAILABLE');
    else if (!isInt(cc) || cc <= 0) errors.push('ENGINE_CC_MISSING');
    else {
      const ev = weakest('CATALOGUE_ESTIMATE', exEv);
      const idv = pct(ex, 10000 - ins.idv_depreciation_bp);
      const od = pct(idv, ins.od_rate_bp);
      const tp = ins.tp_3yr_by_engine_cc.find((b: Json) => b.max_cc === null || cc <= b.max_cc).premium_paise;
      lines.push(line('INSURANCE_OD', od, ev), line('INSURANCE_TP', tp, ev), line('INSURANCE_GST', pct(od + tp, ins.gst_bp), ev));
      warnings.push('INSURANCE_ESTIMATED');
    }
  }

  for (const a of inp.insurance_addons ?? []) {
    if (amountOk(a.amount_paise, a.code) && evidenceOk(a.evidence, a.code)) {
      lines.push(line(`INSURANCE_ADDON:${a.code}`, a.amount_paise, a.evidence, (a.selected ?? false) && !ranking, ['OPTIONAL']));
    }
  }
  for (const f of inp.fees ?? []) {
    if (amountOk(f.amount_paise, f.code) && evidenceOk(f.evidence, f.code)) lines.push(line(`FEE:${f.code}`, f.amount_paise, f.evidence));
  }
  for (const a of inp.accessories ?? []) {
    if (amountOk(a.amount_paise, a.code) && evidenceOk(a.evidence, a.code)) {
      if (a.required) {
        lines.push(line(`ACCESSORY:${a.code}`, a.amount_paise, a.evidence, true, ['DEALER_REQUIRED']));
        warnings.push(`REQUIRED_ACCESSORY:${a.code}`);
      } else {
        lines.push(line(`ACCESSORY:${a.code}`, a.amount_paise, a.evidence, (a.selected ?? false) && !ranking, ['OPTIONAL']));
      }
    }
  }
  const w = inp.extended_warranty;
  if (w !== undefined && w !== null && amountOk(w.amount_paise, 'EXTENDED_WARRANTY') && evidenceOk(w.evidence, 'EXTENDED_WARRANTY')) {
    lines.push(line('EXTENDED_WARRANTY', w.amount_paise, w.evidence, (w.selected ?? false) && !ranking, ['OPTIONAL']));
  }
  for (const c of inp.other_charges ?? []) {
    if (amountOk(c.amount_paise, c.code) && evidenceOk(c.evidence, c.code)) {
      const q = rs.questionable_charge_codes.includes(c.code);
      lines.push(line(`CHARGE:${c.code}`, c.amount_paise, c.evidence, true, q ? ['QUESTIONABLE'] : []));
      if (q) warnings.push(`QUESTIONABLE_CHARGE:${c.code}`);
    }
  }

  if (errors.length) return { status: 'INVALID', errors: sortedSet(errors) };

  // TCS: 1 % of the full vehicle consideration when it EXCEEDS the threshold
  const tcsAmt = vehiclePrice > tcs.threshold_paise ? pct(vehiclePrice, tcs.rate_bp) : 0;
  lines.push(line('TCS', tcsAmt, vpEv, true, tcsAmt ? ['CREDITABLE'] : []));
  if (tcsAmt) warnings.push('TCS_APPLIES');

  const cash = safe(lines.filter((l) => l.in_cash_payable).reduce((s, l) => s + l.amount_paise, 0));
  return {
    status: 'OK', vehicle_price_paise: vehiclePrice, lines, cash_payable_paise: cash, tcs_paise: tcsAmt,
    estimated_net_cost_paise: cash - tcsAmt, warnings: sortedSet(warnings),
  };
}

// ---------------------------------------------------------------- tiers (tier-resolution.md)
export interface Tier { min: number; max: number | null; price_paise: number }

export function validateTiers(tiers: Tier[], minFulfilment: unknown, maxUnits: number | null = null): string[] {
  const errs: string[] = [];
  if (!isInt(minFulfilment) || minFulfilment < 1) errs.push('MIN_FULFILMENT_INVALID');
  if (!tiers.length) return [...errs, 'TIERS_EMPTY'];
  tiers.forEach((t, i) => {
    if (t.min < 1 || (t.max !== null && t.max < t.min)) errs.push(`TIER_BOUNDS_INVALID:${i}`);
    if (i && tiers[i - 1].max === null) errs.push('TIER_AFTER_UNBOUNDED');
    if (i && tiers[i - 1].max !== null && t.min > tiers[i - 1].max! + 1) errs.push(`TIER_GAP:${i}`);
    if (i && tiers[i - 1].max !== null && t.min <= tiers[i - 1].max!) errs.push(`TIER_OVERLAP:${i}`);
    if (i && t.price_paise > tiers[i - 1].price_paise) errs.push(`TIER_PRICE_INCREASES:${i}`);
  });
  if (tiers[tiers.length - 1].max !== null) errs.push('LAST_TIER_MUST_BE_UNBOUNDED');
  if (isInt(minFulfilment) && minFulfilment >= 1 && !errs.length && minFulfilment < tiers[0].min) {
    errs.push('MIN_FULFILMENT_BELOW_FIRST_TIER');
  }
  if (maxUnits !== null && isInt(minFulfilment) && maxUnits < minFulfilment) errs.push('MAX_UNITS_BELOW_MIN_FULFILMENT');
  return errs;
}

export function tierFor(tiers: Tier[], n: number): number | null {
  const i = tiers.findIndex((t) => t.min <= n && (t.max === null || n <= t.max));
  return i < 0 ? null : i;
}

export interface Confirmation { confirmed_at: string; deposit_captured_at: string | null; cancelled_at: string | null }

/** Tier count: confirmation and capture committed strictly before the deadline, not cancelled before it. */
export function countConfirmed(confirmations: Confirmation[], deadline: string): number {
  return confirmations.filter((c) => c.confirmed_at < deadline
    && c.deposit_captured_at != null && c.deposit_captured_at < deadline
    && (c.cancelled_at == null || c.cancelled_at >= deadline)).length;
}

export function resolveTiers(tiers: Tier[], minFulfilment: number, confirmedCount: number, maxUnits: number | null = null): Json {
  const errs = validateTiers(tiers, minFulfilment, maxUnits);
  if (errs.length) return { status: 'INVALID', errors: errs };
  const ci = tierFor(tiers, minFulfilment)!;
  const out: Json = { status: 'OK', ceiling_tier: ci, ceiling_price_paise: tiers[ci].price_paise, confirmed_count: confirmedCount };
  if (confirmedCount < minFulfilment) {
    Object.assign(out, { outcome: 'VOID', resolved_tier: null, resolved_price_paise: null, refund_reason: 'QUOTE_VOID' });
  } else {
    const ri = tierFor(tiers, confirmedCount)!;
    Object.assign(out, { outcome: 'RESOLVED', resolved_tier: ri, resolved_price_paise: tiers[ri].price_paise, refund_reason: null });
    if (out.resolved_price_paise > out.ceiling_price_paise) throw new Error('price rose above ceiling');
  }
  return out;
}

/** Canonical JSON: sorted keys, no whitespace (matches price_ref.canonical). */
export function canonical(v: Json): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v);
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`;
  return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}`;
}
