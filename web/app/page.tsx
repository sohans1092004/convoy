import { getTranslations } from 'next-intl/server';
import Link from 'next/link';
import { EvidenceBadge } from '@/components/ui/badge';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { rupees } from '@/lib/format';
import { gatewayGet } from '@/lib/gateway';

interface ModelRow { slug: string; name: string; brand: string; body_type: string; variant_count: number; from_ex_showroom_paise: number; any_city_confirmed: boolean }

export default async function Home() {
  const t = await getTranslations('home');
  const { status, data } = await gatewayGet<{ models: ModelRow[]; ruleset_version: string }>('/catalog/models');
  return (
    <div className="flex flex-col gap-6">
      <section>
        <h1 className="text-2xl font-extrabold leading-tight sm:text-3xl">{t('title')}</h1>
        <p className="mt-2 max-w-2xl text-muted">{t('lede')}</p>
      </section>
      {status !== 200 || !data ? (
        <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">The catalogue is unavailable right now. Try again in a minute.</p>
      ) : (
        <section aria-labelledby="cars">
          <h2 id="cars" className="mb-3 text-lg font-bold">Cars with checked prices</h2>
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {data.models.map((m) => (
              <li key={m.slug}>
                <Card className="relative flex h-full flex-col gap-2">
                  <CardTitle as="h3"><Link href={`/models/${m.slug}`} className="after:absolute after:inset-0 hover:underline">{m.brand} {m.name}</Link></CardTitle>
                  <CardText>{m.body_type} · {t('variants', { count: m.variant_count })}</CardText>
                  <p className="tabular font-semibold">{t('from', { price: rupees(m.from_ex_showroom_paise) })}</p>
                  <div className="mt-auto flex flex-wrap items-center gap-2">
                    <EvidenceBadge evidence="CATALOGUE_ESTIMATE" />
                    {!m.any_city_confirmed ? <span className="text-xs text-muted">{t('cityNotConfirmed')}</span> : null}
                  </div>
                </Card>
              </li>
            ))}
          </ul>
          <p className="mt-4 text-xs text-muted">Prices from the manufacturers&apos; official pages (ruleset {data.ruleset_version}). Only verified variant prices are listed.</p>
        </section>
      )}
    </div>
  );
}
