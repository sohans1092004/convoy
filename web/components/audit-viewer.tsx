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

type Entry = Schemas['audit']['Entry'];
const FIELDS = ['actor', 'aggregate_type', 'aggregate_id', 'event_type'] as const;

/** E163: search only on request (never polled): every read is itself written to the audit log. */
export function AuditViewer() {
  const t = useTranslations('admin.audit');
  const [f, setF] = React.useState<Record<string, string>>({});
  const [entries, setEntries] = React.useState<Entry[] | null>(null);
  const search = useCommand(async () => {
    const q = Object.fromEntries(Object.entries(f).filter(([, v]) => v));
    for (const k of ['from', 'to']) if (q[k]) q[k] = new Date(q[k]).toISOString(); // datetime-local is the viewer's local time
    const { data } = await call(api.GET('/admin/audit', { params: { query: q } }));
    setEntries(data.entries);
  });
  const input = (k: string, type = 'text') => (
    <label key={k} className="flex flex-col gap-1 text-sm">{t(k)}
      <input type={type} className="rounded-lg border border-border bg-surface p-2" value={f[k] ?? ''} onChange={(e) => setF({ ...f, [k]: e.target.value })} />
    </label>
  );
  return (
    <div className="flex flex-col gap-3">
      <form className="grid gap-2 sm:grid-cols-3" onSubmit={(e) => { e.preventDefault(); void search.execute(); }}>
        {FIELDS.map((k) => input(k))}
        {input('from', 'datetime-local')}
        {input('to', 'datetime-local')}
        <Button type="submit" loading={search.state.kind === 'loading'} className="sm:col-span-3">{t('search')}</Button>
      </form>
      <p className="text-xs text-muted">{t('readsAudited')}</p>
      <CommandStatus state={search.state} successText={t('found', { count: entries?.length ?? 0 })} />
      {entries?.map((e) => (
        <Card key={e.seq} className="flex flex-col gap-1 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono">#{e.seq}</span>
            <span className="font-semibold">{e.event_type}</span>
            {e.sensitive ? <Badge tone="warning">{t('sensitive')}</Badge> : null}
            <span className="text-muted">{ist(e.occurred_at)}</span>
          </div>
          <span>{t('by', { actor: e.actor })} · {e.aggregate_type ?? '-'} {e.aggregate_id ?? ''}{e.aggregate_version != null ? ` v${e.aggregate_version}` : ''}</span>
          <span className="break-all font-mono text-xs text-muted">{e.payload}</span>
        </Card>
      ))}
    </div>
  );
}
