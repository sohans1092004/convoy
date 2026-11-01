import { describe, expect, it } from 'vitest';
import { etagVersion } from './api';

const res = (etag: string | null) => new Response(null, { headers: etag ? { etag } : {} });

describe('etagVersion', () => {
  it('reads plain, weak and compressed ETags', () => {
    expect(etagVersion(res('"7"'))).toBe(7);
    expect(etagVersion(res('W/"7"'))).toBe(7);
    expect(etagVersion(res('"7-gzip"'))).toBe(7); // Caddy encode gzip on the lite host
  });
  it('is undefined without a usable ETag', () => {
    expect(etagVersion(res(null))).toBeUndefined();
    expect(etagVersion(res('"abc"'))).toBeUndefined();
  });
});
