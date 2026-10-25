'use client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';

/** One query cache per browser tab. Snapshots are refetched explicitly (stream, polling fallback), not on focus. */
export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = React.useState(() => new QueryClient({
    defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 5_000 } },
  }));
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
