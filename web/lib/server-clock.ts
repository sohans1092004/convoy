// Client clock offset from Server-Time headers (deadline-rule.md §3): offset = server - (send + recv) / 2,
// median of the last 5 samples, samples with RTT > 2 s discarded.
const samples: number[] = [];

export function recordServerTime(serverTimeIso: string | null, sentAt: number, receivedAt: number) {
  if (!serverTimeIso || receivedAt - sentAt > 2000) return;
  const offset = Date.parse(serverTimeIso) - (sentAt + receivedAt) / 2;
  if (Number.isNaN(offset)) return;
  samples.push(offset);
  if (samples.length > 5) samples.shift();
}

export function clockOffset(): number {
  if (!samples.length) return 0;
  const s = [...samples].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
}

export function serverNow(): number {
  return Date.now() + clockOffset();
}
