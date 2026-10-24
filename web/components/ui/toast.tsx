'use client';
import * as ToastPrimitive from '@radix-ui/react-toast';
import * as React from 'react';
import { cn } from '@/lib/cn';

type Tone = 'success' | 'error' | 'info';
interface ToastItem { id: number; title: string; description?: string; tone: Tone }

const ToastContext = React.createContext<(t: Omit<ToastItem, 'id'>) => void>(() => {});

export function useToast() {
  return React.useContext(ToastContext);
}

const TONE: Record<Tone, string> = {
  success: 'border-success-fg/30 bg-success-bg text-success-fg',
  error: 'border-danger/30 bg-danger-bg text-danger',
  info: 'border-border bg-surface text-fg',
};

/** Polite live-region toasts (Radix); errors also stay inline next to the action that failed. */
export function Toaster({ children }: { children: React.ReactNode }) {
  const [items, setItems] = React.useState<ToastItem[]>([]);
  const push = React.useCallback((t: Omit<ToastItem, 'id'>) => setItems((xs) => [...xs, { ...t, id: Date.now() + Math.random() }]), []);
  return (
    <ToastContext.Provider value={push}>
      <ToastPrimitive.Provider swipeDirection="right" duration={6000}>
        {children}
        {items.map((t) => (
          <ToastPrimitive.Root key={t.id} className={cn('rounded-lg border p-3 shadow-lg', TONE[t.tone])}
            onOpenChange={(open) => { if (!open) setItems((xs) => xs.filter((x) => x.id !== t.id)); }}>
            <ToastPrimitive.Title className="text-sm font-bold">{t.title}</ToastPrimitive.Title>
            {t.description ? <ToastPrimitive.Description className="mt-0.5 text-sm">{t.description}</ToastPrimitive.Description> : null}
          </ToastPrimitive.Root>
        ))}
        <ToastPrimitive.Viewport className="fixed bottom-4 right-4 z-50 flex w-[min(24rem,calc(100%-2rem))] flex-col gap-2" />
      </ToastPrimitive.Provider>
    </ToastContext.Provider>
  );
}
