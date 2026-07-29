#!/usr/bin/env bash
set -euo pipefail

# Runs once, on first container init (docker-entrypoint-initdb.d), after the
# entrypoint has already created $POSTGRES_DB (mugen_user). mugen-payment
# needs its own database, never sharing mugen-user's — see architecture rule
# "services never share databases."
psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "postgres" <<-EOSQL
    CREATE DATABASE ${POSTGRES_PAYMENT_DB};
EOSQL
