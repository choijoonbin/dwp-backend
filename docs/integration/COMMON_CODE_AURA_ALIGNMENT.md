# 공통 코드 Aura 정렬 (대문자 계약)

Aura-Platform과 문자열 100% 일치를 위해 **app_codes** 코드값은 다음 규칙을 따릅니다.

## 1. 대문자 계약

- **DOC_TYPE**: `REGULATION`, `POLICY`, `GUIDE` (소문자/혼합 사용 금지)
- **RISK_LEVEL**: `HIGH`, `MEDIUM`, `LOW`, `NORMAL`
- **CASE_STATUS**: `NEW`, `IN_PROGRESS`, `RESOLVED`, `IGNORED` (및 기존 OPEN, IN_REVIEW 등)

## 2. 등록 위치

- **Synapse (dwp_aura)**: `app_code_groups`, `app_codes` — V44 시드 및 이후 마이그레이션에서 위 값으로 등록/갱신.
- 비교 시 `code` 컬럼은 **대문자로 비교**하거나, Aura에서 내려오는 문자열을 `UPPER()` 처리 후 매칭 권장.

## 3. 검증

- 신규 코드 추가 시 Aura 문서/API에서 사용하는 문자열과 동일한지 확인.
- 소문자/대문자 불일치 시 FE/에이전트에서 라벨 조회 실패 가능 — 코드값은 항상 대문자 스네이크 유지.
