import type { NextConfig } from 'next';
import createNextIntlPlugin from 'next-intl/plugin';

const withNextIntl = createNextIntlPlugin('./i18n/request.ts');

const config: NextConfig = {
  output: 'standalone',
  reactStrictMode: true,
  poweredByHeader: false,
  transpilePackages: ['@convoy/price-engine', '@convoy/contracts'],
  outputFileTracingRoot: new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
};

export default withNextIntl(config);
