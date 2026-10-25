import { cookies } from 'next/headers';
import { type NextRequest, NextResponse } from 'next/server';
import { withBffSpan } from '@/lib/otel';

// BFF: the browser only ever talks to this route; it never calls services directly (auth-contract.md §1-2).
// - session id lives in the HttpOnly cookie cv_sid and is forwarded as X-Convoy-Session
// - identity headers from the browser are dropped; only an allow-list of headers is forwarded
// - state-changing requests must be same-origin and carry the CSRF double-submit token
export const dynamic = 'force-dynamic';

const GATEWAY = process.env.GATEWAY_URL || 'http://localhost:8080';
const SECURE = process.env.COOKIE_SECURE !== 'false';
const FORWARD_REQ = ['content-type', 'accept', 'idempotency-key', 'if-match', 'x-correlation-id'];
const FORWARD_RES = ['content-type', 'etag', 'server-time', 'idempotent-replayed', 'x-correlation-id', 'retry-after'];
const NO_SESSION_NEEDED = new Set(['auth/otp/start', 'auth/otp/verify', 'auth/passkeys/authenticate/options', 'auth/passkeys/authenticate']);
const SIGN_IN = new Set(['auth/otp/verify', 'auth/passkeys/authenticate']); // responses carry a session id: set the cookie, never echo it
const SESSION_DAYS = 30;

function problem(status: number, slug: string, code: string, title: string, detail: string) {
  return NextResponse.json({ type: `https://convoy.example/problems/${slug}`, title, status, code, detail }, { status, headers: { 'content-type': 'application/problem+json' } });
}

async function handle(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const route = path.join('/');
  const jar = await cookies();
  const mutating = !['GET', 'HEAD'].includes(req.method);

  if (mutating) {
    const site = req.headers.get('sec-fetch-site');
    const origin = req.headers.get('origin');
    const sameOrigin = site ? site === 'same-origin' : origin !== null && new URL(origin).host === req.headers.get('host');
    if (!sameOrigin) return problem(403, 'forbidden', 'FORBIDDEN', 'Authenticated but not allowed on this resource', 'Cross-site request refused.');
    const csrf = jar.get('cv_csrf')?.value;
    if (!csrf || req.headers.get('x-csrf-token') !== csrf) {
      return problem(403, 'forbidden', 'FORBIDDEN', 'Authenticated but not allowed on this resource', 'Missing or stale security token. Reload the page.');
    }
  }

  const headers = new Headers();
  for (const h of FORWARD_REQ) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  const sid = jar.get('cv_sid')?.value;
  if (sid && !NO_SESSION_NEEDED.has(route)) headers.set('X-Convoy-Session', sid);
  const fwd = req.headers.get('x-forwarded-for');
  headers.set('X-Forwarded-For', fwd ? fwd.split(',')[0].trim() : '127.0.0.1');

  return withBffSpan(`BFF ${req.method} /api/${path[0]}`, async (trace, span) => {
    for (const [k, v] of Object.entries(trace)) headers.set(k, v);
    span.setAttribute('http.route', `/api/${route}`);
    let upstream: Response;
    try {
      upstream = await fetch(`${GATEWAY}/api/${route}${req.nextUrl.search}`, {
        method: req.method, headers, body: mutating ? await req.arrayBuffer() : undefined, cache: 'no-store', redirect: 'manual',
        signal: route.startsWith('stream/') ? req.signal : undefined, // the browser leaving closes the upstream SSE connection
      });
    } catch {
      return problem(503, 'dependency-unavailable', 'DEPENDENCY_UNAVAILABLE', 'A required downstream dependency is unavailable', 'Convoy is unreachable right now. Try again shortly.');
    }
    // An expired or revoked session must not break public reads: retry a GET once without it and drop the dead cookies.
    let sessionExpired = false;
    if (upstream.status === 401 && sid && req.method === 'GET' && !route.startsWith('stream/')) {
      sessionExpired = true;
      headers.delete('X-Convoy-Session');
      try {
        upstream = await fetch(`${GATEWAY}/api/${route}${req.nextUrl.search}`, { method: 'GET', headers, cache: 'no-store', redirect: 'manual' });
      } catch {
        return problem(503, 'dependency-unavailable', 'DEPENDENCY_UNAVAILABLE', 'A required downstream dependency is unavailable', 'Convoy is unreachable right now. Try again shortly.');
      }
    }
    span.setAttribute('http.status_code', upstream.status);
    const out = new Headers();
    for (const h of FORWARD_RES) {
      const v = upstream.headers.get(h);
      if (v) out.set(h, v);
    }
    // SSE (realtime E120): stream through unbuffered; never read the whole body.
    if (route.startsWith('stream/') && upstream.ok && upstream.body) {
      out.set('cache-control', 'no-cache, no-transform');
      out.set('x-accel-buffering', 'no');
      return new Response(upstream.body, { status: upstream.status, headers: out });
    }
    let body: BodyInit | null = upstream.status === 204 ? null : await upstream.arrayBuffer();

    if (SIGN_IN.has(route) && upstream.ok) {
      const data = JSON.parse(new TextDecoder().decode(body as ArrayBuffer));
      const res = NextResponse.json({ user: data.user }, { status: 200 });
      const opts = { httpOnly: true, secure: SECURE, sameSite: 'lax' as const, path: '/', maxAge: SESSION_DAYS * 86400 };
      res.cookies.set('cv_sid', data.session_id, opts);
      res.cookies.set('cv_roles', (data.user.roles as string[]).join(','), opts);
      return res;
    }
    if (route === 'auth/logout') {
      const res = new NextResponse(body, { status: upstream.status, headers: out });
      res.cookies.delete('cv_sid');
      res.cookies.delete('cv_roles');
      return res;
    }
    if (upstream.status === 401 && sid) {
      // Session ended upstream: clear the cookie so the UI shows signed-out state.
      const res = new NextResponse(body, { status: 401, headers: out });
      res.cookies.delete('cv_sid');
      res.cookies.delete('cv_roles');
      return res;
    }
    body = body && (body as ArrayBuffer).byteLength === 0 ? null : body;
    const res = new NextResponse(body, { status: upstream.status, headers: out });
    if (sessionExpired) {
      res.cookies.delete('cv_sid');
      res.cookies.delete('cv_roles');
    }
    return res;
  });
}

export { handle as GET, handle as POST, handle as PUT, handle as DELETE, handle as PATCH };
