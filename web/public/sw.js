// Convoy service worker (P5.B). Caches exactly one thing: the static offline page. Nothing else is cached, so no API
// response (personal or money data) and no server-rendered page (it carries the user's unread count) ever lands in a cache.
const CACHE = 'convoy-offline-v1';
const OFFLINE = '/offline.html';

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.add(new Request(OFFLINE, { cache: 'reload' }))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

// Page navigations only: network first, the offline page when the network fails. Everything else goes straight through.
self.addEventListener('fetch', (e) => {
  if (e.request.mode !== 'navigate') return;
  e.respondWith(fetch(e.request).catch(() => caches.match(OFFLINE)));
});

// Pushes carry no payload: read the newest message over the user's own session (never cached) and show it.
self.addEventListener('push', (e) => {
  e.waitUntil((async () => {
    let title = 'Convoy';
    let body = 'You have a new notification.';
    let link = '/notifications';
    try {
      const r = await fetch('/api/me/notifications?limit=1', { credentials: 'same-origin', cache: 'no-store' });
      const n = r.ok ? (await r.json()).notifications?.[0] : undefined;
      if (n) ({ subject: title, body, link } = n);
    } catch { /* offline or signed out: the generic text */ }
    await self.registration.showNotification(title, { body, icon: '/icons/icon-192.png', data: { link }, tag: 'convoy' });
  })());
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(self.clients.openWindow(e.notification.data?.link || '/notifications'));
});
