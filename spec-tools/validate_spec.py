"""Spec consistency validator (stdlib only). Run: python spec-tools/validate_spec.py
Exit code 1 if any check fails. Each check prints PASS/FAIL with details.
"""
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import gen_event_schemas
import gen_vectors
import miniyaml
import price_ref
import render_machines

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / 'docs'
TRANSITION_FIELDS = ['id', 'from', 'to', 'trigger', 'guard', 'effects', 'emits', 'idempotent_repeat',
                     'late_arrival', 'crash_points', 'timeout', 'detected_by']
CRASH_KEYS = {'before_commit', 'after_commit_before_publish', 'after_publish'}
PLAN_DEPOSIT_ROWS = ['Buyer leaves before CONFIRMATION', 'Pool EXPIRED', 'Pool NO_QUOTES', 'Pool CANCELLED', 'Quote VOID',
                     'Quote WITHDRAWN', 'Stock unavailable', 'Dealer cancels', "Dealer doesn't honour the price",
                     'Buyer misses the confirmation deadline', 'Buyer cancels after confirming',
                     'payment.captured after buyer left', 'payment.captured after pool closed',
                     'Duplicate request', 'Duplicate webhook']
SCREENS = ['discover', 'join', 'compare', 'confirm', 'track', 'quote-builder', 'order-dispute']
STATES8 = ['loading', 'empty', 'error', 'stale', 'pending', 'conflict', 'offline', 'success']

results = []


def check(name):
    def deco(fn):
        errs = []
        try:
            fn(errs)
        except Exception as e:  # a crash in a check is a failure, not a pass
            errs.append(f'{type(e).__name__}: {e}')
        results.append((name, errs))
        return fn
    return deco


def as_list(v):
    return v if isinstance(v, list) else [v]


patterns = miniyaml.load(DOCS / 'rules/state-machines/_patterns.yaml')
problems = miniyaml.load(DOCS / 'architecture/problem-types.yaml')['problems']
catalogue = miniyaml.load(DOCS / 'architecture/event-catalogue.yaml')
events = {}
machines = render_machines.load_machines()
services_md = (DOCS / 'architecture/services.md').read_text(encoding='utf-8')
SERVICES = re.findall(r'^## ([a-z]+)\s*$', services_md, re.M)
authz_md = (DOCS / 'testing/authz-matrix.md').read_text(encoding='utf-8')
api_md = (DOCS / 'architecture/api-outline.md').read_text(encoding='utf-8')


@check('services.md defines the 11 services')
def _(errs):
    want = ['gateway', 'identity', 'catalog', 'pool', 'dealer', 'quote', 'order', 'payment', 'realtime', 'notification', 'audit']
    if sorted(SERVICES) != sorted(want):
        errs.append(f'found {SERVICES}')


@check('state machines: states, fields, crash points, reachability, terminals')
def _(errs):
    for name, m in machines.items():
        states = {s['name'] for s in m['states']}
        terminal = {s['name'] for s in m['states'] if s['terminal']}
        if m['initial'] not in states:
            errs.append(f'{name}: initial {m["initial"]} not a state')
        if m['owner'] not in SERVICES:
            errs.append(f'{name}: owner {m["owner"]} is not a service')
        ids = [t['id'] for t in m['transitions']]
        if len(ids) != len(set(ids)):
            errs.append(f'{name}: duplicate transition ids')
        reach = {m['initial']}
        for t in m['transitions']:
            for f in TRANSITION_FIELDS:
                if f not in t or t[f] in (None, ''):
                    errs.append(f'{name} {t.get("id")}: missing {f}')
            for f in as_list(t['from']):
                if f != '(none)' and f not in states:
                    errs.append(f'{name} {t["id"]}: unknown from-state {f}')
                if f in terminal:
                    errs.append(f'{name} {t["id"]}: transition out of terminal state {f}')
            if t['to'] not in states:
                errs.append(f'{name} {t["id"]}: unknown to-state {t["to"]}')
            if not isinstance(t['effects'], list) or not isinstance(t['emits'], list):
                errs.append(f'{name} {t["id"]}: effects/emits must be lists')
            cp = t['crash_points']
            if isinstance(cp, str):
                if cp not in patterns['crash_patterns']:
                    errs.append(f'{name} {t["id"]}: unknown crash pattern {cp}')
            elif not (isinstance(cp, dict) and set(cp) == CRASH_KEYS):
                errs.append(f'{name} {t["id"]}: crash_points must name a pattern or give {sorted(CRASH_KEYS)}')
            rep = t['idempotent_repeat']
            first = rep.split(';')[0].split(' ')[0]
            if re.fullmatch(r'[A-Z_]+', first) and first not in patterns['repeat_patterns']:
                errs.append(f'{name} {t["id"]}: unknown repeat pattern {first}')
            if t['trigger'].get('kind') not in ('actor', 'system', 'deadline', 'event'):
                errs.append(f'{name} {t["id"]}: bad trigger kind')
        changed = True
        while changed:
            changed = False
            for f, to, _ in render_machines.edges(m):
                if (f == '(none)' or f in reach) and to not in reach:
                    reach.add(to)
                    changed = True
        if states - reach:
            errs.append(f'{name}: unreachable states {sorted(states - reach)}')
        for s in states - terminal:
            if not any(s in as_list(t['from']) for t in m['transitions']):
                errs.append(f'{name}: non-terminal state {s} has no outgoing transition')
        if not terminal:
            errs.append(f'{name}: no terminal state')
        for inv in m['invalid_transitions']:
            if inv['error'] not in problems:
                errs.append(f'{name}: invalid_transitions error {inv["error"]} not in problem-types.yaml')
            for f in as_list(inv['from']):
                if f not in states and f not in ('*', '(none)'):
                    errs.append(f'{name}: invalid_transitions from-state {f} unknown')


@check('state machines README is generated and current')
def _(errs):
    want = render_machines.render(machines)
    have = (DOCS / 'rules/state-machines/README.md').read_text(encoding='utf-8')
    if want != have:
        errs.append('README.md stale: run spec-tools/render_machines.py')
    if have.count('```mermaid') != len(machines):
        errs.append('expected one Mermaid diagram per machine')


@check('event catalogue: one owner, real consumers, audit consumes all, unique names')
def _(errs):
    for e in catalogue['events']:
        if e['name'] in events:
            errs.append(f'duplicate event {e["name"]}')
        events[e['name']] = e
        if not isinstance(e['owner'], str) or e['owner'] not in SERVICES:
            errs.append(f'{e["name"]}: owner must be exactly one service, got {e["owner"]}')
        if not e['consumers']:
            errs.append(f'{e["name"]}: no consumers')
        for c in e['consumers']:
            if c not in SERVICES:
                errs.append(f'{e["name"]}: consumer {c} is not a service')
        if 'audit' not in e['consumers']:
            errs.append(f'{e["name"]}: audit must consume every event')
        if e['fifo'] and e['owner'] != 'pool':
            errs.append(f'{e["name"]}: FIFO outside pool events needs justification in fifo_policy')
        for k, v in e['payload'].items():
            try:
                gen_event_schemas.field_schema(v)
            except Exception:
                errs.append(f'{e["name"]}.{k}: bad type {v}')


@check('event catalogue: one subscription kind per (consumer, aggregate); FULL iff the consumer gets every event of it')
def _(errs):
    total, got = {}, {}
    for e in catalogue['events']:
        total.setdefault(e['aggregate'], set()).add(e['name'])
        for c in e['consumers']:
            got.setdefault((c, e['aggregate']), set()).add(e['name'])
    declared = {}
    for s in catalogue.get('subscriptions') or []:
        k = (s['consumer'], s['aggregate'])
        if k in declared:
            errs.append(f'subscription {k} declared twice')
        declared[k] = s['kind']
        if s['kind'] not in ('FULL', 'PARTIAL', 'EVENTS'):
            errs.append(f'subscription {k}: kind must be FULL, PARTIAL or EVENTS, got {s["kind"]}')
        if s['kind'] == 'EVENTS' and s['consumer'] != 'notification':
            errs.append(f'subscription {k}: EVENTS is for notification only (AMENDMENT-010)')
    for k, names in got.items():
        want = 'FULL' if names == total[k[1]] else 'PARTIAL'
        if k not in declared:
            errs.append(f'subscription {k} missing (should be {want})')
        elif declared[k] != want and declared[k] != 'EVENTS':
            errs.append(f'subscription {k} is {declared[k]} but the consumer receives {len(names)}/{len(total[k[1]])} event types: {want}')
    for k in set(declared) - set(got):
        errs.append(f'subscription {k} declared but {k[0]} consumes no {k[1]} event')


@check('every event emitted by a state machine exists, owned by the machine owner')
def _(errs):
    for name, m in machines.items():
        for t in m['transitions']:
            for ev in t['emits']:
                if ev not in events:
                    errs.append(f'{name} {t["id"]}: event {ev} not in catalogue')
                elif events[ev]['owner'] != m['owner']:
                    errs.append(f'{name} {t["id"]}: {ev} owned by {events[ev]["owner"]}, machine owner {m["owner"]}')


@check('event JSON Schemas are generated and current')
def _(errs):
    want = {k: json.dumps(v, indent=2, ensure_ascii=False) + '\n' for k, v in gen_event_schemas.files().items()}
    have = {p.name: p.read_text(encoding='utf-8') for p in (DOCS / 'architecture/events').glob('*.schema.json')}
    if want != have:
        errs.append('schemas stale: run spec-tools/gen_event_schemas.py')
    for k, v in have.items():
        json.loads(v)


def parse_roles():
    sec = authz_md.split('## Roles', 1)[1].split('##', 1)[0]
    return set(re.findall(r'^\| ([a-z_]+) \|', sec, re.M)) - {'role'}


def parse_named_tests():
    sec = authz_md.split('## Named tests', 1)[1]
    return set(re.findall(r'^\| (AZ-[A-Z0-9-]+) \|', sec, re.M))


def parse_endpoints():
    rows = []
    for line in api_md.splitlines():
        m = re.match(r'^\| (E\d{3}) \| (.*)\|\s*$', line)
        if m:
            cells = [c.strip() for c in m.group(2).split('|')]
            rows.append(dict(zip(['id', 'service', 'method', 'path', 'roles', 'idem', 'ifmatch', 'errors', 'phase', 'tests'], [m.group(1)] + cells)))
    return rows


@check('api-outline: every endpoint maps to a service, known roles, known problem types, authz test IDs')
def _(errs):
    roles = parse_roles()
    named = parse_named_tests()
    rows = parse_endpoints()
    if len(rows) < 50:
        errs.append(f'only {len(rows)} endpoints parsed')
    ids = [r['id'] for r in rows]
    if len(ids) != len(set(ids)):
        errs.append('duplicate endpoint ids')
    for r in rows:
        if r['service'] not in SERVICES:
            errs.append(f'{r["id"]}: unknown service {r["service"]}')
        if r['method'] not in ('GET', 'POST', 'PUT', 'PATCH', 'DELETE'):
            errs.append(f'{r["id"]}: bad method')
        if r['idem'] not in ('yes', 'no') or r['ifmatch'] not in ('yes', 'no'):
            errs.append(f'{r["id"]}: Idem/If-Match must be yes/no')
        rl = [x.strip() for x in r['roles'].split(',')]
        for x in rl:
            ok = x in roles or x == 'authenticated' or (x.startswith('service:') and (x[8:] in SERVICES or x[8:] == 'any'))
            if not ok:
                errs.append(f'{r["id"]}: role {x} not defined in authz-matrix.md')
        if r['errors'] != '-':
            for e in [x.strip() for x in r['errors'].split(',')]:
                if e not in problems:
                    errs.append(f'{r["id"]}: problem type {e} not in problem-types.yaml')
        tests = [x.strip() for x in r['tests'].split(',') if x.strip()]
        if not tests:
            errs.append(f'{r["id"]}: no authz test ids')
        for t in tests:
            m = re.fullmatch(r'AZ-(E\d{3})-([ADX])', t)
            if m:
                if m.group(1) != r['id']:
                    errs.append(f'{r["id"]}: test {t} belongs to another endpoint')
            elif t not in named:
                errs.append(f'{r["id"]}: test {t} not defined in authz-matrix.md named tests')
        if rl != ['anonymous'] and not any(t.endswith('-D') for t in tests):
            errs.append(f'{r["id"]}: protected endpoint lacks a deny test (-D)')
    for t in ['AZ-T1', 'AZ-T2', 'AZ-T3', 'AZ-T4', 'AZ-T5', 'AZ-T6', 'AZ-T7']:
        if t not in named:
            errs.append(f'PLAN §4.8 test {t} missing from authz-matrix.md')
        if t not in api_md:
            errs.append(f'{t} not attached to any endpoint')


@check('vectors: parse, minimum counts, regenerate identically')
def _(errs):
    vdir = ROOT / 'spec-tools/vectors'
    loaded = {p.name: json.loads(p.read_text(encoding='utf-8')) for p in vdir.glob('*.json')}
    for need in ('pricing.json', 'tiers.json', 'deposit-outcomes.json', 'transitions.json'):
        if need not in loaded:
            errs.append(f'missing {need}')
    if len(loaded['pricing.json']['cases']) < 40:
        errs.append('pricing vectors < 40')
    if len(loaded['tiers.json']['cases']) < 25:
        errs.append('tier vectors < 25')
    rows = {c['plan_row'] for c in loaded['deposit-outcomes.json']['cases']}
    for r in PLAN_DEPOSIT_ROWS:
        if r not in rows:
            errs.append(f'deposit vectors miss PLAN §2.3 row: {r}')
    for name, obj in gen_vectors.files().items():
        if gen_vectors.dump(obj) != (vdir / name).read_text(encoding='utf-8'):
            errs.append(f'{name} stale: run spec-tools/gen_vectors.py')


@check('price_ref reproduces every pricing and tier vector exactly (incl. snapshot hash)')
def _(errs):
    rules = price_ref.load_rules()
    pricing = json.loads((ROOT / 'spec-tools/vectors/pricing.json').read_text(encoding='utf-8'))
    for c in pricing['cases']:
        out = price_ref.compute(c['input'], rules)
        if out != c['expected']:
            errs.append(f'{c["id"]}: output differs')
        h = price_ref.sha256({'inputs': c['input'], 'ruleset_version': c['input'].get('ruleset_version'), 'output': out})
        if h != c['snapshot_sha256']:
            errs.append(f'{c["id"]}: snapshot hash differs')
    tiers = json.loads((ROOT / 'spec-tools/vectors/tiers.json').read_text(encoding='utf-8'))
    for c in tiers['cases']:
        i = c['input']
        n = i['confirmed_count'] if 'confirmed_count' in i else price_ref.count_confirmed(i['confirmations'], i['confirmation_deadline'])
        out = price_ref.resolve_tiers(i['tiers'], i['min_fulfilment'], n, i.get('max_units'))
        exp = {k: v for k, v in c['expected'].items() if k in out}
        if out != exp:
            errs.append(f'{c["id"]}: tier resolution differs')
    hv = sum(1 for k in gen_vectors.HAND_VERIFIED)
    if hv < 5:
        errs.append('fewer than 5 hand-verified pricing vectors')
    pm = (DOCS / 'rules/pricing-model.md').read_text(encoding='utf-8')
    for k in gen_vectors.HAND_VERIFIED:
        if f'**{k}' not in pm:
            errs.append(f'hand-verified {k} not worked in pricing-model.md')


@check('data values carry a source or an UNVERIFIED/ASSUMPTION label')
def _(errs):
    rto = miniyaml.load(DOCS / 'data/rules/rto.yaml')
    for code, st in rto['states'].items():
        if not st.get('sources'):
            errs.append(f'rto {code}: no sources')
        for fuel in st['slabs']:
            if fuel not in st['fuel_evidence']:
                errs.append(f'rto {code}.{fuel}: no evidence label')
    for k in ('registration_fee', 'smart_card_fee'):
        if f'{k}_evidence' not in rto['common']:
            errs.append(f'rto common {k}: no evidence label')
    tcs = miniyaml.load(DOCS / 'data/rules/tcs.yaml')
    if not tcs.get('sources') or tcs.get('status') not in ('VERIFIED', 'UNVERIFIED'):
        errs.append('tcs.yaml: missing sources/status')
    ins = miniyaml.load(DOCS / 'data/rules/insurance-baseline.yaml')
    for k in ('idv_depreciation', 'od_rate', 'gst', 'tp'):
        if f'{k}_evidence' not in ins:
            errs.append(f'insurance {k}: no evidence label')
    cat = miniyaml.load(DOCS / 'data/catalogue/models.yaml')
    models = [m for b in cat['brands'] for m in b['models']]
    if len(models) < 25 or len(cat['brands']) < 8:
        errs.append(f'catalogue too small: {len(cat["brands"])} brands, {len(models)} models')
    for m in models:
        src = m.get('source') or {}
        if not src.get('url') or not src.get('accessed'):
            errs.append(f'{m["id"]}: source url/accessed missing')
        for v in m['variants']:
            if v['status'] == 'VERIFIED' and not isinstance(v['price_paise'], int):
                errs.append(f'{v["id"]}: VERIFIED without integer price')
            if v['status'] == 'UNVERIFIED' and v['price_paise'] is not None:
                errs.append(f'{v["id"]}: UNVERIFIED must have null price')
            if v['status'] not in ('VERIFIED', 'UNVERIFIED'):
                errs.append(f'{v["id"]}: bad status')
        for k in ('starting_price', 'price_range'):
            if m.get(k) and m[k].get('status') not in ('VERIFIED', 'UNVERIFIED'):
                errs.append(f'{m["id"]}.{k}: missing status')


@check('wireframes: 7 screens x 8 states x 2 widths, linked from index, shared CSS')
def _(errs):
    wdir = ROOT / 'design/wireframes'
    index = (wdir / 'index.html').read_text(encoding='utf-8')
    for s in SCREENS:
        p = wdir / f'{s}.html'
        if not p.exists():
            errs.append(f'missing {s}.html')
            continue
        html = p.read_text(encoding='utf-8')
        if f'href="{s}.html"' not in index:
            errs.append(f'{s}.html not linked from index')
        if 'href="wireframe.css"' not in html or '<style' in html or '<script' in html:
            errs.append(f'{s}.html must use only the shared CSS and no scripts')
        for st in STATES8:
            if f'id="s-{st}"' not in html or f'st-{st}' not in html:
                errs.append(f'{s}.html: state {st} missing')
        if 'id="w-360"' not in html or 'id="w-desktop"' not in html:
            errs.append(f'{s}.html: width switcher missing')
        if 'lang="en-IN"' not in html or 'class="skip"' not in html:
            errs.append(f'{s}.html: lang or skip link missing')


@check('every Q-### referenced exists in OPEN_QUESTIONS.md')
def _(errs):
    oq = (DOCS / 'OPEN_QUESTIONS.md').read_text(encoding='utf-8')
    defined = set(re.findall(r'^### (Q-\d{3})', oq, re.M))
    used = set()
    for p in list(DOCS.rglob('*.md')) + list(DOCS.rglob('*.yaml')) + [ROOT / 'SPEC.md']:
        if p.name != 'OPEN_QUESTIONS.md':
            used |= set(re.findall(r'Q-\d{3}', p.read_text(encoding='utf-8')))
    for q in sorted(used - defined):
        errs.append(f'{q} referenced but not defined')
    if not defined:
        errs.append('no questions defined')


@check('relative links in markdown files resolve')
def _(errs):
    for p in [ROOT / 'SPEC.md'] + list(DOCS.rglob('*.md')) + list((ROOT / 'reports').glob('*.md')):
        if not p.exists():
            errs.append(f'missing {p.relative_to(ROOT)}')
            continue
        for target in re.findall(r'\]\(([^)#\s]+)(?:#[^)]*)?\)', p.read_text(encoding='utf-8')):
            if re.match(r'^[a-z]+:', target):
                continue
            if not (p.parent / target).resolve().exists():
                errs.append(f'{p.relative_to(ROOT)} -> {target}')


if __name__ == '__main__':
    failed = 0
    for name, errs in results:
        print(('PASS ' if not errs else 'FAIL ') + name)
        for e in errs[:25]:
            print('     - ' + e)
        if len(errs) > 25:
            print(f'     ... {len(errs) - 25} more')
        failed += bool(errs)
    print(f'\n{len(results) - failed}/{len(results)} checks passed')
    sys.exit(1 if failed else 0)
