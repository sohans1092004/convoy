'use client';
import { useRouter, useSearchParams } from 'next/navigation';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { api, call } from '@/lib/api';
import { useCommand } from '@/lib/use-command';

function SignInForm() {
  const router = useRouter();
  const next = useSearchParams().get('next');
  const [email, setEmail] = React.useState('');
  const [code, setCode] = React.useState('');
  const [step, setStep] = React.useState<'email' | 'code'>('email');
  const start = useCommand(async () => call(api.POST('/auth/otp/start', { body: { email } })));
  const verify = useCommand(async () => call<{ user: { roles: string[] } }>(api.POST('/auth/otp/verify', { body: { email, code } })));

  return (
    <Card className="mx-auto flex w-full max-w-md flex-col gap-4">
      <h1 className="text-xl font-bold">Sign in</h1>
      {step === 'email' ? (
        <form className="flex flex-col gap-3" onSubmit={async (e) => { e.preventDefault(); if (await start.execute()) setStep('code'); }}>
          <Input label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} hint="We email you a 6-digit code. No password." />
          <Button type="submit" loading={start.state.kind === 'loading'}>Email me a code</Button>
          <CommandStatus state={start.state} />
        </form>
      ) : (
        <form className="flex flex-col gap-3" onSubmit={async (e) => {
          e.preventDefault();
          const r = await verify.execute();
          if (r) {
            const roles = r.data.user.roles;
            router.push(next ?? (roles.some((x) => x.startsWith('dealer')) ? '/dealer' : '/'));
            router.refresh();
          }
        }}>
          <p className="text-sm">We sent a code to <strong>{email}</strong>. It expires in 10 minutes.</p>
          <Input label="6-digit code" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} required value={code} onChange={(e) => setCode(e.target.value)}
            error={verify.state.kind === 'error' ? verify.state.problem.detail : undefined} />
          <Button type="submit" loading={verify.state.kind === 'loading'}>Sign in</Button>
          <Button type="button" variant="link" onClick={() => { setStep('email'); setCode(''); verify.reset(); }}>Use a different email</Button>
        </form>
      )}
    </Card>
  );
}

export default function SignInPage() {
  return <React.Suspense><SignInForm /></React.Suspense>;
}
