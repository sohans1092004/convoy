import { api, call } from './api';

// Web push (P5.B): one subscription per device, kept by notification (E160-E162). Pushes carry no payload.

export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

export async function pushSubscription(): Promise<PushSubscription | null> {
  const reg = await navigator.serviceWorker.getRegistration();
  return (await reg?.pushManager.getSubscription()) ?? null;
}

export async function enablePush(): Promise<void> {
  if ((await Notification.requestPermission()) !== 'granted') throw new Error('denied');
  const { data: { public_key } } = await call(api.GET('/me/push-subscriptions/key'));
  if (!public_key) throw new Error('unavailable');
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: public_key });
  const keys = sub.toJSON().keys ?? {};
  await call(api.PUT('/me/push-subscriptions', { body: { endpoint: sub.endpoint, p256dh: keys.p256dh ?? '', auth: keys.auth ?? '' } }));
}

export async function disablePush(): Promise<void> {
  const sub = await pushSubscription();
  if (!sub) return;
  await call(api.DELETE('/me/push-subscriptions', { params: { query: { endpoint: sub.endpoint } } }));
  await sub.unsubscribe();
}
