import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { Breakdown, type EngineOutput } from './breakdown';
import { CommandStatus } from './command-status';
import pricing from '../../spec-tools/vectors/pricing.json';

const meta: Meta = { title: 'Interaction states' };
export default meta;
type Story = StoryObj;

// The five action states used for join, commit, confirm and quote submit (frontend-quality.md).
export const Idle: Story = { render: () => <CommandStatus state={{ kind: 'idle' }} /> };
export const Pending: Story = { render: () => <CommandStatus state={{ kind: 'pending', message: 'Payment received by Razorpay — confirming (usually under a minute). TEST: no real money.' }} /> };
export const ErrorKeepsInput: Story = { name: 'Error (input kept)', render: () => <CommandStatus state={{ kind: 'error', problem: { status: 503, code: 'DEPENDENCY_UNAVAILABLE', detail: 'Payments are unavailable right now; nothing was charged. Retry with the same Idempotency-Key.' } }} /> };
export const ConflictDeadline: Story = {
  name: 'Conflict (deadline passed)',
  render: () => <CommandStatus state={{ kind: 'conflict', problem: { status: 409, code: 'DEADLINE_PASSED', detail: 'The sealed round closed before your submission reached our database. Nothing was saved.', deadline: '2026-11-20T12:30:00Z', server_time: '2026-11-20T12:30:00.412Z' } }} />,
};
export const ConflictVersion: Story = { name: 'Conflict (version changed)', render: () => <CommandStatus state={{ kind: 'conflict', problem: { status: 409, code: 'VERSION_CONFLICT', detail: 'Your membership changed; reload and try again.' } }} /> };
export const Success: Story = { render: () => <CommandStatus state={{ kind: 'success' }} successText="You are committed. Your ₹999 test deposit is held, not paid to anyone." /> };

// Real engine output of pricing vector P050 (full configuration), so the breakdown shows every line type.
const p050 = (pricing as { cases: { id: string; expected: EngineOutput }[] }).cases.find((c) => c.id === 'P050')!.expected;
export const PriceBreakdown: Story = { render: () => <div className="max-w-2xl"><Breakdown output={p050} caption="Full configuration (vector P050)" /></div> };
