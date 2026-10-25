import { getTranslations } from 'next-intl/server';
import { MyCars } from '@/components/my-cars';

export async function generateMetadata() {
  const t = await getTranslations('myCars');
  return { title: t('metaTitle') };
}

export default async function SavedPage() {
  const t = await getTranslations('myCars');
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">{t('pageTitle')}</h1>
      <MyCars />
    </div>
  );
}
