import 'server-only';
import { cookies } from 'next/headers';

const GATEWAY = process.env.GATEWAY_URL || 'http://localhost:8080';

/**
 * Server-component read through the gateway with the caller's session (never exposed to the browser). An expired or
 * revoked session must not hide public pages: a 401 for a request that carried a session is retried anonymously.
 */
export async function gatewayGet<T>(path: string): Promise<{ status: number; data: T | null }> {
  const sid = (await cookies()).get('cv_sid')?.value;
  try {
    let r = await fetch(`${GATEWAY}/api${path}`, { headers: sid ? { 'X-Convoy-Session': sid } : {}, cache: 'no-store' });
    if (r.status === 401 && sid) r = await fetch(`${GATEWAY}/api${path}`, { cache: 'no-store' });
    return { status: r.status, data: r.ok ? ((await r.json()) as T) : null };
  } catch {
    return { status: 503, data: null };
  }
}
