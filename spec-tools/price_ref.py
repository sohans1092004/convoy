"""Convoy reference price engine (stdlib only). Implements docs/rules/pricing-model.md and
docs/rules/tier-resolution.md exactly. The Java and TypeScript engines must reproduce
spec-tools/vectors/pricing.json and tiers.json byte for byte (canonical JSON).

All money is integer paise. All rates are integer basis points. No floats anywhere.
"""
import hashlib
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import miniyaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
RULES_DIR = ROOT / 'docs' / 'data' / 'rules'

RANK = {'DEALER_CONFIRMED': 2, 'CATALOGUE_ESTIMATE': 1, 'UNVERIFIED': 0}
# rule-file evidence -> engine evidence (None = refuse to estimate)
RULE_EVIDENCE = {'VERIFIED': 'DEALER_CONFIRMED', 'SECONDARY': 'CATALOGUE_ESTIMATE', 'ASSUMPTION': 'CATALOGUE_ESTIMATE',
                 'CONFLICTING': 'UNVERIFIED', 'UNVERIFIED': None}


def load_rules():
    rules = {n: miniyaml.load(RULES_DIR / f'{n}.yaml') for n in ('ruleset', 'tcs', 'rto', 'insurance-baseline')}
    versions = {r['ruleset_version'] for r in rules.values()}
    if len(versions) != 1:
        raise ValueError(f'rule files disagree on ruleset_version: {versions}')
    return rules


def canonical(obj):
    """Canonical JSON (RFC 8785-equivalent for our data: ints, strings, bools, null, ASCII keys)."""
    return json.dumps(obj, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def sha256(obj):
    return hashlib.sha256(canonical(obj).encode('utf-8')).hexdigest()


def pct(amount_paise, bp):
    """amount x bp / 10000, rounded half up to the whole rupee, returned in paise. amount >= 0."""
    rupees, rem = divmod(amount_paise * bp, 1_000_000)
    if rem * 2 >= 1_000_000:
        rupees += 1
    return rupees * 100


def weakest(*levels):
    return min(levels, key=lambda e: RANK[e])


def _slab(slabs, base):
    for s in slabs:
        if s['upto_paise'] is None or base <= s['upto_paise']:
            return s['rate_bp']
    raise ValueError('slab table has no unbounded last slab')


def _line(code, amount, evidence, in_cash=True, flags=()):
    return {'code': code, 'amount_paise': amount, 'evidence': evidence, 'in_cash_payable': in_cash, 'flags': sorted(flags)}


def compute(inp, rules=None):
    """Return the engine output for one calculation input (see pricing-model.md §Inputs)."""
    rules = rules or load_rules()
    rs, tcs, rto, ins = rules['ruleset'], rules['tcs'], rules['rto'], rules['insurance-baseline']
    errors, warnings, lines = [], [], []
    ranking = inp.get('mode') == 'RANKING'

    def amount_ok(v, code):
        if not isinstance(v, int) or isinstance(v, bool) or v < 0:
            errors.append(f'NEGATIVE_OR_INVALID_AMOUNT:{code}')
            return False
        return True

    def evidence_ok(e, code):
        if e not in RANK:
            errors.append(f'INVALID_EVIDENCE:{code}')
            return False
        return True

    if inp.get('ruleset_version') != rs['ruleset_version']:
        errors.append('RULESET_VERSION_MISMATCH')
    veh = inp.get('vehicle') or {}
    ex = veh.get('ex_showroom_paise')
    if ex is None:
        errors.append('EX_SHOWROOM_MISSING')
    elif amount_ok(ex, 'EX_SHOWROOM') and ex == 0:
        errors.append('EX_SHOWROOM_MISSING')
    ex_ev = veh.get('ex_showroom_evidence')
    evidence_ok(ex_ev, 'EX_SHOWROOM')
    fuel = veh.get('fuel')
    if fuel not in rs['fuels']:
        errors.append('UNKNOWN_FUEL')
    state = inp.get('state')
    if state not in rto['states']:
        errors.append('UNKNOWN_STATE')

    disc = inp.get('discount') or {'amount_paise': 0, 'base': 'EX_SHOWROOM', 'evidence': 'DEALER_CONFIRMED'}
    d = disc.get('amount_paise')
    if disc.get('base') not in rs['discount_bases']:
        errors.append('DISCOUNT_BASE_UNSUPPORTED')
    amount_ok(d, 'DISCOUNT')
    evidence_ok(disc.get('evidence'), 'DISCOUNT')
    if errors:
        return {'status': 'INVALID', 'errors': sorted(set(errors))}
    max_d = ex * rs['max_discount_bp'] // 10000
    if d > max_d:
        return {'status': 'INVALID', 'errors': ['DISCOUNT_EXCEEDS_LIMIT']}

    vehicle_price = ex - d
    vp_ev = weakest(ex_ev, disc['evidence'])
    lines.append(_line('EX_SHOWROOM', ex, ex_ev))
    lines.append(_line('DISCOUNT', -d, disc['evidence']))

    # Registration / RTO
    reg = inp.get('registration')
    if reg is not None:
        if amount_ok(reg.get('amount_paise'), 'REGISTRATION') and evidence_ok(reg.get('evidence'), 'REGISTRATION'):
            lines.append(_line('REGISTRATION', reg['amount_paise'], reg['evidence']))
    else:
        st = rto['states'][state]
        rule_ev = RULE_EVIDENCE[st['fuel_evidence'].get(fuel, 'UNVERIFIED')]
        if rule_ev is None or fuel not in st['slabs']:
            errors.append('REGISTRATION_ESTIMATE_UNAVAILABLE')
        else:
            base_ev = weakest(rule_ev, vp_ev, 'CATALOGUE_ESTIMATE')
            tax = pct(vehicle_price, _slab(st['slabs'][fuel], vehicle_price))
            if st.get('max_tax_paise') is not None:
                tax = min(tax, st['max_tax_paise'])
            lines.append(_line('ROAD_TAX', tax, base_ev))
            if st.get('cess_on_tax_bp'):
                lines.append(_line('ROAD_TAX_CESS', pct(tax, st['cess_on_tax_bp']) + st.get('fixed_cess_paise', 0), base_ev))
            c = rto['common']
            lines.append(_line('REGISTRATION_FEE', c['registration_fee_paise'], weakest(RULE_EVIDENCE[c['registration_fee_evidence']], 'CATALOGUE_ESTIMATE')))
            lines.append(_line('SMART_CARD_FEE', c['smart_card_fee_paise'], RULE_EVIDENCE[c['smart_card_fee_evidence']] or 'UNVERIFIED'))
            warnings.append('REGISTRATION_ESTIMATED')

    # Insurance baseline (comprehensive yr-1 OD + 3-yr TP)
    pol = inp.get('insurance')
    if pol is not None:
        for k, code in (('od_paise', 'INSURANCE_OD'), ('tp_paise', 'INSURANCE_TP'), ('gst_paise', 'INSURANCE_GST')):
            if amount_ok(pol.get(k), code) and evidence_ok(pol.get('evidence'), code):
                lines.append(_line(code, pol[k], pol['evidence']))
    else:
        cc = veh.get('engine_cc')
        if fuel == 'EV':
            errors.append('INSURANCE_ESTIMATE_UNAVAILABLE')
        elif not isinstance(cc, int) or cc <= 0:
            errors.append('ENGINE_CC_MISSING')
        else:
            ev = weakest('CATALOGUE_ESTIMATE', ex_ev)
            idv = pct(ex, 10000 - ins['idv_depreciation_bp'])
            od = pct(idv, ins['od_rate_bp'])
            tp = next(b['premium_paise'] for b in ins['tp_3yr_by_engine_cc'] if b['max_cc'] is None or cc <= b['max_cc'])
            lines += [_line('INSURANCE_OD', od, ev), _line('INSURANCE_TP', tp, ev),
                      _line('INSURANCE_GST', pct(od + tp, ins['gst_bp']), ev)]
            warnings.append('INSURANCE_ESTIMATED')

    for a in inp.get('insurance_addons', []):
        if amount_ok(a.get('amount_paise'), a.get('code')) and evidence_ok(a.get('evidence'), a.get('code')):
            lines.append(_line(f'INSURANCE_ADDON:{a["code"]}', a['amount_paise'], a['evidence'],
                               in_cash=a.get('selected', False) and not ranking, flags=['OPTIONAL']))
    for f in inp.get('fees', []):
        if amount_ok(f.get('amount_paise'), f.get('code')) and evidence_ok(f.get('evidence'), f.get('code')):
            lines.append(_line(f'FEE:{f["code"]}', f['amount_paise'], f['evidence']))
    for a in inp.get('accessories', []):
        if amount_ok(a.get('amount_paise'), a.get('code')) and evidence_ok(a.get('evidence'), a.get('code')):
            if a.get('required'):
                lines.append(_line(f'ACCESSORY:{a["code"]}', a['amount_paise'], a['evidence'], flags=['DEALER_REQUIRED']))
                warnings.append(f'REQUIRED_ACCESSORY:{a["code"]}')
            else:
                lines.append(_line(f'ACCESSORY:{a["code"]}', a['amount_paise'], a['evidence'],
                                   in_cash=a.get('selected', False) and not ranking, flags=['OPTIONAL']))
    w = inp.get('extended_warranty')
    if w is not None and amount_ok(w.get('amount_paise'), 'EXTENDED_WARRANTY') and evidence_ok(w.get('evidence'), 'EXTENDED_WARRANTY'):
        lines.append(_line('EXTENDED_WARRANTY', w['amount_paise'], w['evidence'],
                           in_cash=w.get('selected', False) and not ranking, flags=['OPTIONAL']))
    for c in inp.get('other_charges', []):
        if amount_ok(c.get('amount_paise'), c.get('code')) and evidence_ok(c.get('evidence'), c.get('code')):
            q = c['code'] in rs['questionable_charge_codes']
            lines.append(_line(f'CHARGE:{c["code"]}', c['amount_paise'], c['evidence'], flags=['QUESTIONABLE'] if q else []))
            if q:
                warnings.append(f'QUESTIONABLE_CHARGE:{c["code"]}')

    if errors:
        return {'status': 'INVALID', 'errors': sorted(set(errors))}

    # TCS: 1 % of the full vehicle consideration when it EXCEEDS the threshold
    tcs_amt = pct(vehicle_price, tcs['rate_bp']) if vehicle_price > tcs['threshold_paise'] else 0
    lines.append(_line('TCS', tcs_amt, vp_ev, flags=['CREDITABLE'] if tcs_amt else []))
    if tcs_amt:
        warnings.append('TCS_APPLIES')

    cash = sum(l['amount_paise'] for l in lines if l['in_cash_payable'])
    return {'status': 'OK',
            'vehicle_price_paise': vehicle_price,
            'lines': lines,
            'cash_payable_paise': cash,
            'tcs_paise': tcs_amt,
            'estimated_net_cost_paise': cash - tcs_amt,
            'warnings': sorted(set(warnings))}


def snapshot(inp, computed_at, rules=None):
    """Stored calculation snapshot. Hash covers inputs + ruleset_version + output (not the timestamp)."""
    out = compute(inp, rules)
    body = {'inputs': inp, 'ruleset_version': inp.get('ruleset_version'), 'output': out}
    return dict(body, computed_at=computed_at, sha256=sha256(body))


# ---------------------------------------------------------------- tiers (tier-resolution.md)

def validate_tiers(tiers, min_fulfilment, max_units=None):
    errs = []
    if not isinstance(min_fulfilment, int) or min_fulfilment < 1:
        errs.append('MIN_FULFILMENT_INVALID')
    if not tiers:
        return errs + ['TIERS_EMPTY']
    for i, t in enumerate(tiers):
        if t['min'] < 1 or (t['max'] is not None and t['max'] < t['min']):
            errs.append(f'TIER_BOUNDS_INVALID:{i}')
        if i and tiers[i - 1]['max'] is None:
            errs.append('TIER_AFTER_UNBOUNDED')
        if i and tiers[i - 1]['max'] is not None and t['min'] > tiers[i - 1]['max'] + 1:
            errs.append(f'TIER_GAP:{i}')
        if i and tiers[i - 1]['max'] is not None and t['min'] <= tiers[i - 1]['max']:
            errs.append(f'TIER_OVERLAP:{i}')
        if i and t['price_paise'] > tiers[i - 1]['price_paise']:
            errs.append(f'TIER_PRICE_INCREASES:{i}')
    if tiers[-1]['max'] is not None:
        errs.append('LAST_TIER_MUST_BE_UNBOUNDED')
    if isinstance(min_fulfilment, int) and min_fulfilment >= 1 and not errs:
        if min_fulfilment < tiers[0]['min']:
            errs.append('MIN_FULFILMENT_BELOW_FIRST_TIER')
    if max_units is not None and isinstance(min_fulfilment, int) and max_units < min_fulfilment:
        errs.append('MAX_UNITS_BELOW_MIN_FULFILMENT')
    return errs


def tier_for(tiers, n):
    for i, t in enumerate(tiers):
        if t['min'] <= n and (t['max'] is None or n <= t['max']):
            return i
    return None


def count_confirmed(confirmations, deadline):
    """Tier count (PLAN §2.2.4): confirmation and deposit capture both committed strictly before the
    deadline, and not cancelled before it. Timestamps are UTC ISO-8601 strings of identical format."""
    return sum(1 for c in confirmations
               if c['confirmed_at'] < deadline
               and c.get('deposit_captured_at') is not None and c['deposit_captured_at'] < deadline
               and (c.get('cancelled_at') is None or c['cancelled_at'] >= deadline))


def resolve_tiers(tiers, min_fulfilment, confirmed_count, max_units=None):
    """Return ceiling and resolution for one quote (variant). tiers: [{min, max|None, price_paise}]."""
    errs = validate_tiers(tiers, min_fulfilment, max_units)
    if errs:
        return {'status': 'INVALID', 'errors': errs}
    ci = tier_for(tiers, min_fulfilment)
    out = {'status': 'OK', 'ceiling_tier': ci, 'ceiling_price_paise': tiers[ci]['price_paise'],
           'confirmed_count': confirmed_count}
    if confirmed_count < min_fulfilment:
        out.update(outcome='VOID', resolved_tier=None, resolved_price_paise=None, refund_reason='QUOTE_VOID')
    else:
        ri = tier_for(tiers, confirmed_count)
        out.update(outcome='RESOLVED', resolved_tier=ri, resolved_price_paise=tiers[ri]['price_paise'], refund_reason=None)
        assert out['resolved_price_paise'] <= out['ceiling_price_paise']
    return out


if __name__ == '__main__':
    assert pct(100000050, 100) == 1000000  # 1 % of Rs 10,00,000.50 = Rs 10,000.005 -> Rs 10,000
    assert pct(5, 10000) == 0 and pct(50, 10000) == 100  # half-up at the rupee
    t = [{'min': 1, 'max': 4, 'price_paise': 300}, {'min': 5, 'max': None, 'price_paise': 200}]
    assert resolve_tiers(t, 5, 7)['resolved_price_paise'] == 200
    assert resolve_tiers(t, 5, 4)['outcome'] == 'VOID'
    print('price_ref self-check ok, ruleset', load_rules()['ruleset']['ruleset_version'])
