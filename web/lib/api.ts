import createClient, { type Middleware } from 'openapi-fetch';
import type { paths, Schemas } from './api/schema';

export type { Schemas };
import { recordServerTime } from './server-clock';

// Typed client generated from the services' OpenAPI documents (scripts/gen-api.mjs). It calls the BFF
// (/api/...), which forwards to the gateway. Errors are RFC 9457 problems.

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  code?: string;
  detail?: string;
  errors?: unknown;
  deadline?: string;
  server_time?: string;
  [k: string]: unknown;
}

export class ApiError extends Error {
  constructor(public problem: Problem) {
    super(problem.detail || problem.title || `HTTP ${problem.status}`);
  }
  get status() { return this.problem.status; }
}

function csrf(): string {
  if (typeof document === 'undefined') return '';
  return document.cookie.split('; ').find((c) => c.startsWith('cv_csrf='))?.split('=')[1] ?? '';
}

const sent = new WeakMap<Request, number>();
const middleware: Middleware = {
  onRequest({ request }) {
    if (request.method !== 'GET') request.headers.set('X-CSRF-Token', csrf());
    sent.set(request, Date.now());
    return request;
  },
  onResponse({ request, response }) {
    recordServerTime(response.headers.get('server-time'), sent.get(request) ?? Date.now(), Date.now());
    return response;
  },
};

export const api = createClient<paths>({ baseUrl: '/api' });
api.use(middleware);

/** Unwrap an openapi-fetch result: data (typed from the OpenAPI schema), or throw ApiError with the problem body. */
export async function call<D>(p: Promise<{ data?: D; error?: unknown; response: Response }>): Promise<{ data: NonNullable<D>; response: Response }> {
  const r = await p;
  if (r.error !== undefined || !r.response.ok) {
    const body = (r.error ?? {}) as Partial<Problem>;
    throw new ApiError({ status: r.response.status, ...body } as Problem);
  }
  return { data: r.data as NonNullable<D>, response: r.response };
}

/** ETag "<version>" -> version number for If-Match. */
export function etagVersion(response: Response): number | undefined {
  const e = response.headers.get('etag');
  return e ? Number(e.replace(/W\/|"/g, '')) : undefined;
}
