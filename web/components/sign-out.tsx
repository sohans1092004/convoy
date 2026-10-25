'use client';
import { api } from '@/lib/api';
import { Button } from './ui/button';

export function SignOutButton({ label }: { label: string }) {
  const signOut = async () => {
    await api.POST('/auth/logout');
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- a full reload drops every client cache after sign-out
    window.location.href = '/';
  };
  return (
    <Button variant="link" onClick={signOut}>
      {label}
    </Button>
  );
}
