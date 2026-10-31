'use client';
import { useTranslations } from 'next-intl';
import Link from 'next/link';
import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { ApiError, api, call, postBff } from '@/lib/api';

/** P6.B: one click gives a stranger an isolated 24 h scenario, then a fresh pool for the demo model. No email. */
export function TryDemo() {
  const t = useTranslations('demo');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const start = async () => {
    setBusy(true);
    setError(undefined);
    try {
      await postBff('/auth/demo');
      const month = new Date().toISOString().slice(0, 7);
      const { data } = await call(api.POST('/pools', { body: { model_slug: 'maruti-swift', city: 'Pune', purchase_month: month }, headers: { 'Idempotency-Key': crypto.randomUUID() } }));
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- a full load picks up the new session cookies
      window.location.href = `/pools/${data.pool.id}`;
    } catch (e) {
      setError(t('failed', { detail: e instanceof ApiError ? e.message : String(e) }));
      setBusy(false);
    }
  };
  return (
    <Card className="flex flex-col gap-3">
      <p className="text-sm">{t('intro')}</p>
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={start} loading={busy}>{busy ? t('trying') : t('try')}</Button>
        <Link href="/demo/admin" className="text-sm font-medium text-primary">{t('adminLink')}</Link>
      </div>
      {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
    </Card>
  );
}

/** Nav control while a demo is active: buyer and dealer views of the same scenario. */
export function DemoSwitch({ dealer }: { dealer: boolean }) {
  const t = useTranslations('demo');
  const swap = async () => {
    const r = await postBff<{ roles: string[] }>('/demo/switch');
    window.location.href = r.roles.some((x) => x.startsWith('dealer')) ? '/dealer' : '/';
  };
  return <Button variant="link" onClick={swap}>{dealer ? t('toBuyer') : t('toDealer')}</Button>;
}
