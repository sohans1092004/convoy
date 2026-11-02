import { EvidenceBadge } from '@/components/ui/badge';
import type { Schemas } from '@/lib/api';
import { rupees } from '@/lib/format';
import { useTranslations } from 'next-intl';

export type EngineOutput = Schemas['catalog']['PriceOutput'];
export type EngineLine = EngineOutput['lines'][number];

/** A price line's name in the page language; a dealer's own item keeps the name the dealer gave it. */
export function useLineLabel() {
  const t = useTranslations('line');
  return (code: string): string => {
    if (t.has(`code.${code}`)) return t(`code.${code}`);
    const [kind, rest] = code.split(':');
    const nice = (rest ?? kind).replace(/_/g, ' ').toLowerCase();
    return t.has(`kind.${kind}`) ? `${t(`kind.${kind}`)}${rest ? `: ${nice}` : ''}` : nice;
  };
}

export function Flags({ l }: { l: { flags: string[] } }) {
  const t = useTranslations('breakdown');
  return (
    <>
      {l.flags.includes('DEALER_REQUIRED') ? <span className="ml-1 text-xs text-muted">{t('dealerRequired')}</span> : null}
      {l.flags.includes('QUESTIONABLE') ? <span className="ml-1 text-xs font-semibold text-warning-fg">{t('askDealer')}</span> : null}
    </>
  );
}

export const estimateLines = (lines: EngineLine[]): EngineLine[] => lines.filter((l) => !(l.code === 'DISCOUNT' && l.amount_paise === 0))
  .map((l) => (l.evidence === 'DEALER_CONFIRMED' ? { ...l, evidence: 'CATALOGUE_ESTIMATE' as const } : l));

/**
 * Itemised price-engine output: mandatory lines, then optional items (not in cash payable), each with its evidence label
 * (dealer-confirmed, catalogue estimate or unverified). Below the sm breakpoint it is a stacked list (PLAN section 5.1:
 * cards on mobile, never a squeezed table), so every value is visible at 360 px without scrolling sideways.
 */
export function Breakdown({ output, caption, stacked, estimate }: { output: EngineOutput; caption: string; /** Always the list: for a narrow column on a wide screen. */ stacked?: boolean; /** No dealer is involved yet: no empty discount line, and no line reads as dealer-confirmed. */ estimate?: boolean }) {
  const t = useTranslations('breakdown');
  const lineLabel = useLineLabel();
  const lines = estimate ? estimateLines(output.lines) : output.lines;
  const mandatory = lines.filter((l) => l.in_cash_payable);
  const optional = lines.filter((l) => !l.in_cash_payable);
  const row = (l: EngineLine) => (
    <tr key={l.code} className="border-b border-border last:border-0">
      <th scope="row" className="py-2 pr-3 text-left font-normal">{lineLabel(l.code)}<Flags l={l} /></th>
      <td className="tabular whitespace-nowrap py-2 pr-3 text-right">{rupees(l.amount_paise)}</td>
      <td className="py-2 text-right"><EvidenceBadge evidence={l.evidence} /></td>
    </tr>
  );
  const item = (l: EngineLine) => (
    <li key={l.code} className="flex flex-col gap-1 border-b border-border py-2 last:border-0">
      <span>{lineLabel(l.code)}<Flags l={l} /></span>
      <span className="flex flex-wrap items-center justify-between gap-2"><span className="tabular font-semibold">{rupees(l.amount_paise)}</span><EvidenceBadge evidence={l.evidence} /></span>
    </li>
  );
  return (
    <>
      <section className={`rounded-lg border border-border px-3 py-2 text-sm${stacked ? '' : ' sm:hidden'}`} aria-label={caption}>
        <h4 className="font-semibold">{caption}</h4>
        <ul>{mandatory.map(item)}</ul>
        <p className="flex justify-between border-t border-border py-2 font-bold"><span>{t('cashPayable')}</span><span className="tabular">{rupees(output.cash_payable_paise)}</span></p>
        <p className="flex justify-between gap-2 text-muted"><span>{t('netCost')}</span><span className="tabular">{rupees(output.estimated_net_cost_paise)}</span></p>
        {optional.length ? (
          <>
            <h5 className="pt-3 text-xs font-semibold uppercase tracking-wide text-muted">{t('optional')}</h5>
            <ul>{optional.map(item)}</ul>
          </>
        ) : null}
      </section>
      {stacked ? null : <div className="hidden overflow-x-auto rounded-lg border border-border sm:block" tabIndex={0} role="region" aria-label={caption}>
        <table className="w-full min-w-[22rem] text-sm">
          <caption className="px-3 pb-1 pt-3 text-left font-semibold">{caption}</caption>
          <tbody className="[&_th]:pl-3 [&_td:last-child]:pr-3">
            {mandatory.map(row)}
            <tr className="bg-surface-2 font-bold">
              <th scope="row" className="py-2 pr-3 text-left">{t('cashPayable')}</th>
              <td className="tabular py-2 pr-3 text-right">{rupees(output.cash_payable_paise)}</td>
              <td />
            </tr>
            <tr>
              <th scope="row" className="py-2 pr-3 text-left font-normal text-muted">{t('netCost')}</th>
              <td className="tabular py-2 pr-3 text-right text-muted">{rupees(output.estimated_net_cost_paise)}</td>
              <td />
            </tr>
            {optional.length ? (
              <>
                <tr><th colSpan={3} scope="rowgroup" className="pt-3 text-left text-xs font-semibold uppercase tracking-wide text-muted">{t('optional')}</th></tr>
                {optional.map(row)}
              </>
            ) : null}
          </tbody>
        </table>
      </div>}
    </>
  );
}
