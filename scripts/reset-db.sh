#!/bin/bash

# ========================================
# DWP Auth Server 데이터베이스 초기화 스크립트
# ========================================
# 개발 환경에서 데이터베이스를 완전히 초기화합니다.
#
# 사용법:
#   ./scripts/reset-db.sh              # 완전 초기화 (DB 삭제 후 재생성)
#   ./scripts/reset-db.sh --flyway-only # Flyway 히스토리만 삭제
#   ./scripts/reset-db.sh --skip-drop   # DB 삭제 건너뛰기
# ========================================

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 환경 변수 (기본값)
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-dwp_auth}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}

# PostgreSQL 클라이언트 확인 및 실행 방법 결정
if command -v psql &> /dev/null; then
    PSQL_CMD="psql"
    USE_DOCKER=false
elif docker ps --format "{{.Names}}" | grep -q "^${POSTGRES_CONTAINER}$"; then
    PSQL_CMD="docker exec -i ${POSTGRES_CONTAINER} psql"
    USE_DOCKER=true
    echo -e "${BLUE}[정보] Docker 컨테이너를 통해 PostgreSQL에 접속합니다${NC}"
else
    echo -e "${RED}오류: psql 명령어를 찾을 수 없고 Docker 컨테이너도 실행 중이지 않습니다${NC}"
    echo ""
    echo "해결 방법:"
    echo "  1. PostgreSQL 클라이언트 설치:"
    echo "     brew install postgresql@15"
    echo ""
    echo "  2. 또는 Docker 컨테이너 시작:"
    echo "     docker-compose up -d postgres"
    exit 1
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}DWP Auth Server 데이터베이스 초기화${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "데이터베이스 정보:"
echo "  Host: ${DB_HOST}"
echo "  Port: ${DB_PORT}"
echo "  Database: ${DB_NAME}"
echo "  Username: ${DB_USERNAME}"
echo ""

# Flyway 히스토리만 삭제하는 경우
if [ "$1" == "--flyway-only" ]; then
    echo -e "${YELLOW}[옵션] Flyway 히스토리만 삭제합니다${NC}"
    echo ""
    echo -e "${GREEN}Flyway 히스토리 삭제 중...${NC}"
    if [ "$USE_DOCKER" = true ]; then
        docker exec ${POSTGRES_CONTAINER} psql -U ${DB_USERNAME} -d ${DB_NAME} -c "DROP TABLE IF EXISTS flyway_schema_history;" 2>/dev/null || {
            echo -e "${YELLOW}flyway_schema_history 테이블이 없거나 삭제할 수 없습니다${NC}"
        }
    else
        PGPASSWORD=${DB_PASSWORD} ${PSQL_CMD} -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USERNAME} -d ${DB_NAME} -c "DROP TABLE IF EXISTS flyway_schema_history;" 2>/dev/null || {
            echo -e "${YELLOW}flyway_schema_history 테이블이 없거나 삭제할 수 없습니다${NC}"
        }
    fi
    echo -e "${GREEN}✓ 완료${NC}"
    echo ""
    echo -e "${GREEN}다음 단계: 애플리케이션을 시작하면 Flyway가 자동으로 마이그레이션을 실행합니다${NC}"
    echo "  ./gradlew :dwp-auth-server:bootRun"
    exit 0
fi

# 확인 메시지
if [ "$1" != "--skip-drop" ]; then
    echo -e "${RED}⚠️  경고: 기존 데이터베이스 '${DB_NAME}'가 완전히 삭제됩니다!${NC}"
    echo -e "${YELLOW}계속하시겠습니까? (y/N)${NC}"
    read -r response
    if [[ ! "$response" =~ ^[Yy]$ ]]; then
        echo "취소되었습니다."
        exit 0
    fi
    echo ""
fi

# 1. 기존 데이터베이스 삭제 (선택적)
if [ "$1" != "--skip-drop" ]; then
    echo -e "${GREEN}[1/2] 기존 데이터베이스 삭제 중...${NC}"
    if [ "$USE_DOCKER" = true ]; then
        docker exec ${POSTGRES_CONTAINER} psql -U ${DB_USERNAME} -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};" 2>/dev/null || {
            echo -e "${YELLOW}데이터베이스 삭제 중 오류 발생 (존재하지 않을 수 있음)${NC}"
        }
    else
        PGPASSWORD=${DB_PASSWORD} ${PSQL_CMD} -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USERNAME} -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};" 2>/dev/null || {
            echo -e "${YELLOW}데이터베이스 삭제 중 오류 발생 (존재하지 않을 수 있음)${NC}"
        }
    fi
    echo -e "${GREEN}✓ 완료${NC}"
    echo ""
fi

# 2. 새 데이터베이스 생성
echo -e "${GREEN}[2/2] 새 데이터베이스 생성 중...${NC}"
if [ "$USE_DOCKER" = true ]; then
    docker exec -i ${POSTGRES_CONTAINER} psql -U ${DB_USERNAME} -d postgres << EOF
CREATE DATABASE ${DB_NAME};
GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USERNAME};
EOF
else
    PGPASSWORD=${DB_PASSWORD} ${PSQL_CMD} -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USERNAME} -d postgres << EOF
CREATE DATABASE ${DB_NAME};
GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USERNAME};
EOF
fi
echo -e "${GREEN}✓ 완료${NC}"
echo ""

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}데이터베이스 초기화 완료!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}다음 단계:${NC}"
echo "  1. 애플리케이션 시작:"
echo -e "     ${YELLOW}./gradlew :dwp-auth-server:bootRun${NC}"
echo ""
echo "  2. 애플리케이션이 시작되면 Flyway가 자동으로 마이그레이션을 실행합니다"
echo ""
echo "  3. 로그인 테스트:"
echo "     - Username: admin"
echo "     - Password: admin1234!"
echo ""
