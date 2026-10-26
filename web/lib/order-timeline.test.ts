import { describe, expect, it } from 'vitest';
import { timeline, type TimelineBooking } from './order-timeline';

const booking = (state: string, extra: Partial<TimelineBooking> = {}): TimelineBooking => ({ state, voucher_active: true, scheduled_date: null, overdue_flag: false, ...extra });
const statuses = (r: ReturnType<typeof timeline>) => Object.fromEntries(r.steps.map((s) => [s.key, s.status]));

describe('order timeline', () => {
  it('asks to confirm first', () => {
    const r = timeline({ state: 'CONFIRMATION_PENDING' }, 'CAPTURED');
    expect(r.next).toBe('confirm');
    expect(statuses(r)).toMatchObject({ accepted: 'done', confirmation: 'current', deposit: 'upcoming', outcome: 'upcoming' });
  });

  it('waits for the resolution, then for the voucher while the deposit is being held', () => {
    expect(timeline({ state: 'CONFIRMED', confirmed_at: 'x' }, 'CAPTURED').next).toBe('waitResolution');
    const r = timeline({ state: 'BOOKED', confirmed_at: 'x', booking: booking('ISSUED', { voucher_active: false }) }, 'CAPTURED');
    expect(r.next).toBe('waitVoucher');
    expect(statuses(r)).toMatchObject({ confirmation: 'done', deposit: 'current', issued: 'current' });
  });

  it('walks the booking to delivery with one next action each time', () => {
    const held = 'HELD_FOR_BOOKING';
    expect(timeline({ state: 'BOOKED', confirmed_at: 'x', booking: booking('ISSUED') }, held).next).toBe('waitDealerConfirm');
    expect(timeline({ state: 'BOOKED', confirmed_at: 'x', booking: booking('DEALER_CONFIRMED') }, held).next).toBe('waitDeliveryDate');
    expect(timeline({ state: 'BOOKED', confirmed_at: 'x', booking: booking('DEALER_CONFIRMED', { overdue_flag: true }) }, held).next).toBe('openDispute');
    const sched = timeline({ state: 'BOOKED', confirmed_at: 'x', booking: booking('DELIVERY_SCHEDULED', { scheduled_date: '2026-11-01' }) }, held);
    expect(sched.next).toBe('confirmDelivery');
    expect(statuses(sched)).toMatchObject({ deposit: 'done', issued: 'done', dealerConfirmed: 'done', scheduled: 'done', outcome: 'current' });
    const done = timeline({ state: 'COMPLETED', confirmed_at: 'x', booking: booking('DELIVERED') }, 'RELEASED');
    expect(done.next).toBe('review');
    expect(done.steps.every((s) => s.status === 'done')).toBe(true);
  });

  it('a cancellation ends the remaining steps and points at the refund', () => {
    const r = timeline({ state: 'CANCELLED', confirmed_at: 'x', booking: booking('CANCELLED') }, 'REFUND_PENDING');
    expect(r.next).toBe('refunded');
    expect(statuses(r)).toMatchObject({ confirmation: 'done', dealerConfirmed: 'ended', scheduled: 'ended', outcome: 'done' });
    expect(timeline({ state: 'DECLINED' }, 'REFUNDED').next).toBe('refunded');
  });
});
