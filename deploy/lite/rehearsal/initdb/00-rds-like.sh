#!/bin/sh
# RDS-like: the account that sets the database up is not a superuser (CREATEROLE only) and owns the database.
set -eu
psql -v ON_ERROR_STOP=1 --username postgres --dbname convoy <<SQL
CREATE ROLE convoy_admin LOGIN CREATEROLE NOSUPERUSER PASSWORD '$POSTGRES_PASSWORD';
ALTER DATABASE convoy OWNER TO convoy_admin;
SQL
POSTGRES_USER=convoy_admin sh /init-scripts/01-schemas.sh
POSTGRES_USER=convoy_admin sh /init-scripts/02-audit.sh
psql --username postgres -Atc "select 'convoy_admin superuser=' || rolsuper from pg_roles where rolname='convoy_admin'"
