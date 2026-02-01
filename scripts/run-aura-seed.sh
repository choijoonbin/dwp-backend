#!/bin/bash

# ========================================
# Aura 시드 실행: config/policy + agent_case (CSV)
# ========================================
# agent_case_seed.csv 를 dwp_aura.agent_case 로 변환 적재합니다.
# 기본 CSV 경로: services/aura-service/src/main/resources/sap_dummy/agent_case_seed.csv
#
# 사용법:
#   ./scripts/run-aura-seed.sh
#   (기본: sap_dummy/agent_case_seed.csv)
#   ./scripts/run-aura-seed.sh /path/to/sap_dummy
#   ./scripts/run-aura-seed.sh /path/to/agent_case_seed.csv
# ========================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_SAP_DUMMY="${PROJECT_ROOT}/services/aura-service/src/main/resources/sap_dummy"
SEED_SQL="${SCRIPT_DIR}/seed/aura_seed_case_and_policy.sql"
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-5432}
DB_NAME=${DB_NAME:-dwp_aura}
DB_USERNAME=${DB_USERNAME:-dwp_user}
DB_PASSWORD=${DB_PASSWORD:-dwp_password}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-dwp-postgres}
PG_SUPERUSER=${PG_SUPERUSER:-dwp_user}

# 인자: 없으면 sap_dummy/agent_case_seed.csv, 있으면 디렉터리 또는 CSV 전체 경로
if [ -z "$1" ]; then
  CSV_PATH="${DEFAULT_SAP_DUMMY}/agent_case_seed.csv"
else
  if [ -f "$1" ]; then
    CSV_PATH="$1"
  else
    CSV_PATH="$1/agent_case_seed.csv"
  fi
fi

if [ ! -f "$CSV_PATH" ]; then
  echo -e "${RED}오류: CSV 파일이 없습니다: ${CSV_PATH}${NC}"
  exit 1
fi

if [ ! -f "$SEED_SQL" ]; then
  echo -e "${RED}오류: 시드 SQL이 없습니다: ${SEED_SQL}${NC}"
  exit 1
fi

echo -e "${YELLOW}Aura 시드 실행 (dwp_aura)${NC}"
echo "  CSV: $CSV_PATH"
echo "  DB:  $DB_HOST:$DB_PORT / $DB_NAME"
echo ""

# CSV 경로를 SQL에 치환 (__CSVPATH__)
TMP_SQL=$(mktemp)
sed "s|__CSVPATH__|${CSV_PATH}|g" "${SEED_SQL}" > "${TMP_SQL}"
trap "rm -f ${TMP_SQL}" EXIT

if command -v psql &> /dev/null; then
  # 호스트 psql로 Docker Postgres 접속 → \copy가 호스트 경로 사용
  [ "$PG_SUPERUSER" = "dwp_user" ] && PG_SUPERUSER=${USER:-$(whoami)}
  export PGPASSWORD=${DB_PASSWORD}
  psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d "${DB_NAME}" \
    -v tenant_id=1 \
    -f "${TMP_SQL}"
  unset PGPASSWORD
else
  # Docker만 있는 경우: CSV를 컨테이너로 복사 후 실행 (경로는 이미 TMP_SQL에 /tmp/agent_case_seed.csv 로 들어감)
  echo -e "${YELLOW}psql 없음 → CSV를 컨테이너로 복사 후 실행${NC}"
  docker cp "$CSV_PATH" "${POSTGRES_CONTAINER}:/tmp/agent_case_seed.csv"
  sed "s|__CSVPATH__|/tmp/agent_case_seed.csv|g" "${SEED_SQL}" > "${TMP_SQL}"
  docker cp "${TMP_SQL}" "${POSTGRES_CONTAINER}:/tmp/aura_seed.sql"
  docker exec -i "${POSTGRES_CONTAINER}" psql -U "${PG_SUPERUSER}" -d "${DB_NAME}" \
    -v tenant_id=1 \
    -f /tmp/aura_seed.sql
  docker exec "${POSTGRES_CONTAINER}" rm -f /tmp/agent_case_seed.csv /tmp/aura_seed.sql
fi

echo -e "${GREEN}✓ Aura 시드 완료${NC}"
