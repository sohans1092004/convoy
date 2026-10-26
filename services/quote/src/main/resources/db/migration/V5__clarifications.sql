-- P5.E structured clarifications (PLAN §3.1 item 11): a buyer asks about one FINAL quote with a fixed question type
-- and short text; the dealer answers once; answers are shown to the pool's committed members.
create table clarifications (
    id            uuid primary key,
    pool_id       uuid not null,
    quote_id      uuid not null references quotes(id),
    buyer_id      uuid not null,
    question_type text not null check (question_type in ('DELIVERY_DATE', 'STOCK_COLOUR', 'PRICE_ITEM', 'INSURANCE', 'ACCESSORIES', 'DOCUMENTS', 'OTHER')),
    question      text check (length(question) between 1 and 300), -- cleared on the asker's erasure (retention.md)
    answer        text check (length(answer) between 1 and 500),
    answered_by   uuid,
    asked_at      timestamptz not null default clock_timestamp(),
    answered_at   timestamptz,
    check ((answer is null) = (answered_at is null))
);
create index clarifications_quote on clarifications (quote_id, asked_at);
