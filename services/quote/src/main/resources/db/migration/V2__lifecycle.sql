-- P3.B quote lifecycle: withdrawal (QUO-T05) and post-FINAL cancellation (QUO-T06); versions are immutable.
alter table quotes add column withdraw_reason text, add column after_final boolean not null default false;

-- A submitted version is evidence (price snapshots, ruleset): never edited or deleted, only superseded by version n+1.
create function quote_versions_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'quote_versions rows are immutable (quote %, version %)', old.quote_id, old.version_no;
end $$;
create trigger quote_versions_no_change before update or delete on quote_versions for each row execute function quote_versions_immutable();
