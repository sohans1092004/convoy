'use client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import * as React from 'react';
import { Breakdown } from '@/components/breakdown';
import { CommandStatus } from '@/components/command-status';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Price } from '@/components/ui/price';
import { Skeleton } from '@/components/ui/skeleton';
import { ApiError, api, call, type Schemas } from '@/lib/api';
import { ist } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { useTranslations } from 'next-intl';
import { useOnline } from '@/lib/use-online';

export type Variant = Schemas['catalog']['Variant'];

const CITIES = ['Mumbai', 'Pune', 'Delhi', 'Bengaluru'] as const;
type Estimate = Schemas['catalog']['Estimate'];

/**
 * Variant + city picker, on-road estimate (engine snapshot), and "join or start a pool". PLAN §5.2 states: loading, empty
 * (no verified price for this variant in this city), error (choices kept), stale (previous estimate after a failed
 * refresh or offline), pending, conflict (e.g. a commitment elsewhere), offline (joining paused), success.
 */
export function ModelEstimate({ slug, variants, canJoin, signedIn }: { slug: string; variants: Variant[]; canJoin: boolean; signedIn: boolean }) {
  const router = useRouter();
  const [variantId, setVariantId] = React.useState(variants[0]?.id);
  const [city, setCity] = React.useState<(typeof CITIES)[number]>('Pune');
  const online = useOnline();
  const t = useTranslations('join');
  const estimate = useQuery<Estimate, ApiError>({
    queryKey: ['estimate', variantId, city],
    queryFn: async () => (await call(api.GET('/catalog/variants/{id}/price-estimate', { params: { path: { id: variantId! }, query: { city } } }))).data,
    placeholderData: keepPreviousData,
    staleTime: 0,
    retry: (n, e) => n < 1 && e?.problem?.code !== 'QUOTE_INVALID',
    enabled: Boolean(variantId),
  });
  // QUOTE_INVALID = the engine has no verified inputs for this variant in this city: an empty state, not a failure.
  const noPrice = estimate.error?.problem?.code === 'QUOTE_INVALID';
  const current = estimate.data && !estimate.isPlaceholderData ? estimate.data : undefined;
  const previous = estimate.isPlaceholderData ? estimate.data : undefined;

  const month = new Date().toISOString().slice(0, 7);
  const start = useCommand(async (key: string) => call(
    api.POST('/pools', { body: { model_slug: slug, city, purchase_month: month }, headers: { 'Idempotency-Key': key } })));

  const variant = variants.find((v) => v.id === variantId);
  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <Card className="flex flex-col gap-4">
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-semibold">{t('variant')}</legend>
          {variants.map((v) => (
            <label key={v.id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-border px-3 has-[:checked]:border-primary has-[:checked]:bg-surface-2">
              <input type="radio" name="variant" value={v.id} checked={v.id === variantId} onChange={() => setVariantId(v.id)} className="size-4 accent-[var(--primary)]" />
              <span className="flex-1">{v.name} <span className="text-sm text-muted">{[v.fuel, v.transmission].filter(Boolean).join(' · ')}</span></span>
            </label>
          ))}
        </fieldset>
        <div className="flex flex-col gap-1">
          <label htmlFor="city" className="text-sm font-semibold">{t('city')}</label>
          <select id="city" value={city} onChange={(e) => setCity(e.target.value as (typeof CITIES)[number])} className="min-h-11 rounded-lg border border-border bg-surface px-3">
            {CITIES.map((c) => <option key={c}>{c}</option>)}
          </select>
        </div>
        <div className="flex flex-col gap-2 border-t border-border pt-4">
          <h2 className="font-bold">{t('title', { city })}</h2>
          <p className="text-sm text-muted">{t('body', { variant: variant?.name ?? t('car') })}</p>
          {!online ? <p role="status" className="rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">{t('offline')}</p> : null}
          {canJoin ? (
            <Button disabled={!online} loading={start.state.kind === 'loading'} onClick={async () => { const r = await start.execute(); if (r) router.push(`/pools/${r.data.pool.id}`); }}>
              {t('join', { city })}
            </Button>
          ) : signedIn ? (
            <p className="text-sm text-muted">{t('dealerNo')}</p>
          ) : (
            <Button asChild><Link href={`/sign-in?next=/models/${slug}`}>{t('signIn')}</Link></Button>
          )}
          <CommandStatus state={start.state} successText={t('ready')} />
        </div>
      </Card>
      <section aria-labelledby="estimate" className="flex flex-col gap-3">
        <h2 id="estimate" className="text-lg font-bold">{t('estimateTitle', { city })}</h2>
        {estimate.isPending && estimate.isFetching ? <Skeleton className="h-64" label="Calculating the on-road estimate" /> : null}
        {estimate.isFetching && previous ? <p aria-live="polite" className="text-sm text-muted">Updating the estimate for {variant?.name} in {city}…</p> : null}
        {noPrice ? (
          <Card><p className="text-sm"><strong>No verified price for {variant?.name} in {city} yet.</strong> {estimate.error?.problem?.detail}</p>
            <p className="mt-2 text-sm text-muted">We only estimate when every input is published or verified. Try another city, or join a pool: a dealer&apos;s quote gives exact figures.</p></Card>
        ) : estimate.isError && !current ? (
          <div role="alert" className="flex flex-col items-start gap-2 rounded-lg bg-danger-bg p-3 text-sm text-danger">
            <p>The estimate could not be calculated right now. Your variant and city are kept.</p>
            <Button variant="secondary" onClick={() => estimate.refetch()}>Try again</Button>
          </div>
        ) : null}
        {(estimate.isError && current && !noPrice) || (!online && current) ? (
          <p role="status" className="rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">
            This estimate was calculated {ist(current.snapshot.computed_at)} and could not be refreshed{!online ? ' (offline)' : ''}. It may be out of date.
          </p>
        ) : null}
        {(current ?? previous) && !noPrice ? (
          <div className={previous ? 'opacity-60' : undefined} aria-busy={Boolean(previous)}>
            <Card emphasis className="flex flex-col gap-1">
              <CardTitle as="h3" className="text-base">{t('cash')}</CardTitle>
              <Price paise={(current ?? previous)!.snapshot.output.cash_payable_paise} evidence="CATALOGUE_ESTIMATE" size="lg" />
              <p className="text-sm text-muted">Catalogue figures only: registration and insurance are estimated from published rules. {(current ?? previous)!.city_confirmed ? '' : 'Manufacturer price; city not confirmed.'}</p>
            </Card>
            <div className="mt-3"><Breakdown output={(current ?? previous)!.snapshot.output} caption={`On-road estimate, ${variant?.name}, ${city}`} /></div>
            <p className="mt-2 text-xs text-muted">Ruleset {(current ?? previous)!.snapshot.ruleset_version} · calculated {ist((current ?? previous)!.snapshot.computed_at)} · snapshot {(current ?? previous)!.snapshot.sha256.slice(0, 12)}</p>
          </div>
        ) : null}
      </section>
    </div>
  );
}
