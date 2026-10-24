'use client';
import * as TabsPrimitive from '@radix-ui/react-tabs';
import * as React from 'react';
import { cn } from '@/lib/cn';

export const Tabs = TabsPrimitive.Root;

export function TabsList({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.List>) {
  return <TabsPrimitive.List className={cn('inline-flex gap-1 rounded-lg border border-border bg-surface-2 p-1', className)} {...props} />;
}

export function TabsTrigger({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn('min-h-10 rounded-md px-3 text-sm font-semibold text-muted data-[state=active]:bg-surface data-[state=active]:text-fg', className)}
      {...props}
    />
  );
}

export const TabsContent = TabsPrimitive.Content;
