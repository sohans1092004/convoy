'use client';
import { compute, rules } from '@convoy/price-engine';
import Link from 'next/link';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import type { Request, RoundVariant } from '@/components/dealer-inbox';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Dialog, DialogClose, DialogContent } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, etagVersion, type Schemas } from '@/lib/api';
import { rupees } from '@/lib/format';
import { useCommand } from '@/lib/use-command';

// Functional quote form for P1 (the full builder is P3). Amounts are typed in rupees and stored in paise.
interface Line { variant_id: string; include: boolean; ex: string; reg: string; insMode: 'estimate' | 'dealer'; od: string; tp: string; gst: string; binding: boolean; units: string; colour: string; delivery: string }
interface TierRow { min: string; max: string; discount: string }
type Quote = Schemas['quote']['DealerQuote'];

const paise = (rupeesText: string) => (rupeesText.trim() === '' ? null : Math.round(Number(rupeesText.replace(/,/g, '')) * 100));
const inDays = (d: number) => new Date(Date.now() + d * 864e5).toISOString().slice(0, 10);

function toDraft(lines: Line[], tiers: TierRow[], minF: string, maxUnits: string) {
  return {
    variants: lines.filter((l) => l.include).map((l) => ({
      variant_id: l.variant_id, ex_showroom_paise: paise(l.ex), registration_paise: paise(l.reg),
      insurance: l.insMode === 'dealer' ? { od_paise: paise(l.od), tp_paise: paise(l.tp), gst_paise: paise(l.gst), binding: l.binding, declared_cover: l.binding ? { idv_bp: 9500, addons: [] } : null } : null,
      insurance_addons: [], accessories: [], fees: [], extended_warranty: null, other_charges: [], stock: { units: Number(l.units || 0), colours: l.colour ? [l.colour] : [], delivery_date: l.delivery || null },
    })),
    tiers: tiers.map((t) => ({ min: Number(t.min), max: t.max.trim() === '' ? null : Number(t.max), discount_paise: paise(t.discount) ?? 0 })),
    min_fulfilment: Number(minF), max_units: maxUnits.trim() === '' ? null : Number(maxUnits), valid_until: inDays(30),
  } satisfies Schemas['quote']['QuoteDraft'];
}

function previewPrices(v: RoundVariant, line: Line, state: string, tiers: TierRow[]) {
  const d = toDraft([line], tiers, '1', '');
  const l = d.variants[0];
  return tiers.map((t) => {
    const out = compute({
      ruleset_version: rules.ruleset.ruleset_version, mode: 'BUYER_CONFIG', state,
      vehicle: { variant_id: v.code, fuel: v.fuel, engine_cc: v.engine_cc, ex_showroom_paise: l.ex_showroom_paise, ex_showroom_evidence: 'DEALER_CONFIRMED' },
      discount: { amount_paise: paise(t.discount) ?? 0, base: 'EX_SHOWROOM', evidence: 'DEALER_CONFIRMED' },
      registration: l.registration_paise === null ? null : { amount_paise: l.registration_paise, evidence: 'DEALER_CONFIRMED' },
      insurance: l.insurance ? { ...l.insurance, evidence: 'DEALER_CONFIRMED' } : null,
      insurance_addons: [], fees: [], accessories: [], extended_warranty: null, other_charges: [],
    }, rules);
    return out.status === 'OK' ? { ok: true as const, cash: out.cash_payable_paise as number } : { ok: false as const, errors: out.errors as string[] };
  });
}

export function QuoteForm({ quoteId }: { quoteId: string }) {
  const [quote, setQuote] = React.useState<Quote | null>(null);
  const [request, setRequest] = React.useState<Request | null>(null);
  const [lines, setLines] = React.useState<Line[]>([]);
  const [tiers, setTiers] = React.useState<TierRow[]>([{ min: '1', max: '4', discount: '10000' }, { min: '5', max: '', discount: '25000' }]);
  const [minF, setMinF] = React.useState('1');
  const [maxUnits, setMaxUnits] = React.useState('');
  const [confirmOpen, setConfirmOpen] = React.useState(false);

  const load = React.useCallback(async () => {
    const q = await call(api.GET('/dealer/quotes/{id}', { params: { path: { id: quoteId } } }));
    const inbox = await call(api.GET('/dealer/inbox'));
    const r = inbox.data.requests.find((x) => x.pool_id === q.data.pool_id && x.kind === 'SEALED') ?? inbox.data.requests.find((x) => x.pool_id === q.data.pool_id)!;
    setQuote({ ...q.data, version: etagVersion(q.response) ?? q.data.version });
    setRequest(r);
    setLines((prev) => prev.length ? prev : r.variants.map((v, i) => ({
      variant_id: v.id, include: i === 0, ex: v.catalogue_ex_showroom_paise ? String(v.catalogue_ex_showroom_paise / 100) : '', reg: '',
      insMode: 'estimate', od: '', tp: '', gst: '', binding: true, units: '5', colour: 'White', delivery: inDays(14),
    })));
  }, [quoteId]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- loads the dealer quote once on mount; setState happens after the fetch resolves
  React.useEffect(() => { load().catch(() => {}); }, [load]);

  const save = useCommand(async () => {
    const r = await call(api.PUT('/dealer/quotes/{id}/draft', { params: { path: { id: quoteId } }, body: toDraft(lines, tiers, minF, maxUnits), headers: { 'If-Match': `"${quote!.version}"` } }));
    setQuote(r.data);
    return r.data;
  });
  const submit = useCommand(async (key: string) => {
    const saved = quote!.state === 'DRAFT' ? await save.execute() : quote;
    if (!saved) throw new Error('save failed');
    const r = await call(api.POST('/dealer/quotes/{id}/submit', { params: { path: { id: quoteId } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${saved.version}"` } }));
    setQuote(r.data);
  });

  // Revision (QUO-T03): the form as it stands becomes a new immutable version. Withdrawal (QUO-T05): until the round deadline.
  const revise = useCommand(async (key: string) => {
    const r = await call(api.POST('/dealer/quotes/{id}/revisions', { params: { path: { id: quoteId } }, body: toDraft(lines, tiers, minF, maxUnits),
      headers: { 'Idempotency-Key': key, 'If-Match': `"${quote!.version}"` } }));
    setQuote(r.data);
    return r;
  });
  const withdraw = useCommand(async (key: string) => {
    const r = await call(api.POST('/dealer/quotes/{id}/withdraw', { params: { path: { id: quoteId } }, body: { reason: 'Withdrawn by the dealer' },
      headers: { 'Idempotency-Key': key, 'If-Match': `"${quote!.version}"` } }));
    setQuote(r.data);
    return r;
  });

  if (!quote || !request) return <Skeleton className="h-96" label="Loading the quote" />;
  const editable = quote.state === 'DRAFT';
  const set = (i: number, patch: Partial<Line>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold">Quote for pool …{quote.pool_id.slice(-6)}</h1>
        <Badge tone={editable ? 'warning' : 'success'}>{quote.state.toLowerCase()}</Badge>
        <Link href="/dealer" className="ml-auto text-sm font-semibold text-primary underline">Back to inbox</Link>
      </div>
      {request.state === 'OPEN' ? <Countdown deadline={request.deadline} label="Sealed round closes" /> : null}
      {!editable ? (
        <Card><CardTitle>Submitted</CardTitle><CardText>Your sealed quote is in. Buyers in the pool can see it; other dealers cannot. Prices below are the server&apos;s engine results.</CardText>
          {quote.snapshots ? Object.entries(quote.snapshots).map(([vid, ts]) => <p key={vid} className="tabular mt-2 text-sm">{request.variants.find((v) => v.id === vid)?.name}: {ts.map((t) => rupees(t.cash_payable_paise)).join(' → ')}</p>) : null}
        </Card>
      ) : null}
      <fieldset disabled={!editable} className="flex flex-col gap-4">
        {lines.map((l, i) => {
          const v = request.variants.find((x) => x.id === l.variant_id)!;
          const prev = l.include && l.ex ? previewPrices(v, l, request.state_code, tiers) : [];
          return (
            <Card key={l.variant_id} className="flex flex-col gap-3">
              <label className="flex items-center gap-2 font-bold"><input type="checkbox" className="size-5" checked={l.include} onChange={(e) => set(i, { include: e.target.checked })} />{v.name}</label>
              {l.include ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  <Input label="Ex-showroom (₹)" inputMode="numeric" value={l.ex} onChange={(e) => set(i, { ex: e.target.value })} hint={v.catalogue_ex_showroom_paise ? `Catalogue: ${rupees(v.catalogue_ex_showroom_paise)}` : undefined} />
                  <Input label="Registration / RTO (₹)" inputMode="numeric" value={l.reg} onChange={(e) => set(i, { reg: e.target.value })} hint="Leave empty to use the published-rules estimate." />
                  <fieldset className="flex flex-col gap-2 sm:col-span-2">
                    <legend className="text-sm font-semibold">Insurance</legend>
                    <label className="flex items-center gap-2 text-sm"><input type="radio" checked={l.insMode === 'estimate'} onChange={() => set(i, { insMode: 'estimate' })} />Use the baseline estimate</label>
                    <label className="flex items-center gap-2 text-sm"><input type="radio" checked={l.insMode === 'dealer'} onChange={() => set(i, { insMode: 'dealer' })} />Enter our figures</label>
                    {l.insMode === 'dealer' ? (
                      <div className="grid gap-3 sm:grid-cols-3">
                        <Input label="Own damage, year 1 (₹)" inputMode="numeric" value={l.od} onChange={(e) => set(i, { od: e.target.value })} />
                        <Input label="Third party, 3 years (₹)" inputMode="numeric" value={l.tp} onChange={(e) => set(i, { tp: e.target.value })} />
                        <Input label="GST on insurance (₹)" inputMode="numeric" value={l.gst} onChange={(e) => set(i, { gst: e.target.value })} />
                        <label className="flex items-start gap-2 text-sm sm:col-span-3">
                          <input type="checkbox" className="mt-0.5 size-5" checked={l.binding} onChange={(e) => set(i, { binding: e.target.checked })} />
                          <span><strong>Binding baseline cover</strong>: IDV 95 %, no add-ons, binding on this quote. {l.binding ? 'Your figure is used for your rank.' : 'Not ticked: buyers see your figure, but ranking uses the baseline estimate instead.'}</span>
                        </label>
                      </div>
                    ) : null}
                  </fieldset>
                  <Input label="Units in stock" inputMode="numeric" value={l.units} onChange={(e) => set(i, { units: e.target.value })} />
                  <Input label="Colour" value={l.colour} onChange={(e) => set(i, { colour: e.target.value })} />
                  <Input label="Delivery from" type="date" value={l.delivery} onChange={(e) => set(i, { delivery: e.target.value })} />
                  <div aria-live="polite" className="text-sm sm:col-span-2">
                    <p className="font-semibold">Preview (TypeScript engine; the server recalculates on submit)</p>
                    {prev.map((p, k) => <p key={k} className="tabular">Tier {k + 1}: {p.ok ? rupees(p.cash) : `not valid (${p.errors.join(', ')})`}</p>)}
                  </div>
                </div>
              ) : null}
            </Card>
          );
        })}
        <Card className="flex flex-col gap-3">
          <CardTitle>Tiers (discount on ex-showroom)</CardTitle>
          <CardText>Contiguous ranges; the last tier has no upper limit; the discount can only grow, so the price can only fall.</CardText>
          {tiers.map((t, i) => (
            <div key={i} className="grid grid-cols-3 gap-2">
              <Input label={`Tier ${i + 1} from`} inputMode="numeric" value={t.min} onChange={(e) => setTiers((ts) => ts.map((x, j) => (j === i ? { ...x, min: e.target.value } : x)))} />
              <Input label="to (empty = no limit)" inputMode="numeric" value={t.max} onChange={(e) => setTiers((ts) => ts.map((x, j) => (j === i ? { ...x, max: e.target.value } : x)))} />
              <Input label="Discount (₹)" inputMode="numeric" value={t.discount} onChange={(e) => setTiers((ts) => ts.map((x, j) => (j === i ? { ...x, discount: e.target.value } : x)))} />
            </div>
          ))}
          <div className="grid grid-cols-2 gap-2">
            <Input label="Minimum confirmed buyers" inputMode="numeric" value={minF} onChange={(e) => setMinF(e.target.value)} />
            <Input label="Maximum units (optional)" inputMode="numeric" value={maxUnits} onChange={(e) => setMaxUnits(e.target.value)} />
          </div>
        </Card>
      </fieldset>
      {editable ? (
        <div className="flex flex-wrap gap-3">
          <Button variant="secondary" loading={save.state.kind === 'loading'} onClick={() => save.execute()}>Save draft</Button>
          <Button onClick={() => setConfirmOpen(true)}>Submit sealed quote</Button>
        </div>
      ) : null}
      {quote.state === 'SUBMITTED' ? (
        <div className="flex flex-wrap gap-3">
          <Button variant="secondary" loading={revise.state.kind === 'loading'} onClick={() => revise.execute()}>Revise (new version)</Button>
          <Button variant="secondary" loading={withdraw.state.kind === 'loading'} onClick={() => withdraw.execute()}>Withdraw quote</Button>
        </div>
      ) : null}
      <CommandStatus state={revise.state} />
      <CommandStatus state={withdraw.state} />
      <CommandStatus state={save.state} successText="Draft saved." />
      <CommandStatus state={submit.state} successText="Quote submitted. It is sealed from other dealers." />
      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent title="Submit this sealed quote?" description="Buyers in the pool will see it; other dealers will not. You cannot edit it in place after submitting.">
          <div className="flex justify-end gap-2">
            <DialogClose asChild><Button variant="secondary">Cancel</Button></DialogClose>
            <Button loading={submit.state.kind === 'loading'} onClick={async () => { setConfirmOpen(false); await submit.execute(); }}>Submit</Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
