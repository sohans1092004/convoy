import { notFound } from 'next/navigation';
import { cookies } from 'next/headers';
import { ModelEstimate, type Variant } from '@/components/model-estimate';
import { gatewayGet } from '@/lib/gateway';

interface Model { slug: string; name: string; brand: string; body_type: string; source_url: string; source_title: string; accessed_on: string; price_label: string; variants: Variant[] }

export default async function ModelPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const { status, data } = await gatewayGet<Model>(`/catalog/models/${slug}`);
  if (status === 404 || !data) notFound();
  const roles = ((await cookies()).get('cv_roles')?.value ?? '').split(',');
  return (
    <div className="flex flex-col gap-5">
      <div>
        <p className="text-sm font-semibold text-muted">{data.brand}</p>
        <h1 className="text-2xl font-extrabold sm:text-3xl">{data.name}</h1>
        <p className="mt-1 text-sm text-muted">
          Source: <a className="underline" href={data.source_url} rel="noopener noreferrer" target="_blank">{data.source_title}</a>, read on {data.accessed_on}. {data.price_label}
        </p>
      </div>
      <ModelEstimate slug={data.slug} variants={data.variants} canJoin={roles.includes('buyer')} signedIn={roles.some(Boolean)} />
    </div>
  );
}
