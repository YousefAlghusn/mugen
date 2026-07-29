#!/usr/bin/env bash
set -euo pipefail

# Run as a one-shot compose service (same mssql image, which ships sqlcmd)
# once the sqlserver container reports healthy. Flyway (Phase 2.2) manages
# schema inside this database — it does not create the database itself.
SQLCMD="/opt/mssql-tools18/bin/sqlcmd"

"${SQLCMD}" -C -S "${SQLSERVER_HOST}" -U "${SQLSERVER_USER}" -P "${SQLSERVER_PASSWORD}" -Q "
IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = N'${SQLSERVER_DB}')
BEGIN
    CREATE DATABASE [${SQLSERVER_DB}];
END
"

echo "Database ${SQLSERVER_DB} ready."
