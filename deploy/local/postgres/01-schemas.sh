#!/bin/sh
# One database, one schema + one login role per service, no cross-schema grants (services.md).
# Passwords come from the environment (.env); nothing secret is committed.
set -eu
for svc in identity catalog pool dealer quote order payment; do
  var="DB_PASSWORD_$(echo "$svc" | tr '[:lower:]' '[:upper:]')"
  pw=$(printenv "$var")
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE "$svc" LOGIN PASSWORD '$pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE SCHEMA "$svc" AUTHORIZATION "$svc";
ALTER ROLE "$svc" SET search_path = "$svc";
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO "$svc";
SQL
done
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE "$POSTGRES_DB" FROM PUBLIC;
SQL
