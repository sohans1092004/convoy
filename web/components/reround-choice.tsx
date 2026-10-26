'use client';
import { useTranslations } from 'next-intl';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { api, call } from '@/lib/api';
import { useCommand } from '@/lib/use-command';

/** AMENDMENT-002: the offer went VOID and the pool tries once more. Keep the place (no new payment) or take the refund; no answer means refund. */
export function ReroundChoice({ poolId, membership, onDone }: {
  poolId: string; membership: { version: number; choice_deadline?: string | null }; onDone: () => Promise<unknown>;
}) {
  const t = useTranslations('reround');
  const choose = useCommand(async (key: string, choice: 'KEEP' | 'REFUND') => {
    await call(api.PUT('/pools/{id}/members/me/reround-choice', {
      params: { path: { id: poolId } }, body: { choice }, headers: { 'Idempotency-Key': key, 'If-Match': `"${membership.version}"` },
    }));
    await onDone();
  });
  return (
    <Card emphasis className="flex flex-col gap-3" aria-labelledby="reround-title">
      <CardTitle id="reround-title">{t('title')}</CardTitle>
      <CardText>{t('body')}</CardText>
      <ul className="list-disc pl-5 text-sm">
        <li>{t('keep')}</li>
        <li>{t('refund')}</li>
        <li>{t('silence')}</li>
      </ul>
      {membership.choice_deadline ? <Countdown deadline={membership.choice_deadline} label={t('deadline')} /> : null}
      <div className="flex flex-wrap gap-2">
        <Button loading={choose.state.kind === 'loading'} onClick={() => choose.execute('KEEP')}>{t('keepCta')}</Button>
        <Button variant="secondary" loading={choose.state.kind === 'loading'} onClick={() => choose.execute('REFUND')}>{t('refundCta')}</Button>
      </div>
      <CommandStatus state={choose.state} successText={t('saved')} />
    </Card>
  );
}
