'use client';
import { Button } from '@/components/ui/button';

/** Route error boundary: honest message and a retry; nothing the user entered is lost by reloading a read page. */
export default function RouteError({ reset }: { error: Error; reset: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-start gap-3 rounded-lg bg-danger-bg p-4 text-danger">
      <h1 className="text-lg font-bold">This page could not be loaded right now</h1>
      <p>A part of Convoy it depends on is not answering. Nothing was changed.</p>
      <Button variant="secondary" onClick={() => reset()}>Try again</Button>
    </div>
  );
}
