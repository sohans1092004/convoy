'use client';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { DataRights } from '@/components/data-rights';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { passkeysSupported, registerPasskey } from '@/lib/passkey';
import { useCommand } from '@/lib/use-command';

export default function AccountPage() {
  const add = useCommand(registerPasskey);
  const [supported, setSupported] = React.useState(true);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- feature detection must run after hydration (no window on the server)
  React.useEffect(() => setSupported(passkeysSupported()), []);
  return (
    <Card className="mx-auto flex w-full max-w-md flex-col gap-3">
      <h1 className="text-xl font-bold">Your account</h1>
      <h2 className="font-semibold">Passkeys</h2>
      <p className="text-sm">Sign in with your fingerprint, face or screen lock instead of an email code. The key stays on your device.</p>
      {supported ? (
        <Button type="button" loading={add.state.kind === 'loading'} onClick={() => add.execute()}>Add a passkey</Button>
      ) : (
        <p className="text-sm">This browser does not support passkeys. Keep using the email code.</p>
      )}
      <CommandStatus state={add.state} successText="Passkey added. Next time, choose “Sign in with a passkey”." />
      <DataRights />
    </Card>
  );
}
