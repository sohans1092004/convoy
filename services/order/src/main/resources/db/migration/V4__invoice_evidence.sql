-- P5.E E101: the buyer confirms the invoice total after delivery (savings label 4, realised saving).
alter table bookings add column invoice_total_paise bigint check (invoice_total_paise > 0),
                     add column invoice_confirmed_at timestamptz,
                     add check ((invoice_total_paise is null) = (invoice_confirmed_at is null));
-- P5.E: an evidence file name is the uploader's own text and is cleared on erasure (retention.md); the file stays with its dispute.
alter table dispute_evidence alter column file_name drop not null;
