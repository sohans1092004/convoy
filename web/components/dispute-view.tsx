'use client';
import { useTranslations } from 'next-intl';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { ist } from '@/lib/format';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

type Dispute = Schemas['order']['Dispute'];
const TYPES = ['image/jpeg', 'image/png', 'application/pdf'];
const MAX_BYTES = 10 * 1024 * 1024;
const OUTCOMES = ['REFUND_BUYER', 'CANCEL_BOOKING', 'DEALER_WARNING', 'PRICE_ADJUSTMENT_RECORDED', 'NO_ACTION'] as const;

function roles(): string[] {
  if (typeof document === 'undefined') return [];
  return decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('cv_roles='))?.split('=')[1] ?? '').split(',');
}

/** E097: ask for a pre-signed PUT, then upload the file straight to storage (type and size checked before and after). */
function EvidenceUpload({ d, refresh }: { d: Dispute; refresh: () => Promise<unknown> }) {
  const t = useTranslations('dispute');
  const [file, setFile] = React.useState<File | null>(null);
  const [problem, setProblem] = React.useState<string | null>(null);
  const upload = useCommand(async (key: string) => {
    if (!file) return;
    const r = await call(api.POST('/disputes/{id}/evidence-uploads', { params: { path: { id: d.id } }, body: { file_name: file.name, content_type: file.type, size_bytes: file.size }, headers: { 'Idempotency-Key': key } }));
    const put = await fetch(r.data.upload_url, { method: 'PUT', headers: r.data.headers, body: file });
    if (!put.ok) throw new Error(t('uploadFailed'));
    setFile(null);
    await refresh();
  });
  return (
    <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); upload.execute(); }}>
      <label className="flex flex-col gap-1 text-sm">{t('addFile')}
        <input type="file" accept={TYPES.join(',')} onChange={(e) => {
          const f = e.target.files?.[0] ?? null;
          setProblem(f && !TYPES.includes(f.type) ? t('badType') : f && f.size > MAX_BYTES ? t('tooBig') : null);
          setFile(f);
        }} />
      </label>
      {problem ? <p role="alert" className="text-sm text-danger">{problem}</p> : null}
      <Button type="submit" variant="secondary" className="self-start" disabled={!file || !!problem || d.evidence.length >= 10} loading={upload.state.kind === 'loading'}>{t('upload')}</Button>
      <CommandStatus state={upload.state} successText={t('uploaded')} />
    </form>
  );
}

function AdminActions({ d, refresh }: { d: Dispute; refresh: () => Promise<unknown> }) {
  const t = useTranslations('dispute');
  const [outcome, setOutcome] = React.useState<string>(d.proposed_outcome ?? 'NO_ACTION');
  const [note, setNote] = React.useState('');
  const [party, setParty] = React.useState<'BUYER' | 'DEALER'>('DEALER');
  const act = useCommand(async (key: string, what: 'assign' | 'info' | 'resolve') => {
    const h = { 'Idempotency-Key': key };
    if (what === 'assign') await call(api.POST('/admin/disputes/{id}/assign', { params: { path: { id: d.id } }, body: { owner_id: null }, headers: h }));
    if (what === 'info') await call(api.POST('/admin/disputes/{id}/request-info', { params: { path: { id: d.id } }, body: { party, question: note }, headers: h }));
    if (what === 'resolve') await call(api.POST('/admin/disputes/{id}/resolve', { params: { path: { id: d.id } }, body: { outcome, note }, headers: h }));
    setNote('');
    await refresh();
  });
  return (
    <Card className="flex flex-col gap-2">
      <CardTitle as="h3">{t('adminTitle')}</CardTitle>
      {d.state === 'OPEN' ? <Button className="self-start" loading={act.state.kind === 'loading'} onClick={() => act.execute('assign')}>{t('assign')}</Button> : null}
      {d.state === 'UNDER_REVIEW' ? (
        <>
          <label className="flex flex-col gap-1 text-sm">{t('note')}<textarea rows={2} maxLength={2000} className="rounded-lg border border-border bg-surface p-2" value={note} onChange={(e) => setNote(e.target.value)} /></label>
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1 text-sm">{t('askParty')}
              <select className="rounded-lg border border-border bg-surface p-2" value={party} onChange={(e) => setParty(e.target.value as 'BUYER' | 'DEALER')}><option value="DEALER">{t('dealer')}</option><option value="BUYER">{t('buyer')}</option></select>
            </label>
            <Button variant="secondary" disabled={!note} loading={act.state.kind === 'loading'} onClick={() => act.execute('info')}>{t('requestInfo')}</Button>
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1 text-sm">{t('outcome')}
              <select className="rounded-lg border border-border bg-surface p-2" value={outcome} onChange={(e) => setOutcome(e.target.value)}>{OUTCOMES.map((o) => <option key={o} value={o}>{t(`outcomes.${o}`)}</option>)}</select>
            </label>
            <Button disabled={!note} loading={act.state.kind === 'loading'} onClick={() => act.execute('resolve')}>{d.proposed_outcome === outcome ? t('confirmOutcome') : t('resolve')}</Button>
          </div>
          {d.proposed_outcome ? <CardText>{t('proposedBy', { outcome: d.proposed_outcome })}</CardText> : null}
          <CardText>{t('moneyRule')}</CardText>
        </>
      ) : null}
      <CommandStatus state={act.state} />
    </Card>
  );
}

export function DisputeView({ disputeId }: { disputeId: string }) {
  const t = useTranslations('dispute');
  const tl = useTranslations('lifecycle');
  const snap = useSnapshot(() => call(api.GET('/disputes/{id}', { params: { path: { id: disputeId } } })), [disputeId], 15_000);
  const [text, setText] = React.useState('');
  const r = roles();
  const isBuyer = r.includes('buyer'), isAdmin = r.includes('platform_admin');
  const party = useCommand(async (key: string, what: 'respond' | 'appeal' | 'withdraw') => {
    const h = { 'Idempotency-Key': key };
    if (what === 'respond') await call(api.POST('/disputes/{id}/responses', { params: { path: { id: disputeId } }, body: { text }, headers: h }));
    if (what === 'appeal') await call(api.POST('/disputes/{id}/appeal', { params: { path: { id: disputeId } }, body: { text }, headers: h }));
    if (what === 'withdraw') await call(api.POST('/disputes/{id}/withdraw', { params: { path: { id: disputeId } }, headers: h }));
    setText('');
    await snap.refresh();
  });
  const d = snap.data;
  if (snap.loading && !d) return <Skeleton className="h-64" label={t('loading')} />;
  if (!d) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{snap.problem?.detail ?? t('loadFailed')}</p>;
  const closed = ['CLOSED', 'WITHDRAWN'].includes(d.state);
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold">{t('title', { category: tl(`category.${d.category}`) })}</h1>
        <Badge tone={d.state === 'RESOLVED' ? 'success' : 'warning'}>{d.state.replace(/_/g, ' ').toLowerCase()}</Badge>
      </div>
      <Card className="flex flex-col gap-1">
        <CardText>{d.description}</CardText>
        <CardText>{d.opened_by === 'SYSTEM' ? t('openedBySystem') : t('openedByBuyer')} · {ist(d.created_at)}</CardText>
        {d.awaiting_party ? <CardText>{t('awaiting', { party: d.awaiting_party, by: ist(d.respond_by) })}</CardText> : null}
        {d.outcome ? <CardText>{t('resolvedAs', { outcome: t(`outcomes.${d.outcome}`), until: ist(d.appeal_until) })}</CardText> : null}
      </Card>
      <Card className="flex flex-col gap-2">
        <CardTitle as="h3">{t('evidence', { n: d.evidence.length })}</CardTitle>
        {d.evidence.length > 0 ? <ul className="text-sm">{d.evidence.map((e) => <li key={e.id}><a className="underline" href={e.download_url} target="_blank" rel="noreferrer">{e.file_name}</a> · {e.uploader_role.toLowerCase()} · {ist(e.created_at)}</li>)}</ul> : null}
        {!closed && !isAdmin ? <EvidenceUpload d={d} refresh={snap.refresh} /> : null}
      </Card>
      <Card className="flex flex-col gap-2">
        <CardTitle as="h3">{t('history')}</CardTitle>
        <ol className="flex flex-col gap-1 text-sm">
          {d.history.map((h, i) => <li key={i}><span className="text-muted">{ist(h.at)}</span> · {h.actor_role.toLowerCase()} · {h.action.replace(/_/g, ' ').toLowerCase()}{h.note ? `: ${h.note}` : ''}</li>)}
        </ol>
      </Card>
      {!closed && !isAdmin ? (
        <Card className="flex flex-col gap-2">
          <label className="flex flex-col gap-1 text-sm">{d.state === 'RESOLVED' ? t('appealWhy') : t('message')}
            <textarea rows={3} maxLength={4000} className="rounded-lg border border-border bg-surface p-2" value={text} onChange={(e) => setText(e.target.value)} />
          </label>
          <div className="flex flex-wrap gap-2">
            {d.state === 'RESOLVED' && isBuyer ? <Button disabled={!text || d.appeal_count > 0} loading={party.state.kind === 'loading'} onClick={() => party.execute('appeal')}>{t('appeal')}</Button>
              : <Button disabled={!text} loading={party.state.kind === 'loading'} onClick={() => party.execute('respond')}>{t('send')}</Button>}
            {isBuyer && d.opened_by === 'BUYER' && d.state !== 'RESOLVED' ? <Button variant="secondary" loading={party.state.kind === 'loading'} onClick={() => party.execute('withdraw')}>{t('withdraw')}</Button> : null}
          </div>
          <CommandStatus state={party.state} />
        </Card>
      ) : null}
      {isAdmin && !closed ? <AdminActions d={d} refresh={snap.refresh} /> : null}
    </div>
  );
}
