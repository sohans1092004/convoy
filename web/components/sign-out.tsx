'use client';
import { api } from '@/lib/api';
import { Button } from './ui/button';

export function SignOutButton({ label }: { label: string }) {
  return (
    <Button variant="link" onClick={async () => { await api.POST('/auth/logout'); window.location.href = '/'; }}>
      {label}
    </Button>
  );
}
