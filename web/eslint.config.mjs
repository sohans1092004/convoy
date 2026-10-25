// ESLint (P3.A): Next's recommended rules (core web vitals + TypeScript). Required in CI: `pnpm --filter @convoy/web lint`.
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';

const config = [
  ...nextVitals,
  ...nextTs,
  { ignores: ['.next/**', 'storybook-static/**', 'playwright-report*/**', 'test-results/**', 'lib/api/schema/**', 'next-env.d.ts'] },
];

export default config;
