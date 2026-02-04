#!/bin/bash

# ========================================
# fi_dummy_additions 더미 데이터 적재
# ========================================
# bp_party_add, fi_doc_header_add, fi_doc_item_add, fi_open_item_add CSV를
# dwp_aura 스키마 4개 테이블에 추가 삽입합니다.
#
# 사용법:
#   ./scripts/run-fi-dummy-additions.sh /path/to/fi_dummy_additions
#   예: ./scripts/run-fi-dummy-additions.sh /Users/joonbinchoi/Downloads/fi_dummy_additions
#
# 전제:
#   1. docker-compose up -d postgres (또는 로컬 PostgreSQL)
#   2. Flyway 마이그레이션 완료
#   3. scripts/seed/fi_dummy_additions_conflict_check.sql 실행 후 충돌 0 확인
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFLICT_CHECK_SQL="${SCRIPT_DIR}/seed/fi_dummy_additions_conflict_check.sql"
LOAD_SQL="${SCRIPT_DIR}/seed/fi_dummy_additions_load.sql"

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-dwp_aura}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
PG_SUPERUSER=${PG_SUPERUSER:-dwp_user}

SKIP_CHECK=false
if [ "${1:-}" = "-y" ] || [ "${1:-}" = "--yes" ]; then
  SKIP_CHECK=true
  shift
fi
CSV_DIR="${1:?Usage: $0 [-y] /path/to/fi_dummy_additions}"
CSV_DIR="$(cd "$CSV_DIR" 2>/dev/null && pwd)" || { echo -e "${RED}오류: 디렉터리가 없습니다: ${CSV_DIR}${NC}"; exit 1; }

for f in bp_party_add.csv fi_doc_header_add.csv fi_doc_item_add.csv fi_open_item_add.csv; do
  if [ ! -f "${CSV_DIR}/${f}" ]; then
    echo -e "${RED}오류: 필수 CSV 없음: ${CSV_DIR}/${f}${NC}"
    exit 1
  fi
done

echo -e "${YELLOW}fi_dummy_additions 적재 (dwp_aura)${NC}"
echo "  CSV 디렉터리: $CSV_DIR"
echo "  DB: $DB_HOST:$DB_PORT / $DB_NAME"
echo ""

# 1) 충돌 점검 (선택)
if [ "$SKIP_CHECK" = false ]; then
  echo -e "${YELLOW}==[1/2] 충돌 점검 실행 ==${NC}"
  if command -v psql &> /dev/null; then
    export PGPASSWORD=${DB_PASSWORD}
    psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d "${DB_NAME}" -f "${CONFLICT_CHECK_SQL}" || true
    unset PGPASSWORD
  else
    docker exec -i "${POSTGRES_CONTAINER}" psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -f - < "${CONFLICT_CHECK_SQL}" || true
  fi
  echo ""
  echo -e "${YELLOW}위 점검 결과 확인 후 계속하려면 Enter, 중단하려면 Ctrl+C${NC}"
  read -r
fi

# 2) CSV 전처리 (Python False/True -> PostgreSQL false/true)
echo -e "${YELLOW}== CSV 적재 ==${NC}"
WORK_DIR=$(mktemp -d)
trap "rm -rf ${WORK_DIR}" EXIT
for f in bp_party_add.csv fi_doc_header_add.csv fi_doc_item_add.csv fi_open_item_add.csv; do
  sed -e 's/,False,/,false,/g' -e 's/,True,/,true,/g' "${CSV_DIR}/${f}" > "${WORK_DIR}/${f}"
done

# 3) 적재 실행
TMP_SQL=$(mktemp)
sed "s|__CSVDIR__|${WORK_DIR}|g" "${LOAD_SQL}" > "${TMP_SQL}"
trap "rm -rf ${WORK_DIR} ${TMP_SQL}" EXIT

if command -v psql &> /dev/null; then
  export PGPASSWORD=${DB_PASSWORD}
  psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d "${DB_NAME}" -f "${TMP_SQL}"
  unset PGPASSWORD
else
  echo -e "${YELLOW}psql 없음 → Docker 컨테이너로 복사 후 실행${NC}"
  docker exec "${POSTGRES_CONTAINER}" mkdir -p /tmp/fi_dummy_additions
  for f in bp_party_add.csv fi_doc_header_add.csv fi_doc_item_add.csv fi_open_item_add.csv; do
    docker cp "${WORK_DIR}/${f}" "${POSTGRES_CONTAINER}:/tmp/fi_dummy_additions/${f}"
  done
  sed "s|__CSVDIR__|/tmp/fi_dummy_additions|g" "${LOAD_SQL}" > "${TMP_SQL}"
  docker cp "${TMP_SQL}" "${POSTGRES_CONTAINER}:/tmp/fi_dummy_additions_load.sql"
  docker exec -i "${POSTGRES_CONTAINER}" psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -f /tmp/fi_dummy_additions_load.sql
  docker exec "${POSTGRES_CONTAINER}" rm -rf /tmp/fi_dummy_additions /tmp/fi_dummy_additions_load.sql
fi

echo -e "${GREEN}✓ fi_dummy_additions 적재 완료${NC}"
