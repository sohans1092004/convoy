# W9 fixture, run on the load generator (stdlib only): reads the run's pre-provisioned sessions (tools/load/provision.py,
# s3://<state bucket>/full/<run>/load-sessions.json, fetched by the caller), creates pools across models and cities,
# brings QUOTE_POOLS of them to QUOTE_OPEN the way the journey does (join -> commit -> simulated deposit -> webhook),
# and writes /work/w9-fixture.json for tools/load/w9.k6.js.
#   python3 w9_setup.py <base url> <sessions.json> [pools=200] [quote pools=50]
import json, ssl, sys, time, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor

BASE, SESS = sys.argv[1], json.load(open(sys.argv[2]))
POOLS, QUOTE_POOLS = int(sys.argv[3]) if len(sys.argv) > 3 else 200, int(sys.argv[4]) if len(sys.argv) > 4 else 50
CTX = ssl._create_unverified_context()  # the full profile's self-signed ALB certificate only
buyers = [s['session'] for s in SESS if s['role'] == 'buyer']
dealers = [{'session': s['session'], 'dealership_id': s['dealership_id']} for s in SESS if s['role'] != 'buyer']


def api(method, path, session=None, body=None, idem=False, ok=(200, 201), base=BASE, tries=5):
    h = {'Content-Type': 'application/json'}
    if session: h['X-Convoy-Session'] = session
    if idem: h['Idempotency-Key'] = str(uuid.uuid4())
    for t in range(tries):
        req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, context=CTX, timeout=30) as r:
                return json.loads(r.read() or b'null')
        except urllib.error.HTTPError as e:
            if e.code in ok: return json.loads(e.read() or b'null')
            if e.code not in (429, 502, 503, 504) or t == tries - 1: raise RuntimeError(f'{method} {path} -> {e.code} {e.read()[:300]}')
        except (urllib.error.URLError, TimeoutError):
            if t == tries - 1: raise
        time.sleep(1 + t)


cities = ['Pune', 'Mumbai', 'Bengaluru', 'Delhi', 'Hyderabad', 'Chennai']
models = [m['slug'] for m in api('GET', '/api/catalog/models?city=Pune', buyers[0])['models']]
variants = [v['id'] for slug in models[:10] for v in api('GET', f'/api/catalog/models/{slug}', buyers[0])['variants']]
month = time.strftime('%Y-%m', time.gmtime(time.time() + 45 * 86400))
combos = [(m, c) for m in models for c in cities][:POOLS]


def create(i):
    m, c = combos[i]
    return api('POST', '/api/pools', buyers[i % len(buyers)], {'model_slug': m, 'city': c, 'purchase_month': month}, idem=True)['pool']['id']


with ThreadPoolExecutor(16) as ex:
    pools = list(ex.map(create, range(len(combos))))
print(f'{len(pools)} pools, {len(models)} models, {len(variants)} variants', flush=True)


def to_quote_open(i):  # journey steps: join, commit, simulated deposit (checkout + webhook capture), wait for QUOTE_OPEN
    p, b = pools[i], buyers[(i * 7 + 3) % len(buyers)]
    api('POST', f'/api/pools/{p}/members', b, idem=True, ok=(200, 201, 409))
    terms = api('GET', f'/api/pools/{p}', b)['terms_version']
    dep = api('POST', f'/api/pools/{p}/commitment', b, {'terms_version': terms}, idem=True)['deposit']
    cb = api('POST', '/pay/fake/pay', body={'order_id': dep['razorpay_order_id'], 'outcome': 'success'})
    api('POST', f'/api/payments/{dep["payment_id"]}/checkout-callback', b, cb)
    for _ in range(120):
        if api('GET', f'/api/pools/{p}', b)['state'] == 'QUOTE_OPEN': return p
        time.sleep(2)
    return None


with ThreadPoolExecutor(10) as ex:
    quote = [p for p in ex.map(to_quote_open, range(min(QUOTE_POOLS, len(pools)))) if p]
print(f'{len(quote)}/{QUOTE_POOLS} pools reached QUOTE_OPEN', flush=True)
json.dump({'buyers': buyers, 'dealers': dealers, 'models': models, 'variants': variants, 'cities': cities,
           'pools': pools[len(quote):] or pools, 'quotePools': quote}, open('/work/w9-fixture.json', 'w'))
