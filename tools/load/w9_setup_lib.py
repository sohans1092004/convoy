# Shared by w9_setup.py and w4_setup.py: the HTTP helper (browser-style auth via the BFF, retries) and the journey's
# steps that bring a pool to QUOTE_OPEN (join -> commit -> simulated deposit -> webhook).
import json, ssl, time, urllib.error, urllib.request, uuid

CTX = ssl._create_unverified_context()  # the full profile's self-signed ALB certificate only


def make_api(BASE):
    def api(method, path, session=None, body=None, idem=False, ok=(200, 201), base=BASE, tries=5):
        h = {'Content-Type': 'application/json'}
        if session: h.update({'Cookie': f'cv_sid={session}; cv_csrf=load', 'X-CSRF-Token': 'load', 'Origin': base})  # as the browser, via the BFF
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

    return api


def to_quote_open(api, p, b):
    api('POST', f'/api/pools/{p}/members', b, idem=True, ok=(200, 201, 409))
    terms = api('GET', f'/api/pools/{p}', b)['terms_version']
    dep = api('POST', f'/api/pools/{p}/commitment', b, {'terms_version': terms}, idem=True)['deposit']
    cb = api('POST', '/pay/fake/pay', body={'order_id': dep['razorpay_order_id'], 'outcome': 'success'})
    api('POST', f'/api/payments/{dep["payment_id"]}/checkout-callback', b, cb)
    for _ in range(120):
        if api('GET', f'/api/pools/{p}', b)['state'] == 'QUOTE_OPEN': return p
        time.sleep(2)
    return None
