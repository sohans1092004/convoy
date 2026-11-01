# W4 fixture, run on the load generator (stdlib only): 50 pools brought to QUOTE_OPEN at the same time (parallel, so the
# sealed-round deadlines land within seconds of each other), then every load-test dealer staff reads its inbox: the
# fixture is one row per (pool, dealer) with that pool's deadline and a variant to quote. Writes /work/w4-fixture.json.
#   python3 w4_setup.py <base url> <sessions.json> [pools=50]
import json, sys, time
from concurrent.futures import ThreadPoolExecutor

import w9_setup_lib as L  # shared HTTP helper and journey steps

BASE, SESS = sys.argv[1], json.load(open(sys.argv[2]))
POOLS = int(sys.argv[3]) if len(sys.argv) > 3 else 50
buyers = [s['session'] for s in SESS if s['role'] == 'buyer']
dealers = [s for s in SESS if s['role'] != 'buyer']
api = L.make_api(BASE)
models = [m['slug'] for m in api('GET', '/api/catalog/models?city=Pune', buyers[0])['models'] if m['brand'] == 'Maruti Suzuki']
months = [time.strftime('%Y-%m', time.gmtime(time.time() + d * 86400)) for d in (40, 70, 100, 130, 160, 190)]
keys = [(m, mo) for mo in months for m in models][:POOLS]
with ThreadPoolExecutor(16) as ex:
    pools = list(ex.map(lambda i: api('POST', '/api/pools', buyers[i], {'model_slug': keys[i][0], 'city': 'Pune', 'purchase_month': keys[i][1]}, idem=True)['pool']['id'], range(len(keys))))
with ThreadPoolExecutor(len(pools)) as ex:  # all at once: the deadlines should coincide
    opened = [p for p in ex.map(lambda i: L.to_quote_open(api, pools[i], buyers[len(pools) + i]), range(len(pools))) if p]
print(f'{len(opened)}/{len(pools)} pools QUOTE_OPEN', flush=True)
rows = []
for d in dealers:
    for req in api('GET', '/api/dealer/inbox', d['session'])['requests']:
        if req['pool_id'] in opened:
            rows.append({'pool': req['pool_id'], 'dealer': d['session'], 'deadline': req['deadline'],
                         'variant': req['variants'][0]['id'], 'price': req['variants'][0]['catalogue_ex_showroom_paise']})
dl = sorted(r['deadline'] for r in rows)
print(f'{len(rows)} (pool, dealer) pairs; deadlines {dl[0] if dl else "-"} .. {dl[-1] if dl else "-"}', flush=True)
json.dump({'pairs': rows}, open('/work/w4-fixture.json', 'w'))
