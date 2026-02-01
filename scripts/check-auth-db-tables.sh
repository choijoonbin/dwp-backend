#!/bin/bash

# ========================================
# Auth 서버 DB(dwp_auth) 테이블/마이그레이션 확인
# ========================================
# Flyway 적용 여부와 테이블 목록을 확인합니다.
#
# 사용법:
#   ./scripts/check-auth-db-tables.sh
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-dwp_auth}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
PG_SUPERUSER=${PG_SUPERUSER:-postgres}

if command -v psql &> /dev/null; then
    USE_DOCKER=false
    [ "$PG_SUPERUSER" = "postgres" ] && PG_SUPERUSER=${USER:-$(whoami)}
elif docker ps --format "{{.Names}}" | grep -q "^${POSTGRES_CONTAINER}$"; then
    USE_DOCKER=true
else
    echo -e "${RED}오류: psql 없고 Docker 컨테이너 '${POSTGRES_CONTAINER}'도 실행 중이 아닙니다.${NC}"
    exit 1
fi

run_sql() {
    local sql="$1"
    if [ "$USE_DOCKER" = true ]; then
        docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -t -A -c "$sql"
    else
        PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${DB_USERNAME}" -d "${DB_NAME}" -t -A -c "$sql" 2>/dev/null || true
    fi
}

run_sql_verbose() {
    local sql="$1"
    if [ "$USE_DOCKER" = true ]; then
        docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -c "$sql"
    else
        PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${DB_USERNAME}" -d "${DB_NAME}" -c "$sql" 2>/dev/null || true
    fi
}

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Auth DB(dwp_auth) 확인${NC}"
echo -e "${BLUE}========================================${NC}"
echo "  Host: ${DB_HOST}:${DB_PORT}"
echo "  Database: ${DB_NAME}"
echo ""

# 1. Flyway 히스토리 테이블 존재 여부
echo -e "${YELLOW}[1] Flyway 마이그레이션 이력${NC}"
HAS_FLYWAY=$(run_sql "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history' LIMIT 1;" 2>/dev/null || echo "")
if [ -z "$HAS_FLYWAY" ] || [ "$HAS_FLYWAY" != "1" ]; then
    echo -e "  ${RED}flyway_schema_history 테이블 없음 → Flyway가 한 번도 실행되지 않은 상태입니다.${NC}"
    echo -e "  → Auth 서버 기동 시 Flyway가 마이그레이션을 실행해야 합니다. 로그에서 'Flyway' / 'Migrating' 검색해 보세요."
else
    echo -e "  ${GREEN}flyway_schema_history 존재. 적용된 버전:${NC}"
    run_sql_verbose "SELECT installed_rank, version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
fi
echo ""

# 2. public 스키마 테이블 목록
echo -e "${YELLOW}[2] public 스키마 테이블 목록${NC}"
TABLES=$(run_sql "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;" 2>/dev/null || echo "")
if [ -z "$TABLES" ]; then
    echo -e "  ${RED}테이블 없음.${NC}"
else
    echo "$TABLES" | while read -r t; do
        [ -n "$t" ] && echo "  - $t"
    done
    COUNT=$(echo "$TABLES" | grep -c . || echo 0)
    echo -e "  ${GREEN}총 ${COUNT}개 테이블${NC}"
fi
echo ""

echo -e "${BLUE}추가 확인: Auth 서버 기동 로그에서 다음을 검색해 보세요.${NC}"
echo "  - 'Flyway'"
echo "  - 'Migrating schema' / 'Successfully applied'"
echo "  - 'validate' (Flyway 검증 실패 시 에러)"
