#!/bin/bash

# ========================================
# Aura Flyway V2 checksum repair
# ========================================
# V2 마이그레이션 파일명 변경(arua→aura)으로 checksum 불일치가 발생한 경우
# flyway_schema_history의 V2 checksum을 현재 파일 기준으로 갱신합니다.
#
# 사용법:
#   ./scripts/flyway-repair-aura-v2.sh
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

DB_NAME=${DB_NAME:-dwp_aura}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
PG_SUPERUSER=${PG_SUPERUSER:-dwp_user}

# Flyway가 "Resolved locally"로 보고한 새 checksum (파일명/내용 변경 후)
NEW_CHECKSUM=-111514194

if command -v psql &> /dev/null; then
    USE_DOCKER=false
    [ "$PG_SUPERUSER" = "dwp_user" ] && PG_SUPERUSER=${USER:-$(whoami)}
elif docker ps --format "{{.Names}}" | grep -q "^${POSTGRES_CONTAINER}$"; then
    USE_DOCKER=true
else
    echo -e "${RED}오류: psql 없고 Docker 컨테이너 '${POSTGRES_CONTAINER}'도 실행 중이 아닙니다.${NC}"
    exit 1
fi

echo -e "${YELLOW}Aura Flyway V2 checksum repair (dwp_aura DB)${NC}"
echo ""

if [ "$USE_DOCKER" = true ]; then
    docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -c \
        "UPDATE dwp_aura.flyway_schema_history SET checksum = ${NEW_CHECKSUM} WHERE version = '2';"
else
    PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${DB_USERNAME}" -d "${DB_NAME}" -c \
        "UPDATE dwp_aura.flyway_schema_history SET checksum = ${NEW_CHECKSUM} WHERE version = '2';"
fi

echo -e "${GREEN}✓ V2 checksum이 ${NEW_CHECKSUM} 로 갱신되었습니다. Aura 서버를 다시 기동해 보세요.${NC}"
