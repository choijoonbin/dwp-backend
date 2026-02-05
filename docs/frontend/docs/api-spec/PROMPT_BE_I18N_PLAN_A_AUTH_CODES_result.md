# A안(Auth 중앙 코드) 구현 결과

**요청 문서**: `docs/job/PROMPT_BE_I18N_CHOOSE_PLAN_A_AUTH_CODES_SOURCE_OF_TRUTH.txt`  
**완료일**: 2025-01-29

---

## 1. 구현 요약

### P0: Auth 서버에서 코드/라벨 다국어 제공

| 항목 | 상태 | 내용 |
|------|------|------|
| sys_code_groups | ✅ | CASE_TYPE, CASE_STATUS, SEVERITY 추가 |
| sys_codes | ✅ | 각 그룹별 코드 + name_ko, name_en 시드 |
| sys_code_usages | ✅ | menu.autonomous-operations.cases → 3그룹 매핑 |
| CodeController | ✅ | menuKey 파라미터 alias 추가 (resourceKey와 동일 동작) |

---

## 2. API 스펙

### GET /api/admin/codes/usage

- **resourceKey** (기존): `menu.admin.users`, `menu.autonomous-operations.cases` 등
- **menuKey** (신규 alias): resourceKey와 동일, FE 호환용

```
GET /api/admin/codes/usage?resourceKey=menu.autonomous-operations.cases
GET /api/admin/codes/usage?menuKey=menu.autonomous-operations.cases
```

→ CASE_TYPE, CASE_STATUS, SEVERITY 그룹의 코드 목록 반환 (Accept-Language에 따라 name/label locale-resolved)

### GET /api/admin/codes?groupKey=CASE_TYPE

- Accept-Language=en 시 영어 name/label 반환

---

## 3. 시드 데이터 (V29)

### CASE_TYPE
DUPLICATE_INVOICE, ANOMALY_AMOUNT, MISSING_EVIDENCE, BANK_CHANGE, BANK_CHANGE_RISK, POLICY_VIOLATION, DATA_INTEGRITY, THRESHOLD_BREACH, ANOMALY, DEFAULT

### CASE_STATUS
OPEN, TRIAGED, IN_REVIEW, IN_PROGRESS, PENDING_APPROVAL, APPROVED, REJECTED, RESOLVED, CLOSED, ARCHIVED, DISMISSED

### SEVERITY
CRITICAL, HIGH, MEDIUM, LOW, INFO

---

## 4. 검증 curl

```bash
# 1) usage 확인 (3그룹 반환)
curl -X GET "$BASE_URL/api/admin/codes/usage?menuKey=menu.autonomous-operations.cases" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"

# 2) codes 영어 라벨 확인
curl -X GET "$BASE_URL/api/admin/codes?groupKey=CASE_TYPE" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"
```

---

## 5. FE 연동

- FE는 `GET /api/admin/codes/usage?menuKey=menu.autonomous-operations.cases` 또는 `resourceKey=` 사용
- 각 groupKey별로 `GET /api/admin/codes?groupKey=CASE_TYPE` 등 호출하여 코드 목록 조회
- Accept-Language 헤더 전파 시 locale-resolved name/label 수신
