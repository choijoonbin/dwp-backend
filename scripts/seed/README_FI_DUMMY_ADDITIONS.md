# fi_dummy_additions 더미 데이터 적재

`bp_party_add`, `fi_doc_header_add`, `fi_doc_item_add`, `fi_open_item_add` CSV를 **dwp_aura** 스키마 4개 테이블에 추가 삽입합니다.

> **참고**: 테이블은 `dwp_aura` 스키마에 있으며, auth 스키마가 아닙니다.

## 사전 요구사항

- Docker Postgres 실행 (`docker-compose up -d postgres`)
- Flyway 마이그레이션 완료

## 키 충돌 방지

CSV의 키 값은 충돌을 피하기 위해 높은 숫자로 설정되어 있습니다.

| 테이블 | 키 | 추가 데이터 범위 |
|--------|-----|------------------|
| bp_party | (tenant_id, party_type, party_code) | V101500~V101549, C200800~C200849 |
| fi_doc_header | (tenant_id, bukrs, belnr, gjahr) | belnr 1000200000~ |
| fi_doc_item | (tenant_id, bukrs, belnr, gjahr, buzei) | fi_doc_header와 동일 belnr |
| fi_open_item | (tenant_id, bukrs, belnr, gjahr, buzei) | fi_doc_header와 동일 belnr |

## 사용법

### 1) 충돌 점검 (선택)

```bash
docker exec -i dwp-postgres psql -U dwp_user -d dwp_aura -f - < scripts/seed/fi_dummy_additions_conflict_check.sql
```

### 2) 적재 실행

```bash
./scripts/run-fi-dummy-additions.sh /path/to/fi_dummy_additions
# 예: ./scripts/run-fi-dummy-additions.sh /Users/joonbinchoi/Downloads/fi_dummy_additions
```

- `-y` 또는 `--yes`: 충돌 점검 및 확인 프롬프트 생략
  ```bash
  ./scripts/run-fi-dummy-additions.sh -y /path/to/fi_dummy_additions
  ```

## 동작

- **ON CONFLICT DO NOTHING**: 기존 데이터와 키가 겹치면 해당 행은 건너뜀
- **reversal_belnr**: VARCHAR(10) 초과 시 앞 10자리만 저장
- **Python False/True**: 자동으로 PostgreSQL `false`/`true`로 변환

## 파일

| 파일 | 설명 |
|------|------|
| `fi_dummy_additions_conflict_check.sql` | 충돌 점검 쿼리 |
| `fi_dummy_additions_load.sql` | 4개 테이블 적재 SQL |
| `run-fi-dummy-additions.sh` | 실행 스크립트 |
