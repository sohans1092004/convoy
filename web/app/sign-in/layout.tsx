import { getTranslations } from 'next-intl/server';
import { TryDemo } from '@/components/demo';

// P6.B: without SES production access the public demo has no email sign-in (passkeys still work for real admins).
export default async function SignInLayout({ children }: { children: React.ReactNode }) {
  const t = await getTranslations('demo');
  if (process.env.EMAIL_SIGN_IN !== 'false') return children;
  return (
    <div className="mx-auto flex w-full max-w-md flex-col gap-4">
      <p role="note" className="rounded-lg border border-border bg-surface p-3 text-sm">{t('emailOff')}</p>
      {process.env.DEMO_PUBLIC === 'true' ? <TryDemo /> : null}
      {children}
    </div>
  );
}
