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
import { type Accessory, type Issue, diff, lineIssues, ordinal, paise, priceOnlyFalls, tierIssues, type TierRow, validityIssues, whatIf } from '@/lib/quote-builder';
import { useCommand } from '@/lib/use-command';

// Dealer quote builder (P3.D). Amounts are typed in rupees and stored in paise. The live summary uses the TypeScript
// price engine as a preview only; stored prices are the server's (recomputed on every submit and revision).
interface Line {
  variant_id: string; include: boolean; ex: string; reg: string; insMode: 'estimate' | 'dealer'; od: string; tp: string; gst: string; binding: boolean;
  units: string; colour: string; delivery: string; accessories: Accessory[];
}
type Quote = Schemas['quote']['DealerQuote'];
type Draft = Schemas['quote']['QuoteDraft'];
type Rank = Schemas['quote']['Rank'];

const RANKING_RULE = 'Ranked per variant by cash payable at your ceiling tier (the tier holding your minimum), mandatory items and baseline insurance only. Ties: earliest delivery date, then earliest version. You see only your own rank.';
const inDays = (d: number) => new Date(Date.now() + d * 864e5).toISOString().slice(0, 10);
const rupeesText = (p: number | null | undefined) => (p === null || p === undefined ? '' : String(p / 100));
const slug = (s: string) => s.trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').slice(0, 40) || 'ITEM';

function toDraft(lines: Line[], tiers: TierRow[], minF: string, maxUnits: string, validUntil: string) {
  return {
    variants: lines.filter((l) => l.include).map((l) => ({
      variant_id: l.variant_id, ex_showroom_paise: paise(l.ex), registration_paise: paise(l.reg),
      insurance: l.insMode === 'dealer' ? { od_paise: paise(l.od), tp_paise: paise(l.tp), gst_paise: paise(l.gst), binding: l.binding, declared_cover: l.binding ? { idv_bp: 9500, addons: [] } : null } : null,
      insurance_addons: [], fees: [], extended_warranty: null, other_charges: [],
      accessories: l.accessories.map((a) => ({ code: slug(a.label), label: a.label.trim(), amount_paise: paise(a.amount) ?? 0, basis: 'FIXED', required: a.required, selected: false, evidence: 'DEALER_CONFIRMED' })),
      stock: { units: Number(l.units || 0), colours: l.colour ? [l.colour] : [], delivery_date: l.delivery || null },
    })),
    tiers: tiers.map((t) => ({ min: Number(t.min), max: t.max.trim() === '' ? null : Number(t.max), discount_paise: paise(t.discount) ?? 0 })),
    min_fulfilment: Number(minF), max_units: maxUnits.trim() === '' ? null : Number(maxUnits), valid_until: validUntil,
  } satisfies Draft;
}

/** Engine preview per tier (BUYER_CONFIG: mandatory items + the buyer's selections; optional accessories are not selected). */
function previewPrices(v: RoundVariant, line: Line, state: string, tiers: TierRow[]) {
  const l = toDraft([line], tiers, '1', '', inDays(30)).variants[0];
  return tiers.map((t) => {
    const out = compute({
      ruleset_version: rules.ruleset.ruleset_version, mode: 'BUYER_CONFIG', state,
      vehicle: { variant_id: v.code, fuel: v.fuel, engine_cc: v.engine_cc, ex_showroom_paise: l.ex_showroom_paise, ex_showroom_evidence: 'DEALER_CONFIRMED' },
      discount: { amount_paise: paise(t.discount) ?? 0, base: 'EX_SHOWROOM', evidence: 'DEALER_CONFIRMED' },
      registration: l.registration_paise === null ? null : { amount_paise: l.registration_paise, evidence: 'DEALER_CONFIRMED' },
      insurance: l.insurance ? { ...l.insurance, evidence: 'DEALER_CONFIRMED' } : null,
      insurance_addons: [], fees: [], accessories: l.accessories, extended_warranty: null, other_charges: [],
    }, rules);
    return out.status === 'OK' ? { ok: true as const, cash: out.cash_payable_paise as number } : { ok: false as const, errors: out.errors as string[] };
  });
}

/** The server's stored draft back into form rows (so a submitted quote is shown and revised from what was stored). */
function fromDraft(d: Draft, variants: RoundVariant[]): { lines: Line[]; tiers: TierRow[]; minF: string; maxUnits: string; validUntil: string } | null {
  if (!d.variants || d.variants.length === 0) return null;
  const lines = variants.map((v) => {
    const s = d.variants!.find((x) => x.variant_id === v.id);
    return {
      variant_id: v.id, include: !!s, ex: rupeesText(s?.ex_showroom_paise ?? v.catalogue_ex_showroom_paise), reg: rupeesText(s?.registration_paise),
      insMode: s?.insurance ? 'dealer' as const : 'estimate' as const, od: rupeesText(s?.insurance?.od_paise), tp: rupeesText(s?.insurance?.tp_paise), gst: rupeesText(s?.insurance?.gst_paise),
      binding: s?.insurance?.binding ?? true, units: String(s?.stock?.units ?? 0), colour: s?.stock?.colours?.[0] ?? '', delivery: s?.stock?.delivery_date ?? '',
      accessories: (s?.accessories ?? []).map((a) => ({ label: a.label ?? a.code ?? '', amount: rupeesText(a.amount_paise), required: !!a.required })),
    };
  });
  return {
    lines, tiers: (d.tiers ?? []).map((t) => ({ min: String(t.min ?? ''), max: t.max === null || t.max === undefined ? '' : String(t.max), discount: rupeesText(t.discount_paise) })),
    minF: String(d.min_fulfilment ?? 1), maxUnits: d.max_units === null || d.max_units === undefined ? '' : String(d.max_units), validUntil: d.valid_until ?? inDays(30),
  };
}

function AccessoryTable({ title, hint, rows, onChange, onRemove, onAdd, disabled }: {
  title: string; hint: string; rows: { a: Accessory; k: number }[]; onChange: (k: number, p: Partial<Accessory>) => void; onRemove: (k: number) => void; onAdd: () => void; disabled: boolean;
}) {
  return (
    <div className="flex flex-col gap-2">
      <table className="w-full text-sm">
        <caption className="text-left font-semibold">{title} <span className="font-normal text-muted">{hint}</span></caption>
        <thead><tr><th scope="col" className="text-left">Item</th><th scope="col" className="text-left">Amount (₹)</th><th scope="col"><span className="sr-only">Remove</span></th></tr></thead>
        <tbody>
          {rows.length === 0 ? <tr><td colSpan={3} className="text-muted">None.</td></tr> : rows.map(({ a, k }) => (
            <tr key={k}>
              <td><input aria-label={`${title} item ${k + 1} name`} className="w-full rounded border border-border bg-surface px-2 py-1" value={a.label} onChange={(e) => onChange(k, { label: e.target.value })} /></td>
              <td><input aria-label={`${title} item ${k + 1} amount`} inputMode="numeric" className="tabular w-full rounded border border-border bg-surface px-2 py-1" value={a.amount} onChange={(e) => onChange(k, { amount: e.target.value })} /></td>
              <td><Button variant="link" type="button" onClick={() => onRemove(k)}>Remove</Button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {!disabled ? <Button variant="secondary" type="button" className="self-start" onClick={onAdd}>Add {title.toLowerCase()} item</Button> : null}
    </div>
  );
}

export function QuoteForm({ quoteId }: { quoteId: string }) {
  const [quote, setQuote] = React.useState<Quote | null>(null);
  const [request, setRequest] = React.useState<Request | null>(null);
  const [lines, setLines] = React.useState<Line[]>([]);
  const [tiers, setTiers] = React.useState<TierRow[]>([{ min: '1', max: '4', discount: '10000' }, { min: '5', max: '', discount: '25000' }]);
  const [minF, setMinF] = React.useState('1');
  const [maxUnits, setMaxUnits] = React.useState('');
  const [validUntil, setValidUntil] = React.useState(inDays(30));
  const [confirm, setConfirm] = React.useState<null | 'submit' | 'revise'>(null);
  const [revising, setRevising] = React.useState(false);
  const [changes, setChanges] = React.useState<ReturnType<typeof diff> | null>(null);
  const [ranks, setRanks] = React.useState<Rank[]>([]);

  const load = React.useCallback(async () => {
    const q = await call(api.GET('/dealer/quotes/{id}', { params: { path: { id: quoteId } } }));
    const inbox = await call(api.GET('/dealer/inbox'));
    const r = inbox.data.requests.find((x) => x.pool_id === q.data.pool_id && x.state === 'OPEN') ?? inbox.data.requests.find((x) => x.pool_id === q.data.pool_id)!;
    setQuote({ ...q.data, version: etagVersion(q.response) ?? q.data.version });
    setRequest(r);
    const stored = fromDraft(q.data.draft, r.variants);
    if (stored) {
      setLines(stored.lines); setTiers(stored.tiers); setMinF(stored.minF); setMaxUnits(stored.maxUnits); setValidUntil(stored.validUntil);
    } else {
      setLines((prev) => prev.length ? prev : r.variants.map((v, i) => ({
        variant_id: v.id, include: i === 0, ex: rupeesText(v.catalogue_ex_showroom_paise), reg: '',
        insMode: 'estimate', od: '', tp: '', gst: '', binding: true, units: '5', colour: 'White', delivery: inDays(14), accessories: [],
      })));
    }
    if (q.data.state !== 'DRAFT') {
      const rk = await api.GET('/dealer/quotes/{id}/rank', { params: { path: { id: quoteId } } });
      setRanks(rk.data?.ranks ?? []);
    }
  }, [quoteId]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- loads the dealer quote once on mount; setState happens after the fetch resolves
  React.useEffect(() => { load().catch(() => {}); }, [load]);

  const draftNow = () => toDraft(lines, tiers, minF, maxUnits, validUntil);
  const save = useCommand(async () => {
    const r = await call(api.PUT('/dealer/quotes/{id}/draft', { params: { path: { id: quoteId } }, body: draftNow(), headers: { 'If-Match': `"${quote!.version}"` } }));
    setQuote(r.data);
    return r.data;
  });
  const submit = useCommand(async (key: string) => {
    const saved = quote!.state === 'DRAFT' ? await save.execute() : quote;
    if (!saved) throw new Error('save failed');
    const r = await call(api.POST('/dealer/quotes/{id}/submit', { params: { path: { id: quoteId } }, headers: { 'Idempotency-Key': key, 'If-Match': `"${saved.version}"` } }));
    setQuote(r.data);
  });
  // Revision (QUO-T03): the form as it stands becomes a new immutable version; the change list is shown afterwards.
  const revise = useCommand(async (key: string) => {
    const before = quote!.draft;
    const r = await call(api.POST('/dealer/quotes/{id}/revisions', { params: { path: { id: quoteId } }, body: draftNow(),
      headers: { 'Idempotency-Key': key, 'If-Match': `"${quote!.version}"` } }));
    setChanges(diff(before, r.data.draft));
    setQuote(r.data);
    setRevising(false);
    return r;
  });
  const withdraw = useCommand(async (key: string) => {
    const r = await call(api.POST('/dealer/quotes/{id}/withdraw', { params: { path: { id: quoteId } }, body: { reason: 'Withdrawn by the dealer' },
      headers: { 'Idempotency-Key': key, 'If-Match': `"${quote!.version}"` } }));
    setQuote(r.data);
    return r;
  });

  if (!quote || !request) return <Skeleton className="h-96" label="Loading the quote" />;
  const open = request.state === 'OPEN';
  const live = ['SUBMITTED', 'REVISED'].includes(quote.state);
  const editable = quote.state === 'DRAFT' || (revising && live && open);
  const set = (i: number, patch: Partial<Line>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const setAcc = (i: number, k: number, p: Partial<Accessory>) => set(i, { accessories: lines[i].accessories.map((a, j) => (j === k ? { ...a, ...p } : a)) });
  const issues: Issue[] = [
    ...lines.flatMap((l, i) => (l.include ? lineIssues({ ...l, name: request.variants.find((v) => v.id === l.variant_id)?.name ?? 'Variant' }, i) : [])),
    ...(lines.some((l) => l.include) ? [] : [{ field: 'lines', message: 'Price at least one variant.' }]),
    ...tierIssues(tiers, minF, maxUnits),
    ...validityIssues(validUntil, new Date().toISOString().slice(0, 10)),
  ];
  const ordinalRank = (r: Rank) => `${ordinal(r.rank)} of ${r.of_n}`;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold">Quote for pool …{quote.pool_id.slice(-6)}</h1>
        <Badge tone={quote.state === 'DRAFT' ? 'warning' : 'success'}>{quote.state.toLowerCase()}</Badge>
        {ranks.length ? ranks.map((r) => (
          <Badge key={r.variant_id} tone="info">{ordinalRank(r)} for {request.variants.find((v) => v.id === r.variant_id)?.name ?? 'variant'}</Badge>
        )) : null}
        <Link href="/dealer" className="ml-auto text-sm font-semibold text-primary underline">Back to inbox</Link>
      </div>
      <div className="flex flex-wrap gap-4">
        {open ? <Countdown deadline={request.deadline} label={request.kind === 'FINAL' ? 'Final round closes' : 'Sealed round closes'} /> : <p className="text-sm">This round has closed.</p>}
        {validUntil ? <Countdown deadline={`${validUntil}T23:59:59+05:30`} label="Quote valid until" /> : null}
      </div>
      {ranks.length ? <p className="text-sm text-muted"><strong>Ranking rule:</strong> {RANKING_RULE}</p> : null}

      {!editable && quote.state !== 'DRAFT' ? (
        <Card>
          <CardTitle>Submitted: version {quote.current_version}</CardTitle>
          <CardText>This version is fixed: it cannot be edited, only replaced by a new version while the round is open. It stays sealed: no buyer or other dealer sees it before the round closes. Prices below are the server&apos;s engine results.</CardText>
          {quote.snapshots ? Object.entries(quote.snapshots).map(([vid, ts]) => <p key={vid} className="tabular mt-2 text-sm">{request.variants.find((v) => v.id === vid)?.name}: {ts.map((t) => rupees(t.cash_payable_paise)).join(' → ')}</p>) : null}
        </Card>
      ) : null}
      {changes ? (
        <Card aria-live="polite">
          <CardTitle>What changed in version {quote.current_version}</CardTitle>
          {changes.length === 0 ? <CardText>Nothing changed.</CardText> : (
            <ul className="tabular mt-2 text-sm">{changes.map((c) => <li key={c.path}><code>{c.path}</code>: {JSON.stringify(c.from)} → {JSON.stringify(c.to)}</li>)}</ul>
          )}
        </Card>
      ) : null}

      <fieldset disabled={!editable} className="flex flex-col gap-4">
        {lines.map((l, i) => {
          const v = request.variants.find((x) => x.id === l.variant_id)!;
          const prev = l.include && l.ex ? previewPrices(v, l, request.state_code, tiers) : [];
          const cash = prev.map((p) => (p.ok ? p.cash : null));
          const rows = whatIf(tiers, cash, Number(minF) || 1, maxUnits.trim() === '' ? null : Number(maxUnits));
          return (
            <Card key={l.variant_id} className="flex flex-col gap-3">
              <label className="flex items-center gap-2 font-bold"><input type="checkbox" className="size-5" checked={l.include} onChange={(e) => set(i, { include: e.target.checked })} />{v.name}</label>
              {l.include ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  <Input label="Ex-showroom (₹)" inputMode="numeric" value={l.ex} onChange={(e) => set(i, { ex: e.target.value })} hint={v.catalogue_ex_showroom_paise ? `Catalogue: ${rupees(v.catalogue_ex_showroom_paise)}` : undefined} />
                  <Input label="Registration / RTO (₹)" inputMode="numeric" value={l.reg} onChange={(e) => set(i, { reg: e.target.value })} hint="Leave empty to use the published-rules estimate." />
                  <fieldset className="flex flex-col gap-2 sm:col-span-2">
                    <legend className="text-sm font-semibold">Insurance (baseline: comprehensive year-1 OD + 3-year TP)</legend>
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
                  <div className="flex flex-col gap-3 sm:col-span-2">
                    <AccessoryTable title="Mandatory" hint="(always in the price and in your rank)" disabled={!editable}
                      rows={l.accessories.map((a, k) => ({ a, k })).filter((x) => x.a.required)} onChange={(k, p) => setAcc(i, k, p)}
                      onRemove={(k) => set(i, { accessories: l.accessories.filter((_, j) => j !== k) })} onAdd={() => set(i, { accessories: [...l.accessories, { label: '', amount: '', required: true }] })} />
                    <AccessoryTable title="Optional" hint="(the buyer chooses; never in your rank)" disabled={!editable}
                      rows={l.accessories.map((a, k) => ({ a, k })).filter((x) => !x.a.required)} onChange={(k, p) => setAcc(i, k, p)}
                      onRemove={(k) => set(i, { accessories: l.accessories.filter((_, j) => j !== k) })} onAdd={() => set(i, { accessories: [...l.accessories, { label: '', amount: '', required: false }] })} />
                  </div>
                  <Input label="Units in stock" inputMode="numeric" value={l.units} onChange={(e) => set(i, { units: e.target.value })} />
                  <Input label="Colour" value={l.colour} onChange={(e) => set(i, { colour: e.target.value })} />
                  <Input label="Delivery from" type="date" value={l.delivery} onChange={(e) => set(i, { delivery: e.target.value })} />
                  <div aria-live="polite" className="text-sm sm:col-span-2">
                    <p className="font-semibold">Preview (TypeScript engine; the server recalculates on submit)</p>
                    {prev.map((p, k) => <p key={k} className="tabular">Tier {k + 1}: {p.ok ? rupees(p.cash) : `not valid (${p.errors.join(', ')})`}</p>)}
                    {prev.length ? (
                      <table className="tabular mt-2 w-full">
                        <caption className="text-left font-semibold">What if this many buyers confirm</caption>
                        <thead><tr><th scope="col" className="text-left">Buyers</th><th scope="col" className="text-left">Each pays</th><th scope="col" className="text-left">Why</th></tr></thead>
                        <tbody>{rows.map((r) => <tr key={r.n}><th scope="row" className="text-left">{r.n}</th><td>{r.cash === null ? '—' : rupees(r.cash)}</td><td>{r.note}</td></tr>)}</tbody>
                      </table>
                    ) : null}
                    {prev.length && !priceOnlyFalls(cash) ? <p className="font-semibold text-danger">A bigger tier costs more than a smaller one: the price must only fall as more buyers confirm.</p> : null}
                  </div>
                </div>
              ) : null}
            </Card>
          );
        })}
        <Card className="flex flex-col gap-3">
          <CardTitle>Tiers (discount on ex-showroom)</CardTitle>
          <CardText>Contiguous ranges; the last tier has no upper limit; the discount can only grow, so the price can only fall.</CardText>
          <table className="w-full text-sm">
            <caption className="sr-only">Tier ladder</caption>
            <thead><tr><th scope="col" className="text-left">Tier</th><th scope="col" className="text-left">From (buyers)</th><th scope="col" className="text-left">To (empty = no limit)</th><th scope="col" className="text-left">Discount (₹)</th></tr></thead>
            <tbody>
              {tiers.map((t, i) => (
                <tr key={i}>
                  <th scope="row" className="text-left">{i + 1}</th>
                  <td><input aria-label={`Tier ${i + 1} from`} inputMode="numeric" className="tabular w-full rounded border border-border bg-surface px-2 py-1" value={t.min} onChange={(e) => setTiers((ts) => ts.map((x, j) => (j === i ? { ...x, min: e.target.value } : x)))} /></td>
                  <td><input aria-label={`Tier ${i + 1} to`} inputMode="numeric" className="tabular w-full rounded border border-border bg-surface px-2 py-1" value={t.max} onChange={(e) => setTiers((ts) => ts.map((x, j) => (j === i ? { ...x, max: e.target.value } : x)))} /></td>
                  <td><input aria-label={`Tier ${i + 1} discount`} inputMode="numeric" className="tabular w-full rounded border border-border bg-surface px-2 py-1" value={t.discount} onChange={(e) => setTiers((ts) => ts.map((x, j) => (j === i ? { ...x, discount: e.target.value } : x)))} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          {editable ? (
            <div className="flex gap-2">
              <Button variant="secondary" type="button" onClick={() => setTiers((ts) => [...ts.slice(0, -1), { ...ts[ts.length - 1], max: ts[ts.length - 1].min }, { min: String(Number(ts[ts.length - 1].min) + 1), max: '', discount: ts[ts.length - 1].discount }])}>Add tier</Button>
              {tiers.length > 1 ? <Button variant="secondary" type="button" onClick={() => setTiers((ts) => ts.slice(0, -1).map((x, j, a) => (j === a.length - 1 ? { ...x, max: '' } : x)))}>Remove last tier</Button> : null}
            </div>
          ) : null}
          <div className="grid gap-2 sm:grid-cols-3">
            <Input label="Minimum confirmed buyers" inputMode="numeric" value={minF} onChange={(e) => setMinF(e.target.value)} />
            <Input label="Maximum units (optional)" inputMode="numeric" value={maxUnits} onChange={(e) => setMaxUnits(e.target.value)} />
            <Input label="Quote valid until" type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
          </div>
        </Card>
      </fieldset>

      {editable && issues.length ? (
        <div role="status" className="rounded-lg bg-warning-bg p-3 text-sm text-warning-fg">
          <p className="font-semibold">Fix before submitting ({issues.length})</p>
          <ul className="list-disc pl-5">{issues.map((x) => <li key={x.field + x.message}>{x.message}</li>)}</ul>
        </div>
      ) : null}
      {quote.state === 'DRAFT' ? (
        <div className="flex flex-wrap gap-3">
          <Button variant="secondary" loading={save.state.kind === 'loading'} onClick={() => save.execute()}>Save draft</Button>
          <Button onClick={() => setConfirm('submit')} disabled={issues.length > 0}>Submit sealed quote</Button>
        </div>
      ) : null}
      {live && open ? (
        <div className="flex flex-wrap gap-3">
          {revising ? (
            <>
              <Button onClick={() => setConfirm('revise')} disabled={issues.length > 0}>Submit revision</Button>
              <Button variant="secondary" onClick={() => { setRevising(false); load().catch(() => {}); }}>Discard changes</Button>
            </>
          ) : (
            <Button variant="secondary" onClick={() => { setRevising(true); setChanges(null); }}>Revise (new version)</Button>
          )}
          <Button variant="secondary" loading={withdraw.state.kind === 'loading'} onClick={() => withdraw.execute()}>Withdraw quote</Button>
        </div>
      ) : null}
      <CommandStatus state={revise.state} successText="New version submitted." />
      <CommandStatus state={withdraw.state} />
      <CommandStatus state={save.state} successText="Draft saved." />
      <CommandStatus state={submit.state} successText="Quote submitted. It is sealed from other dealers." />
      <Dialog open={confirm !== null} onOpenChange={(o) => { if (!o) setConfirm(null); }}>
        <DialogContent title={confirm === 'revise' ? 'Submit this revision?' : 'Submit this sealed quote?'}
          description="No buyer or other dealer sees it before the round closes. Once submitted, a version cannot be edited: changes become a new version while the round is open.">
          <div className="flex justify-end gap-2">
            <DialogClose asChild><Button variant="secondary">Cancel</Button></DialogClose>
            <Button loading={submit.state.kind === 'loading' || revise.state.kind === 'loading'}
              onClick={async () => { const what = confirm; setConfirm(null); if (what === 'revise') await revise.execute(); else await submit.execute(); }}>Submit</Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
