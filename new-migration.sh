#!/usr/bin/env bash
# Usage: ./new-migration.sh <module> <description>
# Example: ./new-migration.sh api-gateway add_totp_columns_to_users
#
# Creates a forward + undo migration pair:
#   <module>/src/main/resources/db/migrations/V<epoch>__<description>.sql
#   <module>/src/main/resources/db/migrations/U<epoch>__<description>.sql

set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <module> <description>"
  echo ""
  echo "Modules: api-gateway, probe-scheduler, result-ingestor,"
  echo "         notification-dispatcher, metrics-service, aggregate-worker"
  echo ""
  echo "Example: $0 api-gateway add_totp_columns_to_users"
  exit 1
fi

MODULE="$1"
DESC="$2"
EPOCH=$(date +%s)

declare -A MODULE_PREFIX
MODULE_PREFIX[api-gateway]=1
MODULE_PREFIX[probe-scheduler]=2
MODULE_PREFIX[result-ingestor]=3
MODULE_PREFIX[notification-dispatcher]=4
MODULE_PREFIX[metrics-service]=5
MODULE_PREFIX[aggregate-worker]=6

PREFIX="${MODULE_PREFIX[$MODULE]:-}"
if [[ -z "$PREFIX" ]]; then
  echo "Error: unknown module '$MODULE'"
  echo "Valid modules: ${!MODULE_PREFIX[*]}"
  exit 1
fi

DIR="$MODULE/src/main/resources/db/migrations"
mkdir -p "$DIR"

# Epoch-only version: unlike initial_schema (module-prefixed), timestamped
# migrations are globally ordered by epoch across all modules.
V_FILE="$DIR/V${EPOCH}__${DESC}.sql"
U_FILE="$DIR/U${EPOCH}__${DESC}.sql"

cat > "$V_FILE" << 'SQL'
-- Forward migration
SQL

cat > "$U_FILE" << 'SQL'
-- Undo migration
SQL

echo "Created:"
echo "  $V_FILE"
echo "  $U_FILE"
