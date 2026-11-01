import { build } from '@/lib/build';

// P8.C: which commit is deployed. GIT_SHA and BUILD_TIME are baked into the web image (web/Dockerfile, images workflow).
export const dynamic = 'force-dynamic';
export function GET() {
  return Response.json(build(), { headers: { 'cache-control': 'no-store' } });
}
