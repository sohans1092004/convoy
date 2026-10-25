import { EvidenceBadge } from '@/components/ui/badge';
import type { Schemas } from '@/lib/api';
import { lineLabel, rupees } from '@/lib/format';
import { useTranslations } from 'next-intl';

export type EngineOutput = Schemas['catalog']['PriceOutput'];
export type EngineLine = EngineOutput['lines'][number];

function Flags({ l }: { l: EngineLine }) {
  const t = useTranslations('breakdown');
  return (
    <>
      {l.flags.includes('DEALER_REQUIRED') ? <span className="ml-1 text-xs text-muted">{t('dealerRequired')}</span> : null}
      {l.flags.includes('QUESTIONABLE') ? <span className="ml-1 text-xs font-semibold text-warning-fg">{t('askDealer')}</span> : null}
    </>
  );
}

/**
 * Itemised price-engine output: mandatory lines, then optional items (not in cash payable), each with its evidence label
 * (dealer-confirmed, catalogue estimate or unverified). Below the sm breakpoint it is a stacked list (PLAN section 5.1:
 * cards on mobile, never a squeezed table), so every value is visible at 360 px without scrolling sideways.
 */
export function Breakdown({ output, caption }: { output: EngineOutput; caption: string }) {
  const t = useTranslations('breakdown');
  const mandatory = output.lines.filter((l) => l.in_cash_payable);
  const optional = output.lines.filter((l) => !l.in_cash_payable);
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
      <section className="rounded-lg border border-border px-3 py-2 text-sm sm:hidden" aria-label={caption}>
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
      <div className="hidden overflow-x-auto rounded-lg border border-border sm:block" tabIndex={0} role="region" aria-label={caption}>
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
      </div>
    </>
  );
}
