"""Write spec-tools/vectors/*.json (stdlib only).

python spec-tools/gen_vectors.py          # (re)write vectors
python spec-tools/gen_vectors.py --check  # exit 1 if any vector file is stale

Pricing inputs are SYNTHETIC test inputs (some reuse catalogue prices for realism); they test the
arithmetic, not market facts. HAND_VERIFIED holds totals computed by hand in pricing-model.md; the
generator refuses to write if the engine disagrees with any of them.
"""
import copy
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import price_ref
import render_machines

OUT = pathlib.Path(__file__).resolve().parent / 'vectors'
RULES = price_ref.load_rules()
RV = RULES['ruleset']['ruleset_version']
L = 100 * 100000  # one lakh rupees in paise
R = 100           # one rupee in paise
DC, CE, UV = 'DEALER_CONFIRMED', 'CATALOGUE_ESTIMATE', 'UNVERIFIED'


def base(state='MH', fuel='PETROL', ex=None, cc=1197, disc=0, ex_ev=DC):
    return {'ruleset_version': RV, 'mode': 'BUYER_CONFIG', 'state': state,
            'vehicle': {'variant_id': 'test-variant', 'fuel': fuel, 'engine_cc': cc,
                        'ex_showroom_paise': ex, 'ex_showroom_evidence': ex_ev},
            'discount': {'amount_paise': disc, 'base': 'EX_SHOWROOM', 'evidence': DC},
            'registration': None, 'insurance': None, 'insurance_addons': [], 'fees': [],
            'accessories': [], 'extended_warranty': None, 'other_charges': []}


def dealer_reg(i, amount):
    i['registration'] = {'amount_paise': amount, 'evidence': DC}
    return i


def dealer_ins(i, od, tp, gst):
    i['insurance'] = {'od_paise': od, 'tp_paise': tp, 'gst_paise': gst, 'evidence': DC}
    return i


def full_dealer(ex, disc=0, state='MH', fuel='PETROL', cc=1197):
    return dealer_ins(dealer_reg(base(state, fuel, ex, cc, disc), 120000 * R), 30000 * R, 10640 * R, 7315 * R)


def with_(i, **kw):
    i = copy.deepcopy(i)
    for k, v in kw.items():
        i[k] = v
    return i


acc_req = {'code': 'MUDFLAPS', 'label': 'Mud flaps', 'amount_paise': 2000 * R, 'required': True, 'selected': True, 'evidence': DC}
acc_opt = {'code': 'SEAT_COVERS', 'label': 'Seat covers', 'amount_paise': 12000 * R, 'required': False, 'selected': True, 'evidence': DC}
acc_opt_unsel = dict(acc_opt, selected=False)
warranty = {'amount_paise': 15000 * R, 'selected': True, 'evidence': DC}
zero_dep = {'code': 'ZERO_DEP', 'label': 'Zero depreciation', 'amount_paise': 4000 * R, 'selected': True, 'evidence': DC}
fastag = {'code': 'FASTAG', 'label': 'FASTag', 'amount_paise': 500 * R, 'basis': 'Issuer fee + security deposit as charged by dealer', 'evidence': DC}
handling = {'code': 'HANDLING', 'label': 'Handling charges', 'amount_paise': 5000 * R, 'evidence': DC}

BASELINE_COVER = {'idv_bp': 9500, 'addons': []}
ranking_cfg = dealer_ins(dealer_reg(base('MH', 'PETROL', 8 * L, 1197, 20000 * R), 95000 * R), 15000 * R, 10640 * R, 4615 * R)
ranking_cfg['insurance'].update(declared_cover=BASELINE_COVER, binding=True)
ranking_cfg.update(accessories=[acc_req, acc_opt], extended_warranty=warranty, insurance_addons=[zero_dep], fees=[fastag])

PRICING = [
    ('P001', 'MH petrol < Rs 10 lakh, zero discount, registration + insurance estimated (Swift LXi price)', base(ex=583900 * R, ex_ev=CE)),
    ('P002', 'MH petrol with discount, dealer-confirmed registration and insurance', full_dealer(7 * L, 25000 * R)),
    ('P003', 'TCS just below threshold: vehicle price Rs 9,99,999', full_dealer(1049999 * R, 50000 * R)),
    ('P004', 'TCS at threshold: vehicle price exactly Rs 10,00,000.00 (not exceeding)', full_dealer(10 * L)),
    ('P005', 'TCS just above threshold: Rs 10,00,000.01', full_dealer(10 * L + 1)),
    ('P006', 'TCS one rupee above threshold: Rs 10,00,001', full_dealer(10 * L + R)),
    ('P007', 'TCS rounding half up: 1% of Rs 10,00,050 = Rs 10,000.50 -> Rs 10,001', full_dealer(1050050 * R, 50000 * R)),
    ('P008', 'TCS rounding down: 1% of Rs 10,00,049 = Rs 10,000.49 -> Rs 10,000', full_dealer(1000049 * R)),
    ('P009', 'MH petrol > Rs 20 lakh: 13% road tax + TCS, estimated insurance (>1500 cc)', base(ex=25 * L, cc=1998)),
    ('P010', 'MH diesel Rs 10-20 lakh slab (14%)', base(fuel='DIESEL', ex=1252800 * R, cc=1493)),
    ('P011', 'MH CNG: estimate computed but labelled UNVERIFIED (conflicting sources)', base(fuel='CNG', ex=757400 * R)),
    ('P012', 'MH EV with dealer insurance: 0% road tax', dealer_ins(base(fuel='EV', ex=1484800 * R, cc=None), 25000 * R, 9000 * R, 6120 * R)),
    ('P013', 'MH EV without insurance figure: engine refuses (EV TP unverified)', base(fuel='EV', ex=1484800 * R, cc=None)),
    ('P014', 'MH hybrid without dealer registration: rule unverified -> error', base(fuel='HYBRID', ex=890000 * R, cc=1462)),
    ('P015', 'MH hybrid with dealer-confirmed registration', dealer_reg(base(fuel='HYBRID', ex=890000 * R, cc=1462), 98000 * R)),
    ('P016', 'DL petrol <= Rs 6 lakh (4%)', base('DL', ex=583900 * R)),
    ('P017', 'DL petrol exactly Rs 6,00,000 (inclusive upper bound -> 4%)', base('DL', ex=6 * L)),
    ('P018', 'DL petrol Rs 6,00,001 (7%)', base('DL', ex=6 * L + R)),
    ('P019', 'DL diesel > Rs 10 lakh (12.5%) + TCS', base('DL', 'DIESEL', 1473200 * R, 1493)),
    ('P020', 'DL CNG: rule unverified -> error', base('DL', 'CNG', 818100 * R)),
    ('P021', 'KA petrol <= Rs 5 lakh (13%) + 11% cess + Rs 1,000 cess, <= 1000 cc', base('KA', ex=499000 * R, cc=998)),
    ('P022', 'KA exactly Rs 10,00,000 (inclusive -> 14%), no TCS', base('KA', ex=10 * L)),
    ('P023', 'KA Rs 10,00,001 (17%) and TCS', base('KA', ex=10 * L + R)),
    ('P024', 'KA > Rs 20 lakh (18%)', base('KA', ex=2156900 * R, cc=1497)),
    ('P025', 'KA EV Rs 14,84,800 -> 8% slab (Rs 10-25 lakh), dealer insurance', dealer_ins(base('KA', 'EV', 1484800 * R, None), 25000 * R, 9000 * R, 6120 * R)),
    ('P026', 'Dealer-required accessory included and flagged', with_(full_dealer(7 * L), accessories=[acc_req])),
    ('P027', 'Optional accessory not selected: listed, excluded from cash payable', with_(full_dealer(7 * L), accessories=[acc_opt_unsel])),
    ('P028', 'Optional accessory selected: included', with_(full_dealer(7 * L), accessories=[acc_opt])),
    ('P029', 'RANKING mode: optional items excluded even if selected; required kept', with_(ranking_cfg, mode='RANKING')),
    ('P030', 'Same configuration in BUYER_CONFIG mode (optional selected items included)', ranking_cfg),
    ('P031', 'Extended warranty listed but not selected', with_(full_dealer(7 * L), extended_warranty=dict(warranty, selected=False))),
    ('P032', 'Questionable HANDLING charge flagged and included', with_(full_dealer(7 * L), other_charges=[handling])),
    ('P033', 'FASTag fee itemised', with_(full_dealer(7 * L), fees=[fastag])),
    ('P034', 'Quote missing ex-showroom -> error', base(ex=None)),
    ('P035', 'Missing insurance and engine cc -> error', base(ex=7 * L, cc=None)),
    ('P036', 'Missing insurance component filled by catalogue estimate (warning)', dealer_reg(base(ex=7 * L, cc=1197), 80000 * R)),
    ('P037', 'Largest discount allowed: exactly 25% of ex-showroom', dealer_ins(dealer_reg(base(ex=8 * L, disc=2 * L), 66000 * R), 12000 * R, 10640 * R, 4075 * R)),
    ('P038', 'Discount 1 paise above the 25% limit -> error', full_dealer(8 * L, 2 * L + 1)),
    ('P039', 'Negative discount -> error', full_dealer(8 * L, -1)),
    ('P040', 'Unsupported discount base -> error', with_(full_dealer(8 * L), discount={'amount_paise': 1000 * R, 'base': 'ACCESSORIES', 'evidence': DC})),
    ('P041', 'Unknown state -> error', base('TN', ex=7 * L)),
    ('P042', 'Road-tax rounding half up: 11% of Rs 5,00,050 = Rs 55,005.50 -> Rs 55,006', dealer_ins(base(ex=500050 * R), 1 * R, 1 * R, 0)),
    ('P043', 'Road-tax rounding down: 11% of Rs 5,00,040 = Rs 55,004.40 -> Rs 55,004', dealer_ins(base(ex=500040 * R), 1 * R, 1 * R, 0)),
    ('P044', 'TP band boundary: exactly 1000 cc -> lowest band', dealer_reg(base(ex=6 * L, cc=1000), 70000 * R)),
    ('P045', 'TP band boundary: 1001 cc -> 1000-1500 band', dealer_reg(base(ex=6 * L, cc=1001), 70000 * R)),
    ('P046', 'Ruleset version mismatch -> error', with_(full_dealer(7 * L), ruleset_version='2025.01.01-old')),
    ('P047', 'UNVERIFIED ex-showroom weakens computed lines (road tax, TCS)', base(ex=1214000 * R, cc=1451, ex_ev=UV)),
    ('P048', 'MH one-time tax cap Rs 30 lakh applied', dealer_ins(base(ex=250 * L, cc=2998), 500000 * R, 24596 * R, 94427 * R)),
    ('P049', 'KA cess rounding on an odd tax amount', dealer_ins(base('KA', ex=777777 * R), 1 * R, 1 * R, 0)),
    ('P050', 'Full configuration: discount, estimates, required+optional accessories, warranty, add-on, fees, questionable charge, TCS', with_(base('KA', ex=1449700 * R, cc=1497, disc=40000 * R), accessories=[acc_req, acc_opt, acc_opt_unsel], extended_warranty=warranty, insurance_addons=[zero_dep], fees=[fastag], other_charges=[handling])),
    ('P051', 'Zero discount stated explicitly with dealer figures (Creta E MT price)', full_dealer(1090700 * R, 0)),
    ('P052', 'RANKING mode, dealer insurance not marked binding -> baseline estimate used (AMENDMENT-004)', with_(ranking_cfg, mode='RANKING', insurance=dict(ranking_cfg['insurance'], binding=False))),
    ('P053', 'RANKING mode, binding dealer insurance with a different cover (IDV 100 %, zero-dep) -> baseline estimate used (AMENDMENT-004)', with_(ranking_cfg, mode='RANKING', insurance=dict(ranking_cfg['insurance'], declared_cover={'idv_bp': 10000, 'addons': ['ZERO_DEP']}))),
    ('P054', 'BUYER_CONFIG mode, non-binding dealer insurance is still shown to the buyer as the dealer figure', with_(ranking_cfg, insurance=dict(ranking_cfg['insurance'], binding=False))),
]

# Hand-verified in docs/rules/pricing-model.md §Hand-verified vectors (cash payable, TCS) in paise.
HAND_VERIFIED = {
    'P001': (67457500, 0),
    'P005': (117795501, 1000000),
    'P007': (117800600, 1000100),
    'P021': (59168800, 0),
    'P029': (90775500, 0),
    'P037': (69271500, 0),
}


def gen_pricing():
    cases = []
    for cid, desc, inp in PRICING:
        out = price_ref.compute(inp, RULES)
        cases.append({'id': cid, 'description': desc, 'input': inp, 'expected': out,
                      'snapshot_sha256': price_ref.sha256({'inputs': inp, 'ruleset_version': inp.get('ruleset_version'), 'output': out})})
        if cid in HAND_VERIFIED:
            want = HAND_VERIFIED[cid]
            got = (out.get('cash_payable_paise'), out.get('tcs_paise'))
            if got != want:
                sys.exit(f'{cid}: engine {got} != hand-verified {want}')
    return {'ruleset_version': RV, 'note': 'Synthetic inputs. expected = price_ref.compute(input); snapshot_sha256 = sha256(canonical({inputs, ruleset_version, output})).', 'cases': cases}



LADDER = [{'min': 1, 'max': 4, 'price_paise': 900000 * R}, {'min': 5, 'max': 9, 'price_paise': 855000 * R}, {'min': 10, 'max': None, 'price_paise': 840000 * R}]
SINGLE = [{'min': 1, 'max': None, 'price_paise': 900000 * R}]
DL = '2026-11-20T12:30:00Z'  # confirmation deadline (18:00 IST)


def conf(n, confirmed_at='2026-11-19T10:00:00Z', captured='2026-11-01T09:00:00Z', cancelled=None):
    return [{'confirmed_at': confirmed_at, 'deposit_captured_at': captured, 'cancelled_at': cancelled} for _ in range(n)]


TIERS = [
    ('T01', 'Exact lower boundary of tier 2 (5)', LADDER, 1, 5),
    ('T02', 'Exact upper boundary of tier 1 (4)', LADDER, 1, 4),
    ('T03', 'Exact upper boundary of tier 2 (9)', LADDER, 1, 9),
    ('T04', 'Exact lower boundary of unbounded tier (10)', LADDER, 1, 10),
    ('T05', 'Single confirmed buyer, min 1', LADDER, 1, 1),
    ('T06', 'Far above last tier start (100)', LADDER, 1, 100),
    ('T07', 'Confirmed below minimum fulfilment -> VOID', LADDER, 5, 4),
    ('T08', 'Confirmed exactly at minimum; ceiling = tier containing minimum', LADDER, 5, 5),
    ('T09', 'Minimum 5, confirmed 12: price falls below ceiling', LADDER, 5, 12),
    ('T10', 'Zero confirmed, min 1 -> VOID', LADDER, 1, 0),
    ('T11', 'Zero confirmed, min 5 -> VOID', LADDER, 5, 0),
    ('T12', 'Single tier, 1 confirmed', SINGLE, 1, 1),
    ('T13', 'Single tier, 0 confirmed -> VOID', SINGLE, 1, 0),
    ('T14', 'Single tier, min 3, 2 confirmed -> VOID', SINGLE, 3, 2),
    ('T15', 'Single tier, min 3, 3 confirmed', SINGLE, 3, 3),
    ('T16', 'Gap between tiers (1-4, 6+) -> INVALID at submission', [LADDER[0], {'min': 6, 'max': None, 'price_paise': 855000 * R}], 1, 5),
    ('T17', 'Overlapping tiers -> INVALID', [LADDER[0], {'min': 4, 'max': None, 'price_paise': 855000 * R}], 1, 5),
    ('T18', 'Price rises with more buyers -> INVALID ("price can only fall")', [LADDER[0], {'min': 5, 'max': None, 'price_paise': 905000 * R}], 1, 5),
    ('T19', 'Last tier bounded -> INVALID', [LADDER[0], {'min': 5, 'max': 9, 'price_paise': 855000 * R}], 1, 5),
    ('T20', 'Minimum fulfilment 0 -> INVALID', LADDER, 0, 5),
    ('T21', 'Minimum fulfilment below first tier start -> INVALID', [{'min': 3, 'max': None, 'price_paise': 900000 * R}], 1, 5),
    ('T22', 'Equal prices across tiers allowed; resolved = ceiling', [{'min': 1, 'max': 4, 'price_paise': 900000 * R}, {'min': 5, 'max': None, 'price_paise': 900000 * R}], 1, 7),
    ('T29', 'Minimum 10: ceiling is the unbounded tier', LADDER, 10, 10),
]

TIER_CONFIRMATION_CASES = [
    ('T23', 'Every buyer cancels AFTER the deadline: count fixed at deadline (7), price unchanged; 7 bookings later cancelled with refunds BUYER_CANCELLED',
     LADDER, 1, conf(7, cancelled='2026-11-21T05:00:00Z'), None),
    ('T24', 'Two of six cancel BEFORE the deadline: count 4', LADDER, 1, conf(4) + conf(2, cancelled='2026-11-20T10:00:00Z'), None),
    ('T25', 'Confirmation committed exactly at the deadline is not counted (now() < deadline)', LADDER, 5, conf(4) + conf(1, confirmed_at=DL), None),
    ('T26', 'Confirmation without captured deposit is not counted', LADDER, 5, conf(4) + conf(1, captured=None), None),
    ('T27', 'max_units 5 with minimum 3, 5 confirmed', LADDER, 3, conf(5), 5),
    ('T28', 'max_units below minimum fulfilment -> INVALID', LADDER, 5, conf(3), 3),
    ('T30', 'All buyers cancel before the deadline: count 0 -> VOID', LADDER, 1, conf(3, cancelled='2026-11-20T11:00:00Z'), None),
]


def gen_tiers():
    cases = []
    for cid, desc, tiers, mf, n in TIERS:
        cases.append({'id': cid, 'description': desc, 'input': {'tiers': tiers, 'min_fulfilment': mf, 'confirmed_count': n, 'max_units': None},
                      'expected': price_ref.resolve_tiers(tiers, mf, n)})
    for cid, desc, tiers, mf, confs, mu in TIER_CONFIRMATION_CASES:
        n = price_ref.count_confirmed(confs, DL)
        exp = price_ref.resolve_tiers(tiers, mf, n, mu)
        if cid == 'T23':
            exp = dict(exp, post_deadline_cancellations=7, price_after_cancellations_paise=exp['resolved_price_paise'],
                       per_booking_refund_reason='BUYER_CANCELLED')
        cases.append({'id': cid, 'description': desc,
                      'input': {'tiers': tiers, 'min_fulfilment': mf, 'confirmation_deadline': DL, 'confirmations': confs, 'max_units': mu},
                      'expected': exp})
    cases.sort(key=lambda c: c['id'])
    return {'note': 'price_paise per tier = engine cash payable for the buyer configuration at that tier discount (tier-resolution.md).', 'cases': cases}


AMT = 99900
CAP = {'entry': 'J-CAPTURE', 'lines': [{'account': '1100', 'dr': AMT, 'cr': 0}, {'account': '2100', 'dr': 0, 'cr': AMT}]}
HOLD = {'entry': 'J-HOLD', 'lines': [{'account': '2100', 'dr': AMT, 'cr': 0}, {'account': '2200', 'dr': 0, 'cr': AMT}]}
RELEASE = {'entry': 'J-RELEASE', 'lines': [{'account': '2200', 'dr': AMT, 'cr': 0}, {'account': '1100', 'dr': 0, 'cr': AMT}]}
REF_HELD = {'entry': 'J-REFUND', 'lines': [{'account': '2100', 'dr': AMT, 'cr': 0}, {'account': '1100', 'dr': 0, 'cr': AMT}]}
REF_BOOKING = {'entry': 'J-REFUND', 'lines': [{'account': '2200', 'dr': AMT, 'cr': 0}, {'account': '1100', 'dr': 0, 'cr': AMT}]}
REF_MANUAL = {'entry': 'J-REFUND-MANUAL', 'lines': [{'account': '2100', 'dr': AMT, 'cr': 0}, {'account': '1200', 'dr': 0, 'cr': AMT}]}


def dep(cid, plan_row, situation, events, payment, refund, ledger, side=None):
    return {'id': cid, 'plan_row': plan_row, 'situation': situation, 'events': events,
            'expected': {'payment_state': payment, 'refund': refund, 'ledger': ledger, 'side_effects': side or []}}


def refunded(reason, kind='REFUND'):
    return {'state': 'REFUNDED', 'kind': kind, 'reason_code': reason}


CAPTURED = ['commit', 'webhook payment.captured']
VOID_ALL = CAPTURED + ['accept', 'confirm', 'TierResolved(VOID)', 'BookingsIssued(bookings_count = 0)']
DEPOSITS = [
    dep('D01', 'Buyer leaves before CONFIRMATION', 'Committed buyer leaves during QUOTE_OPEN', CAPTURED + ['MemberLeft', 'webhook refund.processed'], 'REFUNDED', refunded('BUYER_LEFT'), [CAP, REF_HELD]),
    dep('D02', 'Buyer leaves before CONFIRMATION', 'Committed buyer accepts nothing by decision deadline', CAPTURED + ['AcceptancesFrozen(released)', 'webhook refund.processed'], 'REFUNDED', refunded('NO_ACCEPTANCE'), [CAP, REF_HELD]),
    dep('D03', 'Pool EXPIRED', 'Minimum not reached by forming deadline', CAPTURED + ['PoolClosed(EXPIRED)', 'webhook refund.processed'], 'REFUNDED', refunded('POOL_EXPIRED'), [CAP, REF_HELD]),
    dep('D04', 'Pool NO_QUOTES', 'No valid quote at sealed deadline', CAPTURED + ['PoolClosed(NO_QUOTES)', 'webhook refund.processed'], 'REFUNDED', refunded('POOL_NO_QUOTES'), [CAP, REF_HELD]),
    dep('D05', 'Pool CANCELLED', 'Admin cancels pool with reason', CAPTURED + ['PoolClosed(CANCELLED)', 'webhook refund.processed'], 'REFUNDED', refunded('POOL_CANCELLED'), [CAP, REF_HELD]),
    dep('D06', 'Quote VOID', 'Quote VOID while another quote in the pool resolves (bookings_count >= 1): no re-round, refunded now', CAPTURED + ['accept', 'confirm', 'TierResolved(VOID)', 'BookingsIssued(bookings_count >= 1)', 'webhook refund.processed'], 'REFUNDED', refunded('QUOTE_VOID'), [CAP, REF_HELD], ['buyer notified with reason', 'offered similar pools']),
    dep('D07', 'Quote WITHDRAWN', 'Dealer cancels FINAL quote during CONFIRMATION', CAPTURED + ['accept', 'QuoteWithdrawn(after_final)', 'webhook refund.processed'], 'REFUNDED', refunded('DEALER_CANCELLED'), [CAP, REF_HELD], ['dispute auto-opened', 'dealer reliability decremented']),
    dep('D08', 'Stock unavailable', 'Stock reservation fails after booking issued', CAPTURED + ['accept', 'confirm', 'TierResolved(RESOLVED)', 'DepositHoldRequested', 'StockUnavailable', 'webhook refund.processed'], 'REFUNDED', refunded('STOCK_UNAVAILABLE'), [CAP, HOLD, REF_BOOKING], ['booking CANCELLED', 'dispute auto-opened', 'dealer reliability decremented']),
    dep('D09', 'Dealer cancels', 'Dealer cancels a confirmed booking', CAPTURED + ['accept', 'confirm', 'TierResolved(RESOLVED)', 'DepositHoldRequested', 'BookingCancelled(DEALER_CANCELLED)', 'webhook refund.processed'], 'REFUNDED', refunded('DEALER_CANCELLED'), [CAP, HOLD, REF_BOOKING], ['dispute auto-opened', 'dealer reliability decremented']),
    dep('D10', "Dealer doesn't honour the price", 'Dealer does not confirm the booking within 48 h', CAPTURED + ['accept', 'confirm', 'TierResolved(RESOLVED)', 'DepositHoldRequested', 'dealer_confirm_deadline passes', 'webhook refund.processed'], 'REFUNDED', refunded('DEALER_NOT_HONOURED'), [CAP, HOLD, REF_BOOKING], ['booking CANCELLED', 'dispute auto-opened', 'admin review']),
    dep('D11', 'Buyer misses the confirmation deadline', 'No confirmation before deadline', CAPTURED + ['accept', 'confirmation_deadline passes', 'webhook refund.processed'], 'REFUNDED', refunded('MISSED_CONFIRMATION_DEADLINE'), [CAP, REF_HELD]),
    dep('D12', 'Buyer misses the confirmation deadline', 'Buyer explicitly declines', CAPTURED + ['accept', 'decline', 'webhook refund.processed'], 'REFUNDED', refunded('BUYER_DECLINED'), [CAP, REF_HELD]),
    dep('D13', 'Buyer cancels after confirming', 'Cancel before tier resolution', CAPTURED + ['accept', 'confirm', 'cancel', 'webhook refund.processed'], 'REFUNDED', refunded('BUYER_CANCELLED'), [CAP, REF_HELD], ['recorded as buyer cancellation; tier unaffected if after deadline']),
    dep('D14', 'Buyer cancels after confirming', 'Cancel after booking issued', CAPTURED + ['accept', 'confirm', 'TierResolved(RESOLVED)', 'DepositHoldRequested', 'BookingCancelled(BUYER_CANCELLED)', 'webhook refund.processed'], 'REFUNDED', refunded('BUYER_CANCELLED'), [CAP, HOLD, REF_BOOKING], ['other buyers price unchanged']),
    dep('D15', 'payment.captured after buyer left', 'Buyer leaves while COMMIT_PENDING; capture arrives later', ['commit', 'MemberLeft', 'webhook payment.captured', 'webhook refund.processed'], 'REFUNDED', refunded('LATE_CAPTURE'), [CAP, REF_HELD]),
    dep('D16', 'payment.captured after pool closed', 'Pool EXPIRED while COMMIT_PENDING; capture arrives later', ['commit', 'PoolClosed(EXPIRED)', 'webhook payment.captured', 'webhook refund.processed'], 'REFUNDED', refunded('LATE_CAPTURE'), [CAP, REF_HELD]),
    dep('D17', 'Duplicate request', 'Double-click commit with the same Idempotency-Key', ['commit', 'commit (same key)', 'webhook payment.captured'], 'CAPTURED', None, [CAP], ['one Razorpay order', 'one membership', 'committed_count +1']),
    dep('D18', 'Duplicate webhook', 'payment.captured delivered twice (same event id)', ['commit', 'webhook payment.captured', 'webhook payment.captured (same event id)'], 'CAPTURED', None, [CAP], ['second webhook 200, no effect, duplicate counter +1']),
    dep('D19', 'Duplicate webhook', 'refund.processed delivered twice', CAPTURED + ['MemberLeft', 'webhook refund.processed', 'webhook refund.processed (same event id)'], 'REFUNDED', refunded('BUYER_LEFT'), [CAP, REF_HELD]),
    dep('D20', '(happy path)', 'Booking delivered: deposit released', CAPTURED + ['accept', 'confirm', 'TierResolved(RESOLVED)', 'DepositHoldRequested', 'BookingDelivered', 'webhook refund.processed'], 'RELEASED', refunded('DELIVERY_RELEASE', 'RELEASE'), [CAP, HOLD, RELEASE], ['deposit returned in full after delivery (AMENDMENT-003)', 'success fee is a separate dealer receivable (J-FEE 1300/4100, only if success_fee_paise is configured, Q-041)']),
    dep('D21', '(refund failure)', 'Refund fails 5 times, admin resolves manually', CAPTURED + ['MemberLeft', 'webhook refund.failed x5', 'admin resolve-manually'], 'REFUNDED', {'state': 'REFUNDED', 'reason_code': 'BUYER_LEFT', 'manual': True}, [CAP, REF_MANUAL], ['alarm REFUND_STUCK', 'admin queue entry', 'resolution needs approval by a second, different admin (AMENDMENT-001)']),
    dep('D22', '(failed then retried)', 'First attempt fails, retry in checkout succeeds', ['commit', 'webhook payment.failed', 'webhook payment.captured'], 'CAPTURED', None, [CAP], ['membership COMMITTED (MEM-T03 from INTERESTED)']),
    dep('D23', '(abandoned)', 'Checkout never completed within 24 h', ['commit', '24 h pass'], 'VOIDED_UNPAID', None, [], ['membership back to INTERESTED']),
    dep('D24', 'Quote VOID, re-round (AMENDMENT-002)', 'Every quote VOID, pool eligible for re-round; buyer keeps the commitment', VOID_ALL + ['PoolReopened', 'reround-choice KEEP'], 'CAPTURED', None, [CAP], ['membership COMMITTED for round_set 2', 'no new payment', 'committed_count +1']),
    dep('D25', 'Quote VOID, re-round (AMENDMENT-002)', 'Every quote VOID, pool eligible for re-round; buyer asks for a refund', VOID_ALL + ['PoolReopened', 'reround-choice REFUND', 'MemberReleased(REROUND_REFUND_REQUESTED)', 'webhook refund.processed'], 'REFUNDED', refunded('REROUND_REFUND_REQUESTED'), [CAP, REF_HELD]),
    dep('D26', 'Quote VOID, re-round (AMENDMENT-002)', 'Every quote VOID, pool eligible for re-round; buyer does not respond within 48 h', VOID_ALL + ['PoolReopened', 'choice_deadline passes', 'MemberReleased(REROUND_NO_RESPONSE)', 'webhook refund.processed'], 'REFUNDED', refunded('REROUND_NO_RESPONSE'), [CAP, REF_HELD], ['no response means refund']),
    dep('D27', 'Quote VOID, no re-round', 'Every quote VOID, pool not eligible (re-round used or purchase window too short)', VOID_ALL + ['PoolClosed(EXPIRED, ALL_QUOTES_VOID)', 'webhook refund.processed'], 'REFUNDED', refunded('QUOTE_VOID'), [CAP, REF_HELD], ['automatic refund per PLAN §2.2']),
]


def gen_deposits():
    for d in DEPOSITS:
        for e in d['expected']['ledger']:
            assert sum(l['dr'] for l in e['lines']) == sum(l['cr'] for l in e['lines']), d['id']
    return {'deposit_amount_paise': AMT, 'accounts': {'1100': 'Razorpay clearing (asset)', '1200': 'Platform bank (asset)',
            '1300': 'Success fees receivable from dealers (asset)', '2100': 'Customer deposits held (liability)',
            '2200': 'Deposits held for bookings (liability)', '4100': 'Success fee revenue (income)'}, 'cases': DEPOSITS}


def gen_transitions():
    out = {}
    for name, m in render_machines.load_machines().items():
        out[name] = {'owner': m['owner'], 'initial': m['initial'],
                     'terminal': [s['name'] for s in m['states'] if s['terminal']],
                     'valid': [{'id': t['id'], 'from': f, 'to': to, 'trigger': t['trigger']['kind'], 'emits': t['emits']}
                               for f, to, t in render_machines.edges(m)],
                     'invalid_pairs': [{'from': f, 'to': to, 'error': 'invalid-transition'} for f, to in render_machines.invalid_pairs(m)],
                     'invalid_commands': m['invalid_transitions']}
    return out


def files():
    return {'pricing.json': gen_pricing(), 'tiers.json': gen_tiers(), 'deposit-outcomes.json': gen_deposits(), 'transitions.json': gen_transitions()}


def dump(obj):
    return json.dumps(obj, indent=1, ensure_ascii=False, sort_keys=True) + '\n'


if __name__ == '__main__':
    OUT.mkdir(exist_ok=True)
    stale = []
    for name, obj in files().items():
        p = OUT / name
        text = dump(obj)
        if '--check' in sys.argv:
            if not p.exists() or p.read_text(encoding='utf-8') != text:
                stale.append(name)
        else:
            p.write_text(text, encoding='utf-8', newline='\n')
            n = len(obj['cases']) if 'cases' in obj else sum(len(v['valid']) for v in obj.values())
            print(f'wrote vectors/{name} ({n} cases/transitions)')
    if stale:
        sys.exit('stale vectors: ' + ', '.join(stale))
