'use client';
/* eslint-disable react-hooks/refs -- remembers the first ruleset seen to flag a recalculation; reading this ref during render is intentional */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import Link from 'next/link';
import * as React from 'react';
import { EvidenceBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { ist, rupees } from '@/lib/format';
import { useOnline } from '@/lib/use-online';

type ModelList = Schemas['catalog']['ModelList'];
const CITIES = ['', 'Mumbai', 'Pune', 'Delhi', 'Bengaluru'];

/**
 * Discover (PLAN §5.2 states): loading, empty, error (search kept), stale (cached prices after a failed refresh),
 * pending (city change, previous list kept), conflict (ruleset changed while browsing), offline, success.
 */
export function Discover() {
  const t = useTranslations('discover');
  const online = useOnline();
  const [q, setQ] = React.useState('');
  const [city, setCity] = React.useState('');
  const firstRuleset = React.useRef<string | null>(null);
  const models = useQuery({
    queryKey: ['models', city],
    queryFn: async () => (await call(api.GET('/catalog/models', { params: { query: city ? { city } : {} } }))).data as ModelList,
    placeholderData: keepPreviousData,
    staleTime: 0, // returning to a city refreshes its prices; a failed refresh shows the cached list as stale
  });
  const data = models.data;
  if (data && firstRuleset.current === null) firstRuleset.current = data.ruleset_version;
  const rulesetChanged = data && firstRuleset.current !== null && data.ruleset_version !== firstRuleset.current;
  const needle = q.trim().toLowerCase();
  const shown = (data?.models ?? []).filter((m) => !needle || `${m.brand} ${m.name} ${m.body_type}`.toLowerCase().includes(needle));
  // Stale = this city's cached list after its refresh failed (never another city's list presented as this one's).
  const stale = Boolean(data) && !models.isPlaceholderData && (models.isError || !online);

  return (
    <section aria-labelledby="cars" className="flex flex-col gap-3">
      <h2 id="cars" className="text-lg font-bold">{t('heading')}</h2>
      <div className="grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <label className="flex flex-col gap-1 text-sm font-semibold">{t('search')}
          <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('searchPlaceholder')}
            className="min-h-11 rounded-lg border border-border bg-surface px-3 text-base font-normal" />
        </label>
        <label className="flex flex-col gap-1 text-sm font-semibold">{t('city')}
          <select value={city} onChange={(e) => setCity(e.target.value)} className="min-h-11 rounded-lg border border-border bg-surface px-3 text-base font-normal">
            {CITIES.map((c) => <option key={c} value={c}>{c || t('anyCity')}</option>)}
          </select>
        </label>
      </div>

      {!online ? <p role="status" className="rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">{t('offline')}</p> : null}
      {online && stale ? (
        <div role="status" className="flex flex-wrap items-center gap-2 rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">
          {t('stale', { time: ist(new Date(models.dataUpdatedAt).toISOString(), true) })}
          <Button variant="secondary" onClick={() => models.refetch()}>{t('retry')}</Button>
        </div>
      ) : null}
      {rulesetChanged ? <p role="status" className="rounded-lg bg-info-bg p-3 text-sm text-info-fg">{t('rulesetChanged', { version: data!.ruleset_version })}</p> : null}

      {models.isPending ? (
        <Skeleton className="h-64" label={t('loading')} />
      ) : !data ? (
        <div role="alert" className="flex flex-col items-start gap-2 rounded-lg bg-danger-bg p-3 text-danger">
          <p>{t('error')}</p>
          <Button variant="secondary" onClick={() => models.refetch()}>{t('retry')}</Button>
        </div>
      ) : (
        <>
          <p aria-live="polite" className="text-sm text-muted">
            {models.isFetching && models.isPlaceholderData ? t('updating', { city: city || t('anyCity') }) : t('count', { count: shown.length, city: city || t('anyCity') })}
          </p>
          {shown.length === 0 ? (
            <div className="flex flex-col items-start gap-2 rounded-lg border border-border p-4">
              <p>{t('empty', { q })}</p>
              <Button variant="secondary" onClick={() => setQ('')}>{t('clear')}</Button>
            </div>
          ) : (
            <ul aria-busy={models.isFetching && models.isPlaceholderData} className={`grid gap-3 sm:grid-cols-2 lg:grid-cols-3 ${models.isPlaceholderData ? 'opacity-60' : ''}`}>
              {shown.map((m) => (
                <li key={m.slug}>
                  <Card className="relative flex h-full flex-col gap-2">
                    <CardTitle as="h3"><Link href={`/models/${m.slug}`} className="after:absolute after:inset-0 hover:underline">{m.brand} {m.name}</Link></CardTitle>
                    <CardText>{m.body_type} · {t('variants', { count: m.variant_count })}</CardText>
                    <p className="tabular font-semibold">{t('from', { price: rupees(m.from_ex_showroom_paise) })}</p>
                    <div className="mt-auto flex flex-wrap items-center gap-2">
                      <EvidenceBadge evidence={m.evidence} />
                      {!m.any_city_confirmed ? <span className="text-xs text-muted">{t('cityNotConfirmed')}</span> : null}
                    </div>
                  </Card>
                </li>
              ))}
            </ul>
          )}
          <p className="text-xs text-muted">{t('source', { version: data.ruleset_version })}</p>
        </>
      )}
    </section>
  );
}
