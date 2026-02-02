#!/bin/bash

# ========================================
# SynapseX CSV 대량 적재: sap_dummy → dwp_aura
# ========================================
# services/synapsex-service/src/main/resources/sap_dummy 폴더의
# bp_party, fi_doc_header, fi_doc_item, fi_open_item, sap_change_log CSV를
# dwp_aura 스키마 테이블에 적재합니다.
#
# 사용법:
#   ./scripts/run-synapsex-load-csv.sh
#   (기본: 프로젝트 내 sap_dummy 경로 사용)
#   ./scripts/run-synapsex-load-csv.sh /absolute/path/to/sap_dummy
#   ./scripts/run-synapsex-load-csv.sh --truncate   # 기존 데이터 삭제 후 적재 (dev 전용)
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_CSV_DIR="${PROJECT_ROOT}/services/synapsex-service/src/main/resources/sap_dummy"
LOAD_SQL="${SCRIPT_DIR}/seed/aura_load_csv.sql"

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-dwp_aura}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
PG_SUPERUSER=${PG_SUPERUSER:-dwp_user}

TRUNCATE_FIRST=false
if [ "${1:-}" = "--truncate" ]; then
  TRUNCATE_FIRST=true
  shift
fi
CSV_DIR="${1:-$DEFAULT_CSV_DIR}"
CSV_DIR="$(cd "$CSV_DIR" 2>/dev/null && pwd)" || { echo -e "${RED}오류: CSV 디렉터리가 없습니다: ${CSV_DIR}${NC}"; exit 1; }

for f in bp_party.csv fi_doc_header.csv fi_doc_item.csv fi_open_item.csv sap_change_log.csv; do
  if [ ! -f "${CSV_DIR}/${f}" ]; then
    echo -e "${RED}오류: 필수 CSV 없음: ${CSV_DIR}/${f}${NC}"
    exit 1
  fi
done

if [ ! -f "$LOAD_SQL" ]; then
  echo -e "${RED}오류: 로드 SQL 없음: ${LOAD_SQL}${NC}"
  exit 1
fi

echo -e "${YELLOW}SynapseX CSV 적재 (dwp_aura)${NC}"
echo "  CSV 디렉터리: $CSV_DIR"
echo "  DB: $DB_HOST:$DB_PORT / $DB_NAME"
echo ""

TMP_SQL=$(mktemp)
if [ "$TRUNCATE_FIRST" = true ]; then
  sed -e "s|__CSVDIR__|${CSV_DIR}|g" -e "s|__TRUNCATE__|TRUNCATE TABLE dwp_aura.fi_doc_item, dwp_aura.fi_open_item, dwp_aura.fi_doc_header, dwp_aura.sap_change_log, dwp_aura.bp_party RESTART IDENTITY CASCADE;|" "${LOAD_SQL}" > "${TMP_SQL}"
else
  sed -e "s|__CSVDIR__|${CSV_DIR}|g" -e "s|__TRUNCATE__|-- TRUNCATE (skip; use --truncate to run)|" "${LOAD_SQL}" > "${TMP_SQL}"
fi
trap "rm -f ${TMP_SQL}" EXIT

if command -v psql &> /dev/null; then
  [ "$PG_SUPERUSER" = "dwp_user" ] && PG_SUPERUSER=${USER:-$(whoami)}
  export PGPASSWORD=${DB_PASSWORD}
  psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d "${DB_NAME}" -f "${TMP_SQL}"
  unset PGPASSWORD
else
  echo -e "${YELLOW}psql 없음 → CSV를 컨테이너로 복사 후 실행${NC}"
  docker exec "${POSTGRES_CONTAINER}" mkdir -p /tmp/sap_dummy
  for f in bp_party.csv fi_doc_header.csv fi_doc_item.csv fi_open_item.csv sap_change_log.csv; do
    docker cp "${CSV_DIR}/${f}" "${POSTGRES_CONTAINER}:/tmp/sap_dummy/${f}"
  done
  if [ "$TRUNCATE_FIRST" = true ]; then
    sed -e "s|__CSVDIR__|/tmp/sap_dummy|g" -e "s|__TRUNCATE__|TRUNCATE TABLE dwp_aura.fi_doc_item, dwp_aura.fi_open_item, dwp_aura.fi_doc_header, dwp_aura.sap_change_log, dwp_aura.bp_party RESTART IDENTITY CASCADE;|" "${LOAD_SQL}" > "${TMP_SQL}"
  else
    sed -e "s|__CSVDIR__|/tmp/sap_dummy|g" -e "s|__TRUNCATE__|-- TRUNCATE (skip)|" "${LOAD_SQL}" > "${TMP_SQL}"
  fi
  docker cp "${TMP_SQL}" "${POSTGRES_CONTAINER}:/tmp/aura_load_csv.sql"
  docker exec -i "${POSTGRES_CONTAINER}" psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" -f /tmp/aura_load_csv.sql
  docker exec "${POSTGRES_CONTAINER}" rm -rf /tmp/sap_dummy /tmp/aura_load_csv.sql
fi

echo -e "${GREEN}✓ SynapseX CSV 적재 완료${NC}"
