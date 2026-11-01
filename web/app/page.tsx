import { getTranslations } from 'next-intl/server';
import { TryDemo } from '@/components/demo';
import { Discover } from '@/components/discover';

export default async function Home() {
  const t = await getTranslations('home');
  return (
    <div className="flex flex-col gap-6">
      <section>
        <h1 className="text-2xl font-extrabold leading-tight sm:text-3xl">{t('title')}</h1>
        <p className="mt-2 max-w-2xl text-muted">{t('lede')}</p>
      </section>
      {process.env.DEMO_PUBLIC === 'true' ? <TryDemo /> : null}
      <Discover />
    </div>
  );
}
