// node --test tools/simulator/dealer-sim.test.mjs   (P3.F: the planning is deterministic and respects stock and margin floors)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DEALERS, plan } from './dealer-sim.mjs';

// Real catalogue rows (Pune, catalogue ex-showroom).
const VARIANTS = [
  { id: 'v-swift', code: 'swift-lxi-mt', name: 'LXi MT', catalogue_ex_showroom_paise: 58390000 },
  { id: 'v-other', code: 'swift-zxip-amt', name: 'ZXi+ AMT', catalogue_ex_showroom_paise: 88890000 },
];
const NOW = Date.parse('2026-10-25T10:00:00Z');
const by = (s) => DEALERS.find((d) => d.strategy === s);

test('same seed, same plan; another seed may differ', () => {
  for (const d of DEALERS) assert.deepEqual(plan(d, VARIANTS, 7, NOW), plan(d, VARIANTS, 7, NOW));
  assert.notDeepEqual(plan(by('conservative'), VARIANTS, 1, NOW).sealed, plan(by('conservative'), VARIANTS, 2, NOW).sealed);
});

test('never quotes past the margin floor, only for variants in stock', () => {
  for (const d of DEALERS) {
    for (const seed of [1, 2, 3, 42, 999]) {
      const p = plan(d, VARIANTS, seed, NOW);
      for (const draft of [p.sealed, p.final].filter(Boolean)) {
        assert.ok(draft.tiers.every((t) => t.discount_paise <= p.floorDiscount), `${d.email} seed ${seed}`);
        assert.deepEqual(draft.variants.map((v) => v.variant_id), ['v-swift']); // no stock of ZXi+ AMT
        assert.ok(draft.variants.every((v) => v.stock.units > 0 && v.stock.colours.length === 1));
      }
    }
  }
});

test('strategies behave as documented', () => {
  const agg = plan(by('aggressive'), VARIANTS, 3, NOW);
  assert.ok(agg.final && agg.final.tiers[0].discount_paise > agg.sealed.tiers[0].discount_paise, 'aggressive undercuts in the final round');
  assert.equal(plan(by('conservative'), VARIANTS, 3, NOW).final, null, 'conservative never revises');
  const mis = plan(by('misprices_addon'), VARIANTS, 3, NOW);
  assert.ok(mis.sealed.variants[0].accessories.some((a) => a.required && a.amount_paise === by('misprices_addon').addon_price_paise), 'mispriced dealer-required add-on');
  assert.ok(plan({ ...by('conservative'), strategy: 'slow_responder' }, VARIANTS, 3, NOW).submitBeforeDeadlineMs >= 8000);
  assert.equal(plan({ ...by('conservative'), strategy: 'withdraws' }, VARIANTS, 3, NOW).withdraw, true);
});
