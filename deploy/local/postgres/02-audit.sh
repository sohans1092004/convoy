#!/bin/sh
# Audit (P5.C): the schema is owned by audit_owner (migrations only); the app logs in as audit, which V1__audit.sql grants
# INSERT and SELECT on audit_log and nothing more. Idempotent: also run by hand on an existing volume
#   docker compose exec postgres sh /docker-entrypoint-initdb.d/02-audit.sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_owner') THEN
    CREATE ROLE audit_owner LOGIN PASSWORD '$DB_PASSWORD_AUDIT_OWNER' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit') THEN
    CREATE ROLE audit LOGIN PASSWORD '$DB_PASSWORD_AUDIT' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END \$\$;
CREATE SCHEMA IF NOT EXISTS audit AUTHORIZATION audit_owner;
ALTER ROLE audit_owner SET search_path = audit;
ALTER ROLE audit SET search_path = audit;
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO audit_owner, audit;
SQL
