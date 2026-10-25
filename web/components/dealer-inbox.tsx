'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Countdown } from '@/components/ui/countdown';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { useCommand } from '@/lib/use-command';
import { useSnapshot } from '@/lib/use-snapshot';

export type RoundVariant = Schemas['quote']['RoundVariant'];
export type Request = Schemas['quote']['Request'];

function RequestCard({ r }: { r: Request }) {
  const router = useRouter();
  const start = useCommand(async (key: string) => call(api.POST('/dealer/pools/{poolId}/quotes', { params: { path: { poolId: r.pool_id } }, headers: { 'Idempotency-Key': key } })));
  // eslint-disable-next-line react-hooks/purity -- a display hint only; the server enforces the deadline (DB clock)
  const open = r.state === 'OPEN' && Date.parse(r.deadline) > Date.now();
  return (
    <Card className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <CardTitle as="h2">Pool …{r.pool_id.slice(-6)} · {r.variants.length} {r.variants.length === 1 ? 'variant' : 'variants'}</CardTitle>
        <Badge tone={r.kind === 'SEALED' ? 'info' : 'warning'}>{r.kind === 'SEALED' ? 'Sealed round' : 'Best-and-final round'}</Badge>
        {r.quote_state ? <Badge tone="success">Your quote: {r.quote_state.toLowerCase()}</Badge> : null}
      </div>
      <CardText>{r.committed_count} committed {r.committed_count === 1 ? 'buyer' : 'buyers'} · registration state {r.state_code} · ruleset {r.ruleset_version}</CardText>
      {open ? <Countdown deadline={r.deadline} label="Round closes" /> : <p className="text-sm text-muted">Round closed.</p>}
      {r.quote_id ? (
        <Button asChild variant="secondary"><Link href={`/dealer/quotes/${r.quote_id}`}>{r.quote_state === 'DRAFT' ? 'Continue the draft' : 'View your quote'}</Link></Button>
      ) : open && r.kind === 'SEALED' ? (
        <>
          <Button loading={start.state.kind === 'loading'} onClick={async () => { const q = await start.execute(); if (q) router.push(`/dealer/quotes/${q.data.id}`); }}>Start a quote</Button>
          <CommandStatus state={start.state} />
        </>
      ) : null}
    </Card>
  );
}

export function DealerInbox() {
  const inbox = useSnapshot(() => call(api.GET('/dealer/inbox')), []);
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">Requests for quotes</h1>
      <p className="text-sm text-muted">Pools of buyers in your city that want cars your dealership sells. Quotes are sealed: other dealers never see your prices.</p>
      {inbox.loading && !inbox.data ? <Skeleton className="h-40" label="Loading requests" /> : null}
      {inbox.problem && !inbox.data ? <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{inbox.problem.detail}</p> : null}
      {inbox.data?.requests.length === 0 ? <Card><p>No requests yet. They appear when a pool reaches its minimum.</p></Card> : null}
      <div className="grid gap-3 md:grid-cols-2">{inbox.data?.requests.map((r) => <RequestCard key={r.round_id} r={r} />)}</div>
    </div>
  );
}
