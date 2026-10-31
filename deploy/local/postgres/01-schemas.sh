#!/bin/sh
# One database, one schema + one login role per service, no cross-schema grants (services.md).
# Passwords come from the environment (.env); nothing secret is committed.
set -eu
for svc in identity catalog pool dealer quote order payment realtime notification; do
  var="DB_PASSWORD_$(echo "$svc" | tr '[:lower:]' '[:upper:]')"
  pw=$(printenv "$var")
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$svc') THEN
    CREATE ROLE "$svc" LOGIN PASSWORD '$pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END \$\$;
-- RDS: the admin is not a superuser and must be a member of a role to make it a schema owner (PostgreSQL 16).
GRANT "$svc" TO CURRENT_USER;
CREATE SCHEMA IF NOT EXISTS "$svc" AUTHORIZATION "$svc";
ALTER ROLE "$svc" SET search_path = "$svc";
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO "$svc";
SQL
done
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE "$POSTGRES_DB" FROM PUBLIC;
SQL
