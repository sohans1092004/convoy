'use client';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';

/** Route error boundary: honest message and a retry; nothing the user entered is lost by reloading a read page. */
export default function RouteError({ reset }: { error: Error; reset: () => void }) {
  const t = useTranslations('errorPage');
  return (
    <div role="alert" className="flex flex-col items-start gap-3 rounded-lg bg-danger-bg p-4 text-danger">
      <h1 className="text-lg font-bold">{t('title')}</h1>
      <p>{t('body')}</p>
      <Button variant="secondary" onClick={() => reset()}>{t('retry')}</Button>
    </div>
  );
}
