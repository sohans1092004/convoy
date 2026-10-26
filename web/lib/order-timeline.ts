// Buyer order timeline (P4.D): accepted -> confirmation -> deposit outcome -> booking issued -> dealer confirmed ->
// delivery scheduled -> delivered or cancelled, with exactly one next action. Pure, so it is unit-tested.

export type StepKey = 'accepted' | 'confirmation' | 'deposit' | 'issued' | 'dealerConfirmed' | 'scheduled' | 'outcome';
export type StepStatus = 'done' | 'current' | 'upcoming' | 'ended';
export type NextAction = 'confirm' | 'waitResolution' | 'waitVoucher' | 'waitDealerConfirm' | 'waitDeliveryDate' | 'openDispute' | 'confirmDelivery' | 'review' | 'refunded' | 'none';
export type DepositState = 'INITIATED' | 'PENDING' | 'CAPTURED' | 'HELD_FOR_BOOKING' | 'REFUND_PENDING' | 'REFUNDED' | 'RELEASED' | 'FAILED' | 'VOIDED_UNPAID' | string;

export interface TimelineBooking { state: string; voucher_active: boolean; scheduled_date?: string | null; overdue_flag: boolean }
export interface TimelineOrder { state: string; confirmed_at?: string | null; booking?: TimelineBooking | null }

const ENDED = new Set(['DECLINED', 'VOIDED', 'CANCELLED']);

export function timeline(o: TimelineOrder, deposit: DepositState | null): { steps: { key: StepKey; status: StepStatus }[]; next: NextAction } {
  const b = o.booking ?? null;
  const ended = ENDED.has(o.state) || b?.state === 'CANCELLED';
  const delivered = b?.state === 'DELIVERED';
  const confirmedOnce = !!o.confirmed_at;
  const bState = b?.state ?? '';
  const status = (done: boolean, current: boolean): StepStatus => (done ? 'done' : ended ? 'ended' : current ? 'current' : 'upcoming');

  const steps: { key: StepKey; status: StepStatus }[] = [
    { key: 'accepted', status: 'done' },
    { key: 'confirmation', status: status(confirmedOnce, o.state === 'CONFIRMATION_PENDING') },
    { key: 'deposit', status: deposit && ['HELD_FOR_BOOKING', 'REFUNDED', 'RELEASED'].includes(deposit) ? 'done' : status(false, o.state === 'CONFIRMED' || (!!b && !b.voucher_active)) },
    { key: 'issued', status: status(!!b && (b.voucher_active || delivered), !!b && !b.voucher_active) },
    { key: 'dealerConfirmed', status: status(['DEALER_CONFIRMED', 'DELIVERY_SCHEDULED', 'DELIVERED'].includes(bState), bState === 'ISSUED' && !!b?.voucher_active) },
    { key: 'scheduled', status: status(['DELIVERY_SCHEDULED', 'DELIVERED'].includes(bState), bState === 'DEALER_CONFIRMED') },
    { key: 'outcome', status: delivered || ended ? 'done' : status(false, bState === 'DELIVERY_SCHEDULED') },
  ];

  let next: NextAction = 'none';
  if (ended) next = 'refunded';
  else if (o.state === 'CONFIRMATION_PENDING') next = 'confirm';
  else if (o.state === 'CONFIRMED') next = 'waitResolution';
  else if (delivered) next = 'review';
  else if (b && !b.voucher_active) next = 'waitVoucher';
  else if (bState === 'ISSUED') next = 'waitDealerConfirm';
  else if (b?.overdue_flag) next = 'openDispute';
  else if (bState === 'DEALER_CONFIRMED') next = 'waitDeliveryDate';
  else if (bState === 'DELIVERY_SCHEDULED') next = 'confirmDelivery';
  return { steps, next };
}
