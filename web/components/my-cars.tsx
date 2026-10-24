'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import * as React from 'react';
import { CommandStatus } from '@/components/command-status';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardText, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { api, call, type Schemas } from '@/lib/api';
import { ist } from '@/lib/format';
import { useCommand } from '@/lib/use-command';

type SavedCar = Schemas['catalog']['SavedCar'];
type Model = Schemas['catalog']['Model'];
type Preferences = Schemas['pool']['Preferences'];
type Result = Schemas['pool']['Result'];

const CITIES = ['Mumbai', 'Pune', 'Delhi', 'Bengaluru'];

function months(): string[] {
  const now = new Date();
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + i, 1));
    return d.toISOString().slice(0, 7);
  });
}

/** Save / unsave one model (catalog E025/E026). */
export function SaveCarButton({ modelId, initiallySaved }: { modelId: string; initiallySaved: boolean }) {
  const [saved, setSaved] = React.useState(initiallySaved);
  const toggle = useCommand(async () => {
    const r = saved
      ? await call(api.DELETE('/me/saved-cars/{modelId}', { params: { path: { modelId } } }))
      : await call(api.PUT('/me/saved-cars/{modelId}', { params: { path: { modelId } } }));
    setSaved(r.data.cars.some((c) => c.model_id === modelId));
  });
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button variant="secondary" aria-pressed={saved} loading={toggle.state.kind === 'loading'} onClick={() => toggle.execute()}>
        {saved ? 'Saved to My cars' : 'Save this car'}
      </Button>
      {saved ? <Link className="text-sm underline" href="/saved">See pools for my cars</Link> : null}
      <CommandStatus state={toggle.state} />
    </div>
  );
}

function Checks({ legend, options, value, onChange }: { legend: string; options: { value: string; label: string }[]; value: string[]; onChange: (v: string[]) => void }) {
  if (options.length === 0) return null;
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="text-sm font-semibold">{legend} <span className="font-normal text-muted">(none ticked = any)</span></legend>
      <div className="flex flex-wrap gap-3">
        {options.map((o) => (
          <label key={o.value} className="flex min-h-11 items-center gap-2 text-sm">
            <input type="checkbox" checked={value.includes(o.value)} onChange={(e) => onChange(e.target.checked ? [...value, o.value] : value.filter((x) => x !== o.value))} />
            {o.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/** Saved cars, the buyer's matching preferences (pool E031) and pool suggestions (pool E030). */
export function MyCars() {
  const router = useRouter();
  const [cars, setCars] = React.useState<SavedCar[] | null>(null);
  const [models, setModels] = React.useState<Model[]>([]);
  const [prefs, setPrefs] = React.useState<Preferences | null>(null);
  const [colour, setColour] = React.useState('');
  const [results, setResults] = React.useState<Result[] | null>(null);
  const [problem, setProblem] = React.useState<string | null>(null);

  const loadSuggestions = React.useCallback(async (saved: SavedCar[]) => {
    if (saved.length === 0) return setResults([]);
    const r = await call(api.GET('/me/pool-suggestions', { params: { query: { model: saved.map((c) => c.slug) } } }));
    setResults(r.data.results);
  }, []);

  React.useEffect(() => {
    (async () => {
      try {
        const [c, p] = await Promise.all([call(api.GET('/me/saved-cars')), call(api.GET('/me/preferences'))]);
        setCars(c.data.cars);
        setPrefs(p.data);
        setColour(p.data.colours.join(', '));
        const ms = await Promise.all(c.data.cars.map((x) => call(api.GET('/catalog/models/{slug}', { params: { path: { slug: x.slug } } }))));
        setModels(ms.map((m) => m.data));
        await loadSuggestions(c.data.cars);
      } catch (e) {
        setProblem(e instanceof Error ? e.message : 'Could not load your cars.');
      }
    })();
  }, [loadSuggestions]);

  const save = useCommand(async () => {
    const body = { ...prefs!, colours: colour.split(',').map((s) => s.trim()).filter(Boolean) };
    const r = await call(api.PUT('/me/preferences', { body }));
    setPrefs(r.data);
    await loadSuggestions(cars ?? []);
  });

  const start = useCommand(async (key: string, slug: string) => {
    const r = await call(api.POST('/pools', { body: { model_slug: slug, city: prefs!.city!, purchase_month: prefs!.purchase_month! }, headers: { 'Idempotency-Key': key } }));
    router.push(`/pools/${r.data.pool.id}`);
  });

  if (problem) return <p role="alert" className="rounded-lg bg-danger-bg p-3 text-danger">{problem}</p>;
  if (!cars || !prefs) return <Skeleton className="h-96" label="Loading your cars" />;
  if (cars.length === 0) {
    return (
      <Card className="flex flex-col gap-2">
        <CardTitle>No saved cars yet</CardTitle>
        <CardText>Open a car and choose &ldquo;Save this car&rdquo;. We then show pools in your city and month that you can join.</CardText>
        <Button asChild className="self-start"><Link href="/">Browse cars</Link></Button>
      </Card>
    );
  }
  const variants = models.flatMap((m) => m.variants.map((v) => ({ ...v, model: m.name })));
  const fuels = [...new Set(variants.map((v) => v.fuel).filter((f): f is string => !!f))];
  const transmissions = [...new Set(variants.map((v) => v.transmission).filter((t): t is string => !!t))];
  const set = (patch: Partial<Preferences>) => setPrefs((p) => ({ ...p!, ...patch }));

  return (
    <div className="flex flex-col gap-5">
      <Card className="flex flex-col gap-4">
        <CardTitle>What you want</CardTitle>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm font-semibold">City
            <select className="min-h-11 rounded-lg border border-border bg-surface px-3 text-base font-normal" value={prefs.city ?? ''} onChange={(e) => set({ city: e.target.value || null })}>
              <option value="">Choose a city</option>
              {CITIES.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm font-semibold">Purchase month
            <select className="min-h-11 rounded-lg border border-border bg-surface px-3 text-base font-normal" value={prefs.purchase_month ?? ''} onChange={(e) => set({ purchase_month: e.target.value || null })}>
              <option value="">Choose a month</option>
              {months().map((m) => <option key={m}>{m}</option>)}
            </select>
          </label>
        </div>
        <Checks legend="Variants" options={variants.map((v) => ({ value: v.id, label: `${v.model} ${v.name}` }))} value={prefs.variant_ids} onChange={(v) => set({ variant_ids: v })} />
        <Checks legend="Fuel" options={fuels.map((f) => ({ value: f, label: f.toLowerCase() }))} value={prefs.fuels} onChange={(v) => set({ fuels: v })} />
        <Checks legend="Transmission" options={transmissions.map((t) => ({ value: t, label: t }))} value={prefs.transmissions} onChange={(v) => set({ transmissions: v })} />
        <label className="flex flex-col gap-1 text-sm font-semibold">Colours (comma separated)
          <span className="font-normal text-muted">Shared with dealers when they quote stock; colours do not filter pools.</span>
          <input className="min-h-11 rounded-lg border border-border bg-surface px-3 text-base font-normal" value={colour} onChange={(e) => setColour(e.target.value)} />
        </label>
        <Button className="self-start" loading={save.state.kind === 'loading'} onClick={() => save.execute()}>Save preferences</Button>
        <CommandStatus state={save.state} successText="Preferences saved. Suggestions updated." />
      </Card>

      <h2 className="text-xl font-bold">Pools for your cars</h2>
      {results === null ? <Skeleton className="h-40" label="Finding pools" /> : results.map((r) => {
        const car = cars.find((c) => c.slug === r.model_slug);
        return (
          <Card key={r.model_slug} className="flex flex-col gap-3">
            <CardTitle>{car ? `${car.brand} ${car.name}` : r.model_slug}</CardTitle>
            {r.pools.map((s) => (
              <div key={s.pool_id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border p-3">
                <div>
                  <Badge tone="info">Open pool</Badge>
                  <p className="mt-1 text-sm">{s.status}</p>
                  {s.join_deadline ? <p className="text-xs text-muted">Joining closes {ist(s.join_deadline)}</p> : null}
                </div>
                <Button asChild><Link href={`/pools/${s.pool_id}`}>View and join</Link></Button>
              </div>
            ))}
            {r.note ? <CardText>{r.note}</CardText> : null}
            {r.start ? (
              <Button variant="secondary" className="self-start" loading={start.state.kind === 'loading'} onClick={() => start.execute(r.model_slug)}>
                Start a pool in {r.start.city} for {r.start.purchase_month}
              </Button>
            ) : null}
          </Card>
        );
      })}
      <CommandStatus state={start.state} />
    </div>
  );
}
