'use client';
import * as React from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { api, call, type Schemas } from '@/lib/api';
import { ist } from '@/lib/format';
import { disablePush, enablePush, pushSubscription, pushSupported } from '@/lib/push';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';

type InboxPage = Schemas['notification']['InboxPage'];
type Preferences = Schemas['notification']['Preferences'];

/** E130 list, E131 / E158 mark read, E159 / E132 channel preferences (payment and refund messages are always sent). */
export function NotificationInbox({ initial, prefs }: { initial: InboxPage; prefs: Preferences | null }) {
  const t = useTranslations('notifications');
  const [items, setItems] = React.useState(initial.notifications);
  const [p, setP] = React.useState<Preferences>(prefs ?? { in_app: true, email: true });
  const [saved, setSaved] = React.useState(false);
  // Push is per device: null = not checked yet (no window on the server), 'unsupported' hides the switch.
  const [push, setPush] = React.useState<boolean | 'unsupported' | null>(null);
  const [pushError, setPushError] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (!pushSupported()) setPush('unsupported'); // eslint-disable-line react-hooks/set-state-in-effect -- feature detection after hydration
    else void pushSubscription().then((s) => setPush(Boolean(s)), () => setPush(false));
  }, []);
  const togglePush = async (on: boolean) => {
    setPushError(null);
    try {
      await (on ? enablePush() : disablePush());
      setPush(on);
    } catch (e) {
      setPushError(e instanceof Error && e.message === 'denied' ? t('pushDenied') : t('pushFailed'));
    }
  };
  const now = () => new Date().toISOString();

  const markRead = async (id: string) => {
    await call(api.POST('/me/notifications/{id}/read', { params: { path: { id } } }));
    setItems((xs) => xs.map((x) => (x.id === id ? { ...x, read_at: x.read_at ?? now() } : x)));
  };
  const markAll = async () => {
    await call(api.POST('/me/notifications/read-all'));
    setItems((xs) => xs.map((x) => ({ ...x, read_at: x.read_at ?? now() })));
  };
  const save = async (next: Preferences) => {
    setP(next);
    setSaved(false);
    await call(api.PUT('/me/notification-preferences', { body: next }));
    setSaved(true);
  };
  const unread = items.filter((x) => !x.read_at).length;

  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm text-muted" aria-live="polite">{t('unread', { count: unread })}</p>
        {unread > 0 ? <Button variant="link" onClick={markAll}>{t('markAll')}</Button> : null}
      </div>
      {items.length === 0 ? (
        <Card><p>{t('none')}</p></Card>
      ) : (
        <ul className="flex flex-col gap-3" data-testid="notifications">
          {items.map((n) => (
            <li key={n.id}>
              <Card className={n.read_at ? 'flex flex-col gap-1' : 'flex flex-col gap-1 border-primary'}>
                <div className="flex flex-wrap items-center gap-2">
                  {!n.read_at ? <Badge tone="warning">{t('new')}</Badge> : null}
                  <Link href={n.link} className="font-semibold underline" onClick={() => void markRead(n.id)}>{n.subject}</Link>
                  {n.draft ? <Badge tone="neutral">{t('draft')}</Badge> : null}
                </div>
                <p className="text-sm">{n.body}</p>
                <div className="flex flex-wrap items-center gap-3 text-xs text-muted">
                  <span>{ist(n.created_at)}</span>
                  {!n.read_at ? <Button variant="link" onClick={() => void markRead(n.id)}>{t('markRead')}</Button> : null}
                </div>
              </Card>
            </li>
          ))}
        </ul>
      )}
      <Card className="flex flex-col gap-2">
        <h2 className="font-bold">{t('prefs')}</h2>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={p.in_app} onChange={(e) => void save({ ...p, in_app: e.target.checked })} /> {t('inApp')}
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={p.email} onChange={(e) => void save({ ...p, email: e.target.checked })} /> {t('email')}
        </label>
        {push === 'unsupported' ? <p className="text-sm text-muted">{t('pushUnsupported')}</p> : (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={push === true} disabled={push === null} onChange={(e) => void togglePush(e.target.checked)} data-testid="push-toggle" /> {t('push')}
          </label>
        )}
        <p className="text-xs text-muted">{t('pushHint')}</p>
        {pushError ? <p className="text-xs text-danger" role="alert">{pushError}</p> : null}
        <p className="text-xs text-muted">{t('always')}</p>
        {saved ? <p className="text-xs" role="status">{t('saved')}</p> : null}
      </Card>
    </>
  );
}
