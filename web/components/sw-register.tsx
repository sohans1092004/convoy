'use client';
import * as React from 'react';

/** Registers the service worker (public/sw.js: offline page + push). */
export function ServiceWorkerRegister() {
  React.useEffect(() => {
    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => undefined);
  }, []);
  return null;
}
