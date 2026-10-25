'use client';
import { useTranslations } from 'next-intl';
import { useRouter, useSearchParams } from 'next/navigation';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { api, call } from '@/lib/api';
import { passkeysSupported, signInWithPasskey } from '@/lib/passkey';
import { useCommand } from '@/lib/use-command';

function SignInForm() {
  const t = useTranslations('signIn');
  const router = useRouter();
  const next = useSearchParams().get('next');
  const [email, setEmail] = React.useState('');
  const [code, setCode] = React.useState('');
  const [step, setStep] = React.useState<'email' | 'code'>('email');
  const start = useCommand(async () => call(api.POST('/auth/otp/start', { body: { email } })));
  const verify = useCommand(async () => call(api.POST('/auth/otp/verify', { body: { email, code } })));
  const passkey = useCommand(signInWithPasskey);
  const [canPasskey, setCanPasskey] = React.useState(false);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- feature detection must run after hydration (no window on the server)
  React.useEffect(() => setCanPasskey(passkeysSupported()), []);
  const done = (roles: string[]) => {
    router.push(next ?? (roles.some((x) => x.startsWith('dealer')) ? '/dealer' : '/'));
    router.refresh();
  };

  return (
    <Card className="mx-auto flex w-full max-w-md flex-col gap-4">
      <h1 className="text-xl font-bold">{t('title')}</h1>
      {step === 'email' ? (
        <form className="flex flex-col gap-3" onSubmit={async (e) => { e.preventDefault(); if (await start.execute()) setStep('code'); }}>
          <Input label={t('email')} type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} hint={t('emailHint')} />
          <Button type="submit" loading={start.state.kind === 'loading'}>{t('sendCode')}</Button>
          <CommandStatus state={start.state} />
          {canPasskey ? (
            <>
              <Button type="button" variant="secondary" loading={passkey.state.kind === 'loading'}
                onClick={async () => { const r = await passkey.execute(); if (r) done(r.data.user.roles); }}>{t('passkey')}</Button>
              <CommandStatus state={passkey.state} />
            </>
          ) : null}
        </form>
      ) : (
        <form className="flex flex-col gap-3" onSubmit={async (e) => {
          e.preventDefault();
          const r = await verify.execute();
          if (r) done(r.data.user.roles);
        }}>
          <p className="text-sm">{t.rich('sentTo', { email, strong: (c) => <strong>{c}</strong> })}</p>
          <Input label={t('code')} inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} required value={code} onChange={(e) => setCode(e.target.value)}
            error={verify.state.kind === 'error' ? verify.state.problem.detail : undefined} />
          <Button type="submit" loading={verify.state.kind === 'loading'}>{t('submit')}</Button>
          <Button type="button" variant="link" onClick={() => { setStep('email'); setCode(''); verify.reset(); }}>{t('otherEmail')}</Button>
        </form>
      )}
    </Card>
  );
}

export default function SignInPage() {
  return <React.Suspense><SignInForm /></React.Suspense>;
}
