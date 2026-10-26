'use client';
import { useTranslations } from 'next-intl';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { api, call, type Schemas } from '@/lib/api';
import { ist } from '@/lib/format';
import { useCommand } from '@/lib/use-command';

type Consents = Schemas['identity']['Consents'];
type Export = Schemas['identity']['ExportStatus'];
type Erasure = Schemas['identity']['ErasureStatus'];

/** P5.D: privacy notice per purpose with my choice and its history (E168/E014), my data as one file (E015/E164), delete my account (E016/E166). */
export function DataRights() {
  const t = useTranslations('privacy');
  const [consents, setConsents] = React.useState<Consents | null>(null);
  const [failed, setFailed] = React.useState(false);
  React.useEffect(() => { call(api.GET('/me/consents')).then((r) => setConsents(r.data), () => setFailed(true)); }, []);
  const [exp, setExp] = React.useState<Export | null>(null);
  const [erasure, setErasure] = React.useState<Erasure | null>(null);
  const [confirmDelete, setConfirmDelete] = React.useState(false);

  const choose = useCommand(async (_key: string, purpose: string, granted: boolean) => {
    await call(api.PUT('/me/consents/{purpose}', { params: { path: { purpose } }, body: { granted, notice_version: consents!.notice_version } }));
    setConsents((await call(api.GET('/me/consents'))).data);
  });
  const requestExport = useCommand(async (key: string) => {
    setExp((await call(api.POST('/me/data-export', { headers: { 'Idempotency-Key': key } }))).data);
  });
  const refreshExport = useCommand(async () => {
    setExp((await call(api.GET('/me/data-export/{id}', { params: { path: { id: exp!.export_id } } }))).data);
  });
  const requestErasure = useCommand(async (key: string) => {
    setErasure((await call(api.POST('/me/erasure', { headers: { 'Idempotency-Key': key } }))).data);
  });

  return (
    <>
      <h2 className="font-semibold">{t('title')}</h2>
      {!consents ? <p className="text-sm">{failed ? t('unavailable') : t('loading')}</p> : consents.purposes.map((p) => (
        <Card key={p.purpose} className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-semibold">{p.title}</h3>
            <Badge tone={p.granted === false ? 'warning' : 'neutral'}>{p.granted == null ? t('notAsked') : p.granted ? t('granted') : t('withdrawn')}</Badge>
          </div>
          <p className="text-sm">{p.notice}</p>
          <p className="text-xs text-muted">{t('data', { list: p.data.join(', ') })} · {t('retention', { text: p.retention })}</p>
          {p.withdrawable ? (
            <div className="flex flex-wrap gap-2">
              {p.granted !== false ? <Button variant="secondary" onClick={() => choose.execute(p.purpose, false)}>{t('withdraw')}</Button>
                : <Button variant="secondary" onClick={() => choose.execute(p.purpose, true)}>{t('give')}</Button>}
            </div>
          ) : <p className="text-xs text-muted">{t('required')}</p>}
          {p.history.length > 0 ? (
            <details className="text-xs"><summary>{t('history', { count: p.history.length })}</summary>
              <ul>{p.history.map((h, i) => <li key={i}>{ist(h.at)}: {h.granted ? t('granted') : t('withdrawn')} ({h.notice_version})</li>)}</ul>
            </details>
          ) : null}
        </Card>
      ))}
      <CommandStatus state={choose.state} successText={t('saved')} />

      <h2 className="font-semibold">{t('exportTitle')}</h2>
      <p className="text-sm">{t('exportText')}</p>
      {!exp ? <Button loading={requestExport.state.kind === 'loading'} onClick={() => requestExport.execute()}>{t('exportButton')}</Button> : (
        <Card className="flex flex-col gap-2 text-sm">
          <p role="status">{exp.state === 'READY' ? t('exportReady') : t('exportPending', { done: exp.parts_received, of: exp.parts_expected })}</p>
          {exp.download_url ? <a className="font-semibold underline" href={exp.download_url} download>{t('download')}</a> : null}
          {exp.link_expires_at ? <p className="text-xs text-muted">{t('linkExpires', { at: ist(exp.link_expires_at) })}</p> : null}
          <Button variant="secondary" loading={refreshExport.state.kind === 'loading'} onClick={() => refreshExport.execute()}>{t('refresh')}</Button>
        </Card>
      )}
      <CommandStatus state={requestExport.state} />

      <h2 className="font-semibold">{t('deleteTitle')}</h2>
      <p className="text-sm">{t('deleteText')}</p>
      {erasure ? (
        <p className="text-sm" role="status">{t('deleteProgress', { done: erasure.confirmed.length, of: erasure.confirmed.length + erasure.pending.length })}</p>
      ) : confirmDelete ? (
        <div className="flex flex-wrap gap-2">
          <Button variant="danger" loading={requestErasure.state.kind === 'loading'} onClick={() => requestErasure.execute()}>{t('deleteConfirm')}</Button>
          <Button variant="secondary" onClick={() => setConfirmDelete(false)}>{t('cancel')}</Button>
        </div>
      ) : <Button variant="secondary" onClick={() => setConfirmDelete(true)}>{t('deleteButton')}</Button>}
      <CommandStatus state={requestErasure.state} />
    </>
  );
}
