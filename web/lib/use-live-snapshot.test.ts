import { describe, expect, it } from 'vitest';
import { applyDelta, backoffMs, decide } from './use-live-snapshot';

describe('stream version rule (realtime-recovery.md §2)', () => {
  it('applies only the next version, ignores old ones and resyncs on a gap', () => {
    expect(decide(4, 5)).toBe('apply');
    expect(decide(4, 4)).toBe('ignore');
    expect(decide(4, 2)).toBe('ignore');
    expect(decide(4, 7)).toBe('resync');
    expect(decide(undefined, 1)).toBe('resync');
  });

  it('merges a delta and takes its version, keeping fields the delta does not carry', () => {
    const snap = { version: 4, committed_count: 2, interested_count: 5, city: 'Pune' };
    expect(applyDelta(snap, { aggregate: 'pool', id: 'p', version: 5, delta: { committed_count: 3 } }))
      .toEqual({ version: 5, committed_count: 3, interested_count: 5, city: 'Pune' });
  });

  it('backs off with full jitter up to the 30 s cap', () => {
    for (let i = 0; i < 200; i++) {
      const a = backoffMs(0);
      expect(a).toBeGreaterThanOrEqual(0);
      expect(a).toBeLessThan(500);
      expect(backoffMs(20)).toBeLessThanOrEqual(30_000);
    }
  });
});
