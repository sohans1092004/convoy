'use client';
import { useTranslations } from 'next-intl';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { api, call, type Schemas } from '@/lib/api';
import { ist } from '@/lib/format';
import { useCommand } from '@/lib/use-command';

type Clarification = Schemas['quote']['Clarification'];
const TYPES = ['DELIVERY_DATE', 'STOCK_COLOUR', 'PRICE_ITEM', 'INSURANCE', 'ACCESSORIES', 'DOCUMENTS', 'OTHER'] as const;
const field = 'rounded-lg border border-border bg-surface p-2';

function Item({ c, children }: { c: Clarification; children?: React.ReactNode }) {
  const t = useTranslations('clarify');
  return (
    <li className="flex flex-col gap-1 border-t border-border pt-2 text-sm">
      <span><Badge>{t(`type.${c.question_type}`)}</Badge> {c.question ?? <span className="text-muted">{t('removed')}</span>}</span>
      {c.answer ? <span><span className="font-semibold">{t('answer')}</span> {c.answer} <span className="text-xs text-muted">{ist(c.answered_at)}</span></span>
        : children ?? <span className="text-muted">{t('waiting')}</span>}
    </li>
  );
}

/** E071 (P5.E): a committed member asks about one final offer; answers are shown to everyone in the pool. No contact details. */
export function BuyerClarifications({ poolId, quoteId, items, onChange }: { poolId: string; quoteId: string; items: Clarification[]; onChange: () => void }) {
  const t = useTranslations('clarify');
  const [type, setType] = React.useState<string>('');
  const [text, setText] = React.useState('');
  const ask = useCommand(async (key: string) => {
    await call(api.POST('/pools/{poolId}/quotes/{quoteId}/clarifications', { params: { path: { poolId, quoteId } }, body: { question_type: type, text }, headers: { 'Idempotency-Key': key } }));
    setText('');
    onChange();
  });
  return (
    <details>
      <summary className="min-h-6 cursor-pointer text-sm font-semibold">{t('summary', { count: items.filter((c) => c.answer).length })}</summary>
      <ul className="mt-2 flex flex-col gap-2">{items.map((c) => <Item key={c.id} c={c} />)}</ul>
      <form className="mt-2 flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); void ask.execute(); }}>
        <label className="flex flex-col gap-1 text-sm">{t('typeLabel')}
          <select className={field} value={type} onChange={(e) => setType(e.target.value)} required>
            <option value="" disabled>{t('choose')}</option>
            {TYPES.map((k) => <option key={k} value={k}>{t(`type.${k}`)}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">{t('question')}
          <textarea className={field} maxLength={300} rows={2} value={text} onChange={(e) => setText(e.target.value)} required />
        </label>
        <p className="text-xs text-muted">{t('noContact')}</p>
        <Button type="submit" variant="secondary" loading={ask.state.kind === 'loading'}>{t('ask')}</Button>
        <CommandStatus state={ask.state} successText={t('asked')} />
      </form>
    </details>
  );
}

function AnswerForm({ id, onChange }: { id: string; onChange: () => void }) {
  const t = useTranslations('clarify');
  const [text, setText] = React.useState('');
  const answer = useCommand(async (key: string) => {
    await call(api.POST('/dealer/clarifications/{id}/answer', { params: { path: { id } }, body: { text }, headers: { 'Idempotency-Key': key } }));
    onChange();
  });
  return (
    <form className="flex flex-col gap-1" onSubmit={(e) => { e.preventDefault(); void answer.execute(); }}>
      <label className="flex flex-col gap-1">{t('yourAnswer')}
        <textarea className={field} maxLength={500} rows={2} value={text} onChange={(e) => setText(e.target.value)} required />
      </label>
      <Button type="submit" variant="secondary" loading={answer.state.kind === 'loading'}>{t('send')}</Button>
      <CommandStatus state={answer.state} successText={t('sent')} />
    </form>
  );
}

/** E072 (P5.E): the dealer answers each question once; the answer is shown to every buyer in the pool. */
export function DealerClarifications({ items, onChange }: { items: Clarification[]; onChange: () => void }) {
  const t = useTranslations('clarify');
  if (items.length === 0) return null;
  return (
    <section className="flex flex-col gap-2" aria-label={t('dealerTitle')}>
      <h2 className="font-semibold">{t('dealerTitle')}</h2>
      <p className="text-xs text-muted">{t('noContactDealer')}</p>
      <ul className="flex flex-col gap-2">{items.map((c) => <Item key={c.id} c={c}><AnswerForm id={c.id} onChange={onChange} /></Item>)}</ul>
    </section>
  );
}
