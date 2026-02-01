#!/bin/bash

# ========================================
# Aura 서비스 DB(dwp_aura) 테이블/마이그레이션 확인
# ========================================
# dwp_aura DB 내 dwp_aura 스키마에 Flyway가 테이블을 생성합니다.
# 이 스크립트로 Flyway 적용 여부와 테이블 목록을 확인합니다.
#
# 사용법:
#   ./scripts/check-aura-db-tables.sh
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-dwp_aura}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
# docker-compose는 POSTGRES_USER=dwp_user 로 생성하므로 기본값 dwp_user
PG_SUPERUSER=${PG_SUPERUSER:-dwp_user}
SCHEMA_NAME=${SCHEMA_NAME:-dwp_aura}

if command -v psql &> /dev/null; then
    USE_DOCKER=false
    # 로컬 Postgres(Homebrew 등)는 슈퍼유저가 현재 사용자인 경우 많음
    [ "$PG_SUPERUSER" = "dwp_user" ] && PG_SUPERUSER=${USER:-$(whoami)}
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
echo -e "${BLUE}Aura 서비스 DB(dwp_aura) 확인${NC}"
echo -e "${BLUE}========================================${NC}"
echo "  Host: ${DB_HOST}:${DB_PORT}"
echo "  Database: ${DB_NAME}"
echo "  User: $([ "$USE_DOCKER" = true ] && echo "${PG_SUPERUSER}" || echo "${DB_USERNAME}")"
echo "  Schema: ${SCHEMA_NAME}"
echo ""

# 0. 접속 테스트 (실패 시 에러 출력)
echo -e "${YELLOW}[0] DB 접속 테스트${NC}"
if [ "$USE_DOCKER" = true ]; then
    DOCKER_ERR=$(docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -c "SELECT 1" 2>&1) || true
    if [ $? -ne 0 ] || echo "$DOCKER_ERR" | grep -q "FATAL\|error\|could not"; then
        echo -e "  ${RED}접속 실패:${NC}"
        echo "$DOCKER_ERR" | sed 's/^/  /'
        echo -e "  ${YELLOW}직접 확인: docker exec -it ${POSTGRES_CONTAINER} psql -U ${PG_SUPERUSER} -d ${DB_NAME} -c \"\\\\dn\"${NC}"
        exit 1
    fi
else
    CONN_ERR=$(PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${DB_USERNAME}" -d "${DB_NAME}" -c "SELECT 1" 2>&1) || true
    if echo "$CONN_ERR" | grep -q "FATAL\|error\|could not connect"; then
        echo -e "  ${RED}접속 실패:${NC}"
        echo "$CONN_ERR" | sed 's/^/  /'
        echo -e "  ${YELLOW}→ Aura 서버와 동일한 DB를 쓰려면 동일한 Host/Port/User/DB를 사용해야 합니다.${NC}"
        echo -e "  ${YELLOW}→ Docker Postgres를 쓰는 경우: psql을 제거하거나, 스크립트를 Docker로 실행하세요.${NC}"
        exit 1
    fi
fi
echo -e "  ${GREEN}접속 OK${NC}"
echo ""

# 1. dwp_aura 스키마 존재 여부
echo -e "${YELLOW}[1] 스키마 존재 여부${NC}"
SCHEMA_EXISTS=$(run_sql "SELECT 1 FROM pg_namespace WHERE nspname = '${SCHEMA_NAME}' LIMIT 1;" 2>/dev/null || echo "")
if [ -z "$SCHEMA_EXISTS" ] || [ "$SCHEMA_EXISTS" != "1" ]; then
    echo -e "  ${RED}스키마 '${SCHEMA_NAME}' 없음.${NC}"
    echo -e "  → Aura 서버를 한 번 기동하면 Flyway가 V1/V2에서 스키마를 생성합니다."
else
    echo -e "  ${GREEN}스키마 '${SCHEMA_NAME}' 존재${NC}"
fi
echo ""

# 2. Flyway 히스토리 (Aura는 schemas: dwp_aura 이므로 테이블이 dwp_aura 스키마에 있음)
echo -e "${YELLOW}[2] Flyway 마이그레이션 이력 (dwp_aura 스키마)${NC}"
HAS_FLYWAY=$(run_sql "SELECT 1 FROM information_schema.tables WHERE table_schema='${SCHEMA_NAME}' AND table_name='flyway_schema_history' LIMIT 1;" 2>/dev/null || echo "")
if [ -z "$HAS_FLYWAY" ] || [ "$HAS_FLYWAY" != "1" ]; then
    echo -e "  ${RED}flyway_schema_history 없음 → Flyway가 아직 실행되지 않았습니다.${NC}"
    echo -e "  → Aura 서버 기동 시 Flyway가 V1~V4 마이그레이션을 실행합니다."
else
    echo -e "  ${GREEN}적용된 마이그레이션:${NC}"
    run_sql_verbose "SET search_path TO ${SCHEMA_NAME}; SELECT installed_rank, version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
fi
echo ""

# 3. dwp_aura 스키마 내 테이블 목록
echo -e "${YELLOW}[3] dwp_aura 스키마 테이블 목록${NC}"
TABLES=$(run_sql "SELECT tablename FROM pg_tables WHERE schemaname='${SCHEMA_NAME}' AND tablename != 'flyway_schema_history' ORDER BY tablename;" 2>/dev/null || echo "")
if [ -z "$TABLES" ]; then
    echo -e "  ${RED}애플리케이션 테이블 없음.${NC}"
    echo -e "  → Aura 서버 기동 후 Flyway가 V3/V4에서 테이블을 생성합니다."
else
    echo "$TABLES" | while read -r t; do
        [ -n "$t" ] && echo "  - $t"
    done
    COUNT=$(echo "$TABLES" | grep -c . || echo 0)
    echo -e "  ${GREEN}총 ${COUNT}개 테이블 (flyway_schema_history 제외)${NC}"
fi
echo ""

echo -e "${BLUE}참고: Aura 서버 기동 로그에서 'Flyway', 'Migrating', 'Successfully applied' 검색해 보세요.${NC}"
