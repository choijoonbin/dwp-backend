#!/bin/bash
# SynapseX API/정책/감사 로그 SQL 검증 스크립트
# CI에서 선택적 실행 가능

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-dwp_aura}"
DB_USER="${DB_USER:-dwp_user}"
DB_PASSWORD="${DB_PASSWORD:-dwp_password}"
TENANT_ID="${TENANT_ID:-1}"

export PGPASSWORD="$DB_PASSWORD"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -v tenantId="$TENANT_ID" \
  -f "$SCRIPT_DIR/verify_synapse_audit.sql"
unset PGPASSWORD

echo "Synapse verification 완료."
