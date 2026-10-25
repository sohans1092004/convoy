'use client';
import * as React from 'react';

/** navigator.onLine, kept current. Offline: pages keep the last known state and pause actions. */
export function useOnline(): boolean {
  return React.useSyncExternalStore(
    (cb) => {
      window.addEventListener('online', cb);
      window.addEventListener('offline', cb);
      return () => {
        window.removeEventListener('online', cb);
        window.removeEventListener('offline', cb);
      };
    },
    () => navigator.onLine,
    () => true,
  );
}
