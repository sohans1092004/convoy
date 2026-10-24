import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import * as React from 'react';
import { Badge, EvidenceBadge } from './badge';
import { Button } from './button';
import { Card, CardText, CardTitle } from './card';
import { Countdown } from './countdown';
import { Dialog, DialogClose, DialogContent, DialogTrigger } from './dialog';
import { Input } from './input';
import { Price } from './price';
import { Skeleton } from './skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs';
import { useToast } from './toast';

const meta: Meta = { title: 'Design system' };
export default meta;
type Story = StoryObj;

export const Buttons: Story = {
  render: () => (
    <div className="flex flex-wrap gap-3">
      <Button>Primary</Button>
      <Button variant="secondary">Secondary</Button>
      <Button variant="danger">Danger</Button>
      <Button variant="link">Link</Button>
      <Button loading>Loading</Button>
      <Button disabled>Disabled</Button>
    </div>
  ),
};

export const Inputs: Story = {
  render: () => (
    <div className="grid max-w-md gap-4">
      <Input label="Email" placeholder="you@example.com" />
      <Input label="6-digit code" hint="We emailed it to you." />
      <Input label="Ex-showroom (₹)" defaultValue="5,83,900" error="Enter an amount above zero." />
    </div>
  ),
};

export const Cards: Story = {
  render: () => (
    <div className="grid max-w-xl gap-3">
      <Card><CardTitle>Default card</CardTitle><CardText>Supporting text.</CardText></Card>
      <Card emphasis><CardTitle>Emphasis card</CardTitle><CardText>Used for the current step.</CardText></Card>
    </div>
  ),
};

export const Badges: Story = {
  render: () => (
    <div className="flex flex-wrap gap-2">
      <Badge>Neutral</Badge><Badge tone="success">Success</Badge><Badge tone="warning">Warning</Badge><Badge tone="danger">Danger</Badge><Badge tone="info">Info</Badge>
      <EvidenceBadge evidence="DEALER_CONFIRMED" /><EvidenceBadge evidence="CATALOGUE_ESTIMATE" /><EvidenceBadge evidence="UNVERIFIED" />
    </div>
  ),
};

export const Prices: Story = {
  render: () => (
    <div className="flex flex-col gap-3">
      <Price paise={67457500} evidence="CATALOGUE_ESTIMATE" size="lg" />
      <Price paise={66347500} evidence="DEALER_CONFIRMED" />
      <Price paise={1218700} evidence="UNVERIFIED" size="sm" />
      <Price paise={117795501} label="Cash payable" />
    </div>
  ),
};

const fixedNow = (iso: string) => () => Date.parse(iso);
export const Countdowns: Story = {
  render: () => (
    <div className="flex flex-col gap-4">
      <Countdown label="Forming closes" deadline="2026-10-31T12:30:00Z" now={fixedNow('2026-10-24T12:30:00Z')} />
      <Countdown label="Sealed round closes" deadline="2026-10-24T13:30:00Z" now={fixedNow('2026-10-24T12:47:00Z')} />
      <Countdown label="Decide by" deadline="2026-10-24T12:31:00Z" now={fixedNow('2026-10-24T12:30:15Z')} />
      <Countdown label="Confirm by" deadline="2026-10-24T12:00:00Z" now={fixedNow('2026-10-24T12:30:00Z')} />
    </div>
  ),
};

export const Skeletons: Story = { render: () => <div className="grid max-w-md gap-2"><Skeleton className="h-6 w-2/3" /><Skeleton className="h-32" /></div> };

export const TabsStory: Story = {
  name: 'Tabs',
  render: () => (
    <Tabs defaultValue="ceiling">
      <TabsList aria-label="Price view"><TabsTrigger value="ceiling">At your ceiling</TabsTrigger><TabsTrigger value="final">Final</TabsTrigger></TabsList>
      <TabsContent value="ceiling" className="mt-3">Ceiling breakdown</TabsContent>
      <TabsContent value="final" className="mt-3">Final breakdown</TabsContent>
    </Tabs>
  ),
};

export const DialogOpen: Story = {
  name: 'Dialog',
  render: () => (
    <Dialog defaultOpen>
      <DialogTrigger asChild><Button>Submit sealed quote</Button></DialogTrigger>
      <DialogContent title="Submit this sealed quote?" description="Buyers in the pool will see it; other dealers will not.">
        <div className="flex justify-end gap-2"><DialogClose asChild><Button variant="secondary">Cancel</Button></DialogClose><Button>Submit</Button></div>
      </DialogContent>
    </Dialog>
  ),
};

function ToastDemo() {
  const toast = useToast();
  return (
    <div className="flex gap-2">
      <Button onClick={() => toast({ tone: 'success', title: 'Joined', description: 'You joined the pool as interested.' })}>Success toast</Button>
      <Button variant="secondary" onClick={() => toast({ tone: 'error', title: 'That did not work', description: 'Try again.' })}>Error toast</Button>
      <Button variant="secondary" onClick={() => toast({ tone: 'info', title: 'Refreshing', description: 'Latest pool state loaded.' })}>Info toast</Button>
    </div>
  );
}
export const Toasts: Story = { render: () => <ToastDemo /> };
