'use client';
import { useTranslations } from 'next-intl';
import Link from 'next/link';
import * as React from 'react';
import { AuditViewer } from '@/components/audit-viewer';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { ist, rupees } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

type Refund = Schemas['payment']['QueuedRefund'];
type Registration = Schemas['dealer']['Registration'];

/** E114-E117: a manual refund is asked for by one admin (evidence + note) and approved by a different one (AMENDMENT-001). */
function RefundRow({ r, refresh }: { r: Refund; refresh: () => Promise<unknown> }) {
  const t = useTranslations('admin');
  const [evidence, setEvidence] = React.useState('');
  const [note, setNote] = React.useState('');
  const retry = useCommand(async (key: string) => { await call(api.POST('/admin/refunds/{id}/retry', { params: { path: { id: r.id } }, headers: { 'Idempotency-Key': key } })); await refresh(); });
  const ask = useCommand(async (key: string) => {
    await call(api.POST('/admin/refunds/{id}/resolve-manually', { params: { path: { id: r.id } }, body: { evidence_ref: evidence, note }, headers: { 'Idempotency-Key': key } }));
    await refresh();
  });
  const approve = useCommand(async (key: string) => { await call(api.POST('/admin/refunds/{id}/approve', { params: { path: { id: r.id } }, headers: { 'Idempotency-Key': key } })); await refresh(); });
  return (
    <Card className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono">{r.id.slice(-8).toUpperCase()}</span>
        <Badge tone="warning">{r.state.replace(/_/g, ' ').toLowerCase()}</Badge>
        <span className="tabular">{rupees(r.amount_paise)}</span>
        <span className="text-sm text-muted">{r.reason_code} · {t('attempts', { n: r.attempts })}</span>
      </div>
      {r.failure_reason ? <CardText>{r.failure_reason}</CardText> : null}
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" loading={retry.state.kind === 'loading'} onClick={() => retry.execute()}>{t('retry')}</Button>
      </div>
      {r.requested_by ? (
        <div className="flex flex-col gap-2">
          <CardText>{t('awaitingSecond')}</CardText>
          <Button className="self-start" loading={approve.state.kind === 'loading'} onClick={() => approve.execute()}>{t('approve')}</Button>
        </div>
      ) : (
        <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); ask.execute(); }}>
          <label className="flex flex-col gap-1 text-sm">{t('evidence')}<input required maxLength={200} className="rounded-lg border border-border bg-surface p-2" value={evidence} onChange={(e) => setEvidence(e.target.value)} /></label>
          <label className="flex flex-col gap-1 text-sm">{t('note')}<input required maxLength={500} className="rounded-lg border border-border bg-surface p-2" value={note} onChange={(e) => setNote(e.target.value)} /></label>
          <Button type="submit" variant="secondary" className="self-start" loading={ask.state.kind === 'loading'}>{t('askManual')}</Button>
        </form>
      )}
      <CommandStatus state={retry.state} /><CommandStatus state={ask.state} /><CommandStatus state={approve.state} />
    </Card>
  );
}

function VerificationRow({ d, refresh }: { d: Registration; refresh: () => Promise<unknown> }) {
  const t = useTranslations('admin');
  const [reason, setReason] = React.useState('');
  const decide = useCommand(async (key: string, decision: 'APPROVE' | 'REJECT') => {
    await call(api.POST('/admin/dealerships/{id}/verify', { params: { path: { id: d.id } }, body: { decision, reason: reason || null }, headers: { 'Idempotency-Key': key, 'If-Match': `"${d.version}"` } }));
    await refresh();
  });
  return (
    <Card className="flex flex-col gap-2">
      <CardTitle as="h3">{d.trade_name}</CardTitle>
      <CardText>{d.legal_name} · GSTIN {d.gstin} · {d.city} · {d.brands.join(', ')}</CardText>
      <label className="flex flex-col gap-1 text-sm">{t('rejectReason')}<input maxLength={500} className="rounded-lg border border-border bg-surface p-2" value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      <div className="flex gap-2">
        <Button loading={decide.state.kind === 'loading'} onClick={() => decide.execute('APPROVE')}>{t('verify')}</Button>
        <Button variant="secondary" disabled={!reason} loading={decide.state.kind === 'loading'} onClick={() => decide.execute('REJECT')}>{t('reject')}</Button>
      </div>
      <CommandStatus state={decide.state} />
    </Card>
  );
}

export function AdminConsole() {
  const t = useTranslations('admin');
  const [tab, setTab] = React.useState<'refunds' | 'disputes' | 'verification' | 'reconciliation' | 'audit'>('refunds');
  const refunds = useSnapshot(() => call(api.GET('/admin/refunds')), [], 15_000);
  const disputes = useSnapshot(() => call(api.GET('/admin/disputes')), [], 15_000);
  const queue = useSnapshot(() => call(api.GET('/admin/dealerships', { params: { query: { status: 'PENDING_VERIFICATION' } } })), [], 30_000);
  const runs = useSnapshot(() => call(api.GET('/admin/reconciliation/runs')), [], 60_000);
  const TABS = ['refunds', 'disputes', 'verification', 'reconciliation', 'audit'] as const;
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">{t('title')}</h1>
      <p className="text-sm text-muted">{t('audited')}</p>
      <div role="tablist" aria-label={t('title')} className="flex flex-wrap gap-2">
        {TABS.map((k) => (
          <Button key={k} role="tab" aria-selected={tab === k} variant={tab === k ? 'primary' : 'secondary'} onClick={() => setTab(k)}>{t(`tab.${k}`)}</Button>
        ))}
      </div>
      <div role="tabpanel" className="flex flex-col gap-3">
        {tab === 'refunds' ? (refunds.data ? (refunds.data.refunds.length === 0 ? <Card><p>{t('noRefunds')}</p></Card>
          : refunds.data.refunds.map((r) => <RefundRow key={r.id} r={r} refresh={refunds.refresh} />)) : <Skeleton className="h-32" label={t('loading')} />) : null}
        {tab === 'disputes' ? (disputes.data ? (disputes.data.disputes.length === 0 ? <Card><p>{t('noDisputes')}</p></Card> : disputes.data.disputes.map((d) => (
          <Card key={d.id} className="flex flex-wrap items-center gap-2">
            <Link className="font-mono underline" href={`/disputes/${d.id}`}>{d.id.slice(-8).toUpperCase()}</Link>
            <Badge tone={d.state === 'OPEN' ? 'warning' : 'neutral'}>{d.state.replace(/_/g, ' ').toLowerCase()}</Badge>
            <span>{d.category}</span><span className="text-sm text-muted">{t('openedBy', { by: d.opened_by })} · {ist(d.created_at)}</span>
            {d.proposed_outcome ? <Badge tone="info">{t('proposed', { outcome: d.proposed_outcome })}</Badge> : null}
          </Card>
        ))) : <Skeleton className="h-32" label={t('loading')} />) : null}
        {tab === 'verification' ? (queue.data ? (queue.data.dealerships.length === 0 ? <Card><p>{t('noDealers')}</p></Card>
          : queue.data.dealerships.map((d) => <VerificationRow key={d.id} d={d} refresh={queue.refresh} />)) : <Skeleton className="h-32" label={t('loading')} />) : null}
        {tab === 'reconciliation' ? (runs.data ? (runs.data.runs.length === 0 ? <Card><p>{t('noRuns')}</p></Card> : runs.data.runs.map((r) => (
          <Card key={r.id} className="flex flex-col gap-1">
            <CardTitle as="h3">{ist(r.started_at)} · {t('runSummary', { checked: r.checked, mismatches: r.mismatch_count })}</CardTitle>
            {r.mismatches.length > 0 ? <ul className="text-sm">{r.mismatches.map((m, i) => <li key={i}><Badge tone="warning">{m.mismatch_class}</Badge> {m.detail}</li>)}</ul> : null}
          </Card>
        ))) : <Skeleton className="h-32" label={t('loading')} />) : null}
        {tab === 'audit' ? <AuditViewer /> : null}
      </div>
    </div>
  );
}
