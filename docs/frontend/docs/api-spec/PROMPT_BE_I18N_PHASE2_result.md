# i18n 2차 보완 — 다국어 시드 데이터 결과

**요청 문서**: `docs/job/PROMPT_BE_I18N_PHASE2_SEED_TRANSLATIONS_AND_OPTIONAL_ERRORS.txt`  
**완료일**: 2025-01-29

---

## 1. 구현 요약

### P0: 다국어 시드 데이터 입력 ✅

**V28__i18n_seed_translations.sql** (auth-server Flyway)

| 대상 | 컬럼 | 시드 범위 |
|------|------|-----------|
| sys_codes | name_ko, name_en | RESOURCE_TYPE, SUBJECT_TYPE, ROLE_CODE, IDP_PROVIDER_TYPE, PERMISSION_CODE, USER_STATUS, EFFECT_TYPE, RESOURCE_STATUS, LOGIN_TYPE, MONITORING_CONFIG_KEY(주요) |
| sys_menus | menu_name_ko, menu_name_en | SynapseX 대메뉴·하위, APPS, Admin 전체 |

### P1: 에러 메시지 다국어

- **미적용** (문서 권장: 내부 UI면 FE 번역으로 충분, B는 2차 이후)

---

## 2. 시드 대상 상세

### sys_codes (auth-server)

- RESOURCE_TYPE: MENU, UI_COMPONENT, PAGE, API
- SUBJECT_TYPE: USER, DEPARTMENT
- ROLE_CODE: ADMIN, USER, SYNAPSEX_ADMIN, SYNAPSEX_OPERATOR, SYNAPSEX_VIEWER
- IDP_PROVIDER_TYPE: LOCAL, SAML, OIDC
- PERMISSION_CODE: VIEW, USE, EDIT, APPROVE, EXECUTE
- USER_STATUS: ACTIVE, INACTIVE, LOCKED
- EFFECT_TYPE: ALLOW, DENY
- RESOURCE_STATUS: ENABLED, DISABLED
- LOGIN_TYPE: LOCAL, SSO
- MONITORING_CONFIG_KEY: MIN_REQ_PER_MINUTE, ERROR_RATE_THRESHOLD

### sys_menus (auth-server)

- Command Center, Autonomous Operations, Cases, Documents, Open Items, Actions, Audit, Admin Codes 등 전체 메뉴 트리

---

## 3. 검증

```bash
# 코드 (영어)
curl -X GET "$BASE_URL/api/admin/codes?groupKey=RESOURCE_TYPE" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"

# 메뉴 (영어)
curl -X GET "$BASE_URL/api/auth/menus/tree" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"
```

- null fallback이 더 이상 발생하지 않음 (시드 완료)
- Accept-Language=en 시 name/label이 영어로 반환됨

---

## 4. 참고

- **synapsex app_codes** (dwp_aura): CASE_TYPE, CASE_STATUS, SEVERITY 등은 synapsex DB에 있으며, auth sys_codes와 별도. 필요 시 synapsex 쪽 i18n 시드는 별도 마이그레이션으로 진행 가능.
