import { type NextRequest, NextResponse } from 'next/server';

// Route guards by role (UX only: every service still enforces authorisation itself) and the CSRF
// double-submit cookie for the BFF.
const BUYER_PATHS = [/^\/pools\//, /^\/orders\//];
const DEALER_PATHS = [/^\/dealer(\/|$)/];

export function proxy(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  const sid = req.cookies.get('cv_sid')?.value;
  const roles = (req.cookies.get('cv_roles')?.value ?? '').split(',').filter(Boolean);
  const needsBuyer = BUYER_PATHS.some((r) => r.test(pathname));
  const needsDealer = DEALER_PATHS.some((r) => r.test(pathname));

  if ((needsBuyer || needsDealer) && !sid) {
    const to = req.nextUrl.clone();
    to.pathname = '/sign-in';
    to.search = `?next=${encodeURIComponent(pathname + search)}`;
    return NextResponse.redirect(to);
  }
  if (needsDealer && !roles.some((r) => r === 'dealer_staff' || r === 'dealer_admin')) {
    return NextResponse.redirect(new URL('/', req.url));
  }
  if (needsBuyer && !roles.includes('buyer')) {
    return NextResponse.redirect(new URL(roles.some((r) => r.startsWith('dealer')) ? '/dealer' : '/', req.url));
  }

  const res = NextResponse.next();
  if (!req.cookies.get('cv_csrf')) {
    res.cookies.set('cv_csrf', crypto.randomUUID(), { httpOnly: false, secure: process.env.COOKIE_SECURE !== 'false', sameSite: 'lax', path: '/' });
  }
  return res;
}

export const config = { matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'] };
