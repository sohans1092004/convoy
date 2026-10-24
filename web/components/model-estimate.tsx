'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import * as React from 'react';
import { Breakdown, type EngineOutput } from '@/components/breakdown';
import { CommandStatus } from '@/components/command-status';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Price } from '@/components/ui/price';
import { Skeleton } from '@/components/ui/skeleton';
import { ApiError, api, call, type Problem } from '@/lib/api';
import { ist } from '@/lib/format';
import { useCommand } from '@/lib/use-command';

export interface Variant { id: string; code: string; name: string; fuel: string | null; transmission: string | null; engine_cc: number | null;
  prices: { city: string; ex_showroom_paise: number; evidence: 'CATALOGUE_ESTIMATE'; city_confirmed: boolean }[] }

const CITIES = ['Mumbai', 'Pune', 'Delhi', 'Bengaluru'] as const;
type Estimate = { city_confirmed: boolean; snapshot: { output: EngineOutput; sha256: string; computed_at: string; ruleset_version: string } };

/** Variant + city picker, on-road estimate (engine snapshot), and "join or start a pool". */
export function ModelEstimate({ slug, variants, canJoin, signedIn }: { slug: string; variants: Variant[]; canJoin: boolean; signedIn: boolean }) {
  const router = useRouter();
  const [variantId, setVariantId] = React.useState(variants[0]?.id);
  const [city, setCity] = React.useState<(typeof CITIES)[number]>('Pune');
  const [estimate, setEstimate] = React.useState<{ loading: boolean; data?: Estimate; problem?: Problem }>({ loading: true });

  React.useEffect(() => {
    let live = true;
    setEstimate({ loading: true });
    call<Estimate>(api.GET('/catalog/variants/{id}/price-estimate', { params: { path: { id: variantId }, query: { city } } }))
      .then(({ data }) => live && setEstimate({ loading: false, data }))
      .catch((e) => live && setEstimate({ loading: false, problem: e instanceof ApiError ? e.problem : { status: 0, detail: 'Network error.' } }));
    return () => { live = false; };
  }, [variantId, city]);

  const month = new Date().toISOString().slice(0, 7);
  const start = useCommand(async (key: string) => call<{ created: boolean; pool: { id: string } }>(
    api.POST('/pools', { body: { model_slug: slug, city, purchase_month: month }, headers: { 'Idempotency-Key': key } })));

  const variant = variants.find((v) => v.id === variantId);
  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <Card className="flex flex-col gap-4">
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-semibold">Variant</legend>
          {variants.map((v) => (
            <label key={v.id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-border px-3 has-[:checked]:border-primary has-[:checked]:bg-surface-2">
              <input type="radio" name="variant" value={v.id} checked={v.id === variantId} onChange={() => setVariantId(v.id)} className="size-4 accent-[var(--primary)]" />
              <span className="flex-1">{v.name} <span className="text-sm text-muted">{[v.fuel, v.transmission].filter(Boolean).join(' · ')}</span></span>
            </label>
          ))}
        </fieldset>
        <div className="flex flex-col gap-1">
          <label htmlFor="city" className="text-sm font-semibold">City</label>
          <select id="city" value={city} onChange={(e) => setCity(e.target.value as (typeof CITIES)[number])} className="min-h-11 rounded-lg border border-border bg-surface px-3">
            {CITIES.map((c) => <option key={c}>{c}</option>)}
          </select>
        </div>
        <div className="flex flex-col gap-2 border-t border-border pt-4">
          <h2 className="font-bold">Buy with others in {city}</h2>
          <p className="text-sm text-muted">Join buyers who want a {variant?.name ?? 'car'} this month. Dealers then compete for the whole group.</p>
          {canJoin ? (
            <Button loading={start.state.kind === 'loading'} onClick={async () => { const r = await start.execute(); if (r) router.push(`/pools/${r.data.pool.id}`); }}>
              Join or start a pool in {city}
            </Button>
          ) : signedIn ? (
            <p className="text-sm text-muted">Dealer accounts cannot join pools.</p>
          ) : (
            <Button asChild><Link href={`/sign-in?next=/models/${slug}`}>Sign in to join a pool</Link></Button>
          )}
          <CommandStatus state={start.state} />
        </div>
      </Card>
      <section aria-labelledby="estimate" className="flex flex-col gap-3">
        <h2 id="estimate" className="text-lg font-bold">On-road estimate in {city}</h2>
        {estimate.loading ? <Skeleton className="h-64" label="Calculating the on-road estimate" /> : null}
        {estimate.problem ? (
          <Card><p role="alert" className="text-sm"><strong>No estimate for this variant.</strong> {estimate.problem.detail}</p>
            <p className="mt-2 text-sm text-muted">We only estimate when every input is published or verified. A dealer&apos;s quote will give exact figures.</p></Card>
        ) : null}
        {estimate.data ? (
          <>
            <Card emphasis className="flex flex-col gap-1">
              <CardTitle as="h3" className="text-base">Estimated cash payable</CardTitle>
              <Price paise={estimate.data.snapshot.output.cash_payable_paise} evidence="CATALOGUE_ESTIMATE" size="lg" />
              <p className="text-sm text-muted">Catalogue figures only: registration and insurance are estimated from published rules. {estimate.data.city_confirmed ? '' : 'Manufacturer price; city not confirmed.'}</p>
            </Card>
            <Breakdown output={estimate.data.snapshot.output} caption={`On-road estimate, ${variant?.name}, ${city}`} />
            <p className="text-xs text-muted">Ruleset {estimate.data.snapshot.ruleset_version} · calculated {ist(estimate.data.snapshot.computed_at)} · snapshot {estimate.data.snapshot.sha256.slice(0, 12)}</p>
          </>
        ) : null}
      </section>
    </div>
  );
}
