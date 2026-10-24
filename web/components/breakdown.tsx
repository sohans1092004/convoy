import { EvidenceBadge } from '@/components/ui/badge';
import { lineLabel, rupees, type Evidence } from '@/lib/format';

export interface EngineLine { code: string; amount_paise: number; evidence: Evidence; in_cash_payable: boolean; flags: string[] }
export interface EngineOutput { status: string; lines: EngineLine[]; cash_payable_paise: number; tcs_paise: number; estimated_net_cost_paise: number; warnings: string[] }

/** Itemised price-engine output: mandatory lines, then optional items (not in cash payable), each with evidence. */
export function Breakdown({ output, caption }: { output: EngineOutput; caption: string }) {
  const mandatory = output.lines.filter((l) => l.in_cash_payable);
  const optional = output.lines.filter((l) => !l.in_cash_payable);
  const row = (l: EngineLine) => (
    <tr key={l.code} className="border-b border-border last:border-0">
      <th scope="row" className="py-2 pr-3 text-left font-normal">
        {lineLabel(l.code)}
        {l.flags.includes('DEALER_REQUIRED') ? <span className="ml-1 text-xs text-muted">(dealer-required)</span> : null}
        {l.flags.includes('QUESTIONABLE') ? <span className="ml-1 text-xs font-semibold text-warning-fg">(ask the dealer)</span> : null}
      </th>
      <td className="tabular whitespace-nowrap py-2 pr-3 text-right">{rupees(l.amount_paise)}</td>
      <td className="py-2 text-right"><EvidenceBadge evidence={l.evidence} /></td>
    </tr>
  );
  return (
    <div className="overflow-x-auto rounded-lg border border-border" tabIndex={0} role="region" aria-label={caption}>
      <table className="w-full min-w-[22rem] text-sm">
        <caption className="px-3 pb-1 pt-3 text-left font-semibold">{caption}</caption>
        <tbody className="[&_th]:pl-3 [&_td:last-child]:pr-3">
          {mandatory.map(row)}
          <tr className="bg-surface-2 font-bold">
            <th scope="row" className="py-2 pr-3 text-left">Cash payable</th>
            <td className="tabular py-2 pr-3 text-right">{rupees(output.cash_payable_paise)}</td>
            <td />
          </tr>
          <tr>
            <th scope="row" className="py-2 pr-3 text-left font-normal text-muted">Estimated net cost (after creditable TCS)</th>
            <td className="tabular py-2 pr-3 text-right text-muted">{rupees(output.estimated_net_cost_paise)}</td>
            <td />
          </tr>
          {optional.length ? (
            <>
              <tr><th colSpan={3} scope="rowgroup" className="pt-3 text-left text-xs font-semibold uppercase tracking-wide text-muted">Optional (not included above)</th></tr>
              {optional.map(row)}
            </>
          ) : null}
        </tbody>
      </table>
    </div>
  );
}
