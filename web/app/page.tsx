import { getTranslations } from 'next-intl/server';
import { TryDemo } from '@/components/demo';
import { Discover } from '@/components/discover';

export default async function Home() {
  const t = await getTranslations('home');
  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-5">
        <div>
          <h1 className="text-3xl font-semibold leading-tight sm:text-4xl">{t('title')}</h1>
          <p className="mt-3 max-w-2xl text-lg text-muted">{t('lede')}</p>
        </div>
        <div>
          <h2 className="text-lg font-semibold">{t('howTitle')}</h2>
          <ol className="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {(['join', 'quote', 'check', 'decide'] as const).map((s, i) => (
              <li key={s} className="border-t-2 border-primary pt-2">
                <p className="font-semibold"><span className="tabular text-muted">{i + 1}.</span> {t(`how.${s}.title`)}</p>
                <p className="mt-1 text-sm text-muted">{t(`how.${s}.body`)}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>
      {process.env.DEMO_PUBLIC === 'true' ? <TryDemo /> : null}
      <Discover />
    </div>
  );
}
