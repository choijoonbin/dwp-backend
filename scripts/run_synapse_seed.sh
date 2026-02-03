#!/bin/bash
# Synapse Phase 시드 실행
# 전제: PostgreSQL dwp_aura DB 기동, Flyway 마이그레이션 완료

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-dwp_aura}"
DB_USER="${DB_USER:-dwp_user}"
DB_PASSWORD="${DB_PASSWORD:-dwp_password}"

export PGPASSWORD="$DB_PASSWORD"
# Phase2~4 스펙: documents 5, open-items 5, entities 3, cases 3, actions 2, audit 10
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/seed/phase2-4-seed.sql"
unset PGPASSWORD

echo "Synapse seed 완료."
