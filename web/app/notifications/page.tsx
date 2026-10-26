import { getLocale, getTranslations } from 'next-intl/server';
import { Card } from '@/components/ui/card';
import { NotificationInbox } from '@/components/notification-inbox';
import type { Schemas } from '@/lib/api';
import { gatewayGet } from '@/lib/gateway';

type InboxPage = Schemas['notification']['InboxPage'];
type Preferences = Schemas['notification']['Preferences'];

export default async function NotificationsPage() {
  const t = await getTranslations('notifications');
  const locale = await getLocale();
  const [{ data }, { data: prefs }] = await Promise.all([
    gatewayGet<InboxPage>(`/me/notifications?locale=${locale === 'hi' ? 'hi' : 'en'}`),
    gatewayGet<Preferences>('/me/notification-preferences'),
  ]);
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">{t('title')}</h1>
      {!data ? <Card><p>{t('unavailable')}</p></Card> : <NotificationInbox initial={data} prefs={prefs} />}
    </div>
  );
}
