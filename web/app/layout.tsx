import type { Metadata, Viewport } from 'next';
import { NextIntlClientProvider } from 'next-intl';
import { getLocale, getTranslations } from 'next-intl/server';
import { cookies } from 'next/headers';
import Link from 'next/link';
import { Toaster } from '@/components/ui/toast';
import { LocaleSwitch } from '@/components/locale-switch';
import { SignOutButton } from '@/components/sign-out';
import { QueryProvider } from '@/lib/query';
import './globals.css';

export const metadata: Metadata = { title: 'Convoy', description: 'Group car buying with sealed, itemised dealer offers.' };
export const viewport: Viewport = { width: 'device-width', initialScale: 1 };

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const t = await getTranslations('nav');
  const locale = await getLocale();
  const jar = await cookies();
  const signedIn = Boolean(jar.get('cv_sid'));
  const roles = (jar.get('cv_roles')?.value ?? '').split(',');
  const dealer = roles.some((r) => r.startsWith('dealer'));
  return (
    <html lang={locale === 'hi' ? 'hi-IN' : 'en-IN'}>
      <body className="min-h-screen">
        <NextIntlClientProvider>
          <QueryProvider>
          <Toaster>
            <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-fg focus:px-3 focus:py-2 focus:text-bg">Skip to content</a>
            <p className="bg-fg py-1 text-center text-xs font-bold tracking-wide text-bg">{t('testMode')}</p>
            <header className="border-b border-border bg-surface">
              <nav aria-label="Main" className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-5 gap-y-2 px-4 py-3">
                <Link href="/" className="text-lg font-extrabold tracking-tight">{t('brand')}</Link>
                <Link href="/" className="text-sm font-medium">{t('cars')}</Link>
                {signedIn && !dealer ? <Link href="/saved" className="text-sm font-medium">{t('saved')}</Link> : null}
                {signedIn && !dealer ? <Link href="/orders" className="text-sm font-medium">{t('orders')}</Link> : null}
                {dealer ? <Link href="/dealer" className="text-sm font-medium">{t('dealer')}</Link> : null}
                {dealer ? <Link href="/dealer/bookings" className="text-sm font-medium">{t('bookings')}</Link> : null}
                <span className="ml-auto flex items-center gap-4"><LocaleSwitch />{signedIn ? <SignOutButton label={t('signOut')} /> : <Link href="/sign-in" className="text-sm font-semibold text-primary">{t('signIn')}</Link>}</span>
              </nav>
            </header>
            <main id="main" className="mx-auto max-w-5xl px-4 py-6 sm:py-8">{children}</main>
          </Toaster>
          </QueryProvider>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
