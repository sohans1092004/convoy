'use client';
import { api, call } from '@/lib/api';
import { useFetch } from '@/lib/use-fetch';

/** The catalogue's own names for a pool's car. Until they load (or if the catalogue is down) the caller's fallback stands. */
export function useModel(slug: string | undefined) {
  return useFetch(['model', slug], async () => (await call(api.GET('/catalog/models/{slug}', { params: { path: { slug: slug! } } }))).data, { enabled: Boolean(slug) }).data;
}
