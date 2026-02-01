#!/bin/bash

# ========================================
# dwp_main 데이터베이스 생성 (Main Service용)
# ========================================
# Flyway는 스키마만 자동 생성 가능하고, 데이터베이스(DB)는 생성하지 못합니다.
# 이 스크립트로 dwp_main DB를 한 번 생성한 뒤 Main 서비스를 기동하세요.
#
# 사용법:
#   ./scripts/create-dwp-main-db.sh
#
# 로컬 PostgreSQL(Homebrew 등): 슈퍼유저가 postgres가 아니면 다음처럼 지정
#   PG_SUPERUSER=$(whoami) ./scripts/create-dwp-main-db.sh
# Docker: 컨테이너 슈퍼유저가 postgres가 아니면
#   PG_SUPERUSER=dwp_user ./scripts/create-dwp-main-db.sh
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
# Docker(docker-compose): POSTGRES_USER=dwp_user 이므로 슈퍼유저는 dwp_user. 로컬: postgres 또는 현재 사용자
PG_SUPERUSER=${PG_SUPERUSER:-}

if command -v psql &> /dev/null; then
    USE_DOCKER=false
    # 로컬(Homebrew 등): postgres 역할이 없을 수 있음 → 현재 사용자를 슈퍼유저로 사용
    [ -z "$PG_SUPERUSER" ] && PG_SUPERUSER="${USER:-$(whoami)}"
elif docker ps --format "{{.Names}}" | grep -q "^${POSTGRES_CONTAINER}$"; then
    USE_DOCKER=true
    # 본 프로젝트 docker-compose는 POSTGRES_USER=dwp_user 이므로 기본값 dwp_user
    [ -z "$PG_SUPERUSER" ] && PG_SUPERUSER="${DB_USERNAME:-dwp_user}"
else
    echo -e "${RED}오류: psql 없고 Docker 컨테이너 '${POSTGRES_CONTAINER}'도 실행 중이 아닙니다.${NC}"
    echo "  docker-compose up -d postgres  또는  brew install postgresql@15"
    exit 1
fi

echo -e "${YELLOW}dwp_main 데이터베이스 생성 중... (슈퍼유저: ${PG_SUPERUSER})${NC}"

if [ "$USE_DOCKER" = true ]; then
    docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'dwp_main'" | grep -q 1 && {
        echo -e "${GREEN}dwp_main DB가 이미 존재합니다.${NC}"
        exit 0
    }
    docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d postgres -c "CREATE DATABASE dwp_main;"
    docker exec ${POSTGRES_CONTAINER} psql -U "${PG_SUPERUSER}" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE dwp_main TO ${DB_USERNAME};"
else
    PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${PG_SUPERUSER}" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'dwp_main'" | grep -q 1 && {
        echo -e "${GREEN}dwp_main DB가 이미 존재합니다.${NC}"
        exit 0
    }
    PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${PG_SUPERUSER}" -d postgres -c "CREATE DATABASE dwp_main;"
    PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -p ${DB_PORT} -U "${PG_SUPERUSER}" -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE dwp_main TO ${DB_USERNAME};"
fi

echo -e "${GREEN}✓ dwp_main DB 생성 완료. Main 서비스를 기동하면 Flyway가 스키마/테이블을 자동 생성합니다.${NC}"
