import { notFound } from 'next/navigation';
import { cookies } from 'next/headers';
import { ModelEstimate } from '@/components/model-estimate';
import { SaveCarButton } from '@/components/my-cars';
import type { Schemas } from '@/lib/api';
import { gatewayGet } from '@/lib/gateway';

type Model = Schemas['catalog']['Model'];

export default async function ModelPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const { status, data } = await gatewayGet<Model>(`/catalog/models/${slug}`);
  if (status === 404 || !data) notFound();
  const roles = ((await cookies()).get('cv_roles')?.value ?? '').split(',');
  const saved = roles.includes('buyer') ? (await gatewayGet<Schemas['catalog']['SavedCars']>('/me/saved-cars')).data?.cars ?? [] : [];
  return (
    <div className="flex flex-col gap-5">
      <div>
        <p className="text-sm font-semibold text-muted">{data.brand}</p>
        <h1 className="text-2xl font-extrabold sm:text-3xl">{data.name}</h1>
        <p className="mt-1 text-sm text-muted">
          Source: <a className="underline" href={data.source_url} rel="noopener noreferrer" target="_blank">{data.source_title}</a>, read on {data.accessed_on}. {data.price_label}
        </p>
      </div>
      {roles.includes('buyer') ? <SaveCarButton modelId={data.id} initiallySaved={saved.some((c) => c.model_id === data.id)} /> : null}
      <ModelEstimate slug={data.slug} variants={data.variants} canJoin={roles.includes('buyer')} signedIn={roles.some(Boolean)} />
    </div>
  );
}
