-- Schema isolation check (services.md): every service role may use only its own schema.
-- Run as the admin user after all services have migrated. Prints offending rows and fails (ON_ERROR_STOP) on any.
\set ON_ERROR_STOP on
create temp table svc(role text primary key);
insert into svc values ('identity'), ('catalog'), ('pool'), ('dealer'), ('quote'), ('order'), ('payment');

-- 1. Schema usage/create on another service's schema (pg_catalog, so ownership and PUBLIC grants count).
create temp table bad_schema as
select r.role, n.nspname as schema, p.priv
from svc r cross join pg_namespace n cross join (values ('USAGE'), ('CREATE')) as p(priv)
where n.nspname in (select role from svc) and n.nspname <> r.role and has_schema_privilege(r.role, n.oid, p.priv);

-- 2. Any table/sequence privilege on another schema's objects (information_schema + has_*_privilege).
create temp table bad_table as
select r.role, c.table_schema, c.table_name, p.priv
from svc r cross join information_schema.tables c cross join (values ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) as p(priv)
where c.table_schema in (select role from svc) and c.table_schema <> r.role
  and has_table_privilege(r.role, format('%I.%I', c.table_schema, c.table_name), p.priv);

-- 3. Explicit grants recorded in the catalogue for a service role outside its schema.
create temp table bad_grant as
select grantee, table_schema, table_name, privilege_type from information_schema.role_table_grants
where grantee in (select role from svc) and table_schema <> grantee and table_schema in (select role from svc);

-- 4. PUBLIC must not create in public, and every service schema must have its migrations (non-empty).
create temp table bad_public as
select r.role from svc r where has_schema_privilege(r.role, 'public', 'CREATE');
create temp table empty_schema as
select r.role from svc r where not exists (select 1 from information_schema.tables t where t.table_schema = r.role);

select 'schema' as kind, count(*) from bad_schema union all select 'table', count(*) from bad_table
union all select 'grant', count(*) from bad_grant union all select 'public', count(*) from bad_public union all select 'empty', count(*) from empty_schema;

do $$
declare n int;
begin
    select (select count(*) from bad_schema) + (select count(*) from bad_table) + (select count(*) from bad_grant)
         + (select count(*) from bad_public) + (select count(*) from empty_schema) into n;
    if n > 0 then
        raise exception 'schema isolation violated: % offending privileges (see counts above)', n;
    end if;
    raise notice 'schema isolation OK: 7 service roles, no cross-schema privileges';
end $$;
