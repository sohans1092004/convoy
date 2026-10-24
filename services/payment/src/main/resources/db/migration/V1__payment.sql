-- payment schema (deposit-contract.md, AMENDMENT-003). Money is bigint paise.
create table payments (
    id                  uuid primary key,
    deposit_key         text not null unique,          -- deposit:{buyer}:{pool}:{round_set}
    buyer_id            uuid not null,
    pool_id             uuid not null,
    round_set           int not null,
    amount_paise        bigint not null check (amount_paise > 0),
    currency            text not null default 'INR',
    state               text not null,
    razorpay_order_id   text unique,
    razorpay_payment_id text,
    booking_id          uuid,
    failure_code        text,
    refund_on_capture   boolean not null default false,
    captured_at         timestamptz,
    version             bigint not null default 1,
    created_at          timestamptz not null default clock_timestamp()
);
create index payments_by_buyer_pool on payments (buyer_id, pool_id);

create table webhook_inbox (
    event_id    text primary key,                     -- x-razorpay-event-id
    event       text not null,
    received_at timestamptz not null default clock_timestamp()
);

create table refunds (
    id                 uuid primary key,
    payment_id         uuid not null unique references payments(id),  -- at most one refund per deposit
    kind               text not null check (kind in ('REFUND', 'RELEASE')),
    reason_code        text not null,
    related_reasons    text[] not null default '{}',
    amount_paise       bigint not null check (amount_paise > 0),
    source_account     text not null check (source_account in ('2100', '2200')),
    state              text not null,
    razorpay_refund_id text,
    attempts           int not null default 0,
    next_attempt_at    timestamptz not null default clock_timestamp(),
    failure_reason     text,
    version            bigint not null default 1,
    created_at         timestamptz not null default clock_timestamp(),
    processed_at       timestamptz
);

-- Double-entry ledger (deposit-contract.md §5). Posted entries are immutable.
create table journal_entries (
    id         uuid primary key,
    kind       text not null,
    payment_id uuid not null references payments(id),
    created_at timestamptz not null default clock_timestamp()
);
create table journal_lines (
    entry_id uuid not null references journal_entries(id),
    line_no  int not null,
    account  text not null check (account in ('1100', '1200', '1300', '2100', '2200', '4100')),
    dr       bigint not null check (dr >= 0),
    cr       bigint not null check (cr >= 0),
    check ((dr = 0) <> (cr = 0)),
    primary key (entry_id, line_no)
);

create function forbid_ledger_change() returns trigger language plpgsql as $$
begin
    raise exception 'ledger rows are immutable; post a compensating entry';
end $$;
create trigger journal_entries_immutable before update or delete on journal_entries for each row execute function forbid_ledger_change();
create trigger journal_lines_immutable before update or delete on journal_lines for each row execute function forbid_ledger_change();

-- Every entry balances: checked at commit so all lines of an entry are in place first.
create function check_entry_balanced() returns trigger language plpgsql as $$
begin
    if (select sum(dr) <> sum(cr) from journal_lines where entry_id = new.entry_id) then
        raise exception 'journal entry % does not balance', new.entry_id;
    end if;
    return null;
end $$;
create constraint trigger journal_lines_balanced after insert on journal_lines deferrable initially deferred
    for each row execute function check_entry_balanced();
