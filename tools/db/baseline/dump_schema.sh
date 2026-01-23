#!/bin/bash
# ========================================
# DWP DB Schema Dump Script
# ========================================
# 용도: PostgreSQL 스키마를 Flyway baseline으로 추출
# 사용법: ./dump_schema.sh <db_name> <service_name>
# 예시: ./dump_schema.sh dwp_main main-service
# ========================================

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 인자 확인
if [ $# -ne 2 ]; then
  echo -e "${RED}Error: Invalid arguments${NC}"
  echo "Usage: $0 <db_name> <service_name>"
  echo ""
  echo "Examples:"
  echo "  $0 dwp_auth auth-server"
  echo "  $0 dwp_main main-service"
  echo "  $0 dwp_mail mail-service"
  exit 1
fi

DB_NAME=$1
SERVICE_NAME=$2
DB_USER=${DB_USERNAME:-dwp_user}
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}

# 출력 파일 경로
OUTPUT_DIR="../../../dwp-${SERVICE_NAME}/src/main/resources/db/migration"
OUTPUT_FILE="${OUTPUT_DIR}/V1__baseline.sql"

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}DWP DB Schema Dump${NC}"
echo -e "${YELLOW}========================================${NC}"
echo "DB Name: ${DB_NAME}"
echo "Service: ${SERVICE_NAME}"
echo "DB User: ${DB_USER}"
echo "DB Host: ${DB_HOST}:${DB_PORT}"
echo "Output: ${OUTPUT_FILE}"
echo ""

# 디렉토리 생성
mkdir -p "${OUTPUT_DIR}"

# pg_dump 실행
echo -e "${YELLOW}Extracting schema...${NC}"
pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
  --schema-only \
  --no-owner \
  --no-privileges \
  --no-tablespaces \
  --no-security-labels \
  --no-comments \
  > "${OUTPUT_FILE}"

# 헤더 추가
TEMP_FILE=$(mktemp)
cat > "${TEMP_FILE}" << EOF
-- ========================================
-- DWP ${SERVICE_NAME} Baseline Schema
-- 생성일: $(date +%Y-%m-%d)
-- 목적: 초기 스키마 정의 (Flyway baseline)
-- DB: ${DB_NAME}
-- ========================================
-- ⚠️ 주의: 이 파일은 자동 생성되었습니다.
-- 수동 편집 시 Flyway 검증이 실패할 수 있습니다.
-- ========================================

EOF

cat "${OUTPUT_FILE}" >> "${TEMP_FILE}"
mv "${TEMP_FILE}" "${OUTPUT_FILE}"

# 결과 확인
if [ -f "${OUTPUT_FILE}" ]; then
  LINE_COUNT=$(wc -l < "${OUTPUT_FILE}")
  echo ""
  echo -e "${GREEN}✅ Baseline generated successfully!${NC}"
  echo "File: ${OUTPUT_FILE}"
  echo "Lines: ${LINE_COUNT}"
  echo ""
  echo -e "${YELLOW}Next steps:${NC}"
  echo "1. Review the generated SQL file"
  echo "2. Ensure ddl-auto is set to 'validate' in application.yml"
  echo "3. Test with: ./gradlew :dwp-${SERVICE_NAME}:bootRun"
else
  echo -e "${RED}❌ Failed to generate baseline${NC}"
  exit 1
fi
