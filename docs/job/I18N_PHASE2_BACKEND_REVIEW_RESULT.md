# i18n Phase2 백엔드 점검 결과

**기준 문서**: `docs/job/I18N_PHASE2_REVIEW_GO_NOGO_AND_REMAINING.txt`  
**점검일**: 2025-01-29

---

## 1. 완료로 판단되는 항목 ✅

| 항목 | 상태 | 근거 |
|------|------|------|
| sys_codes name_ko/name_en 시드 | ✅ | V28 마이그레이션 적용 |
| sys_menus menu_name_ko/menu_name_en 시드 | ✅ | V28 마이그레이션 적용 |
| 에러 메시지 다국어(P1) 미적용 | ✅ | 문서 권장대로 FE 번역으로 충족 |

---

## 2. 추가 확인/보완 항목

### B-1) SynapseX 도메인 코드 테이블 분리 이슈 ⚠️ **확인 필요**

**현황**:
- `CASE_TYPE`, `CASE_STATUS`, `SEVERITY`는 **auth sys_codes가 아닌 synapsex `dwp_aura.app_codes`**에 존재
- auth `sys_code_usages`에 `menu.autonomous-operations.cases` → CASE_STATUS/CASE_TYPE/SEVERITY 매핑 **없음**
- FE `useCodes(CASE_STATUS/CASE_TYPE)` 호출 시 **실제 호출 API**가 auth `/api/admin/codes/usage`인지 synapsex 전용 API인지 코드/네트워크로 확정 필요

**케이스 라벨 소스 후보**:
| 소스 | 위치 | i18n 적용 |
|------|------|-----------|
| auth sys_codes | auth DB | ✅ V28 시드 완료 |
| synapsex app_codes | dwp_aura | ❌ name_ko/name_en 없음 |
| FE i18n (t()) | 프론트 정적 | FE 담당 |

**권장 조치**:
1. **FE 네트워크 확인**: `/synapse/cases` 화면에서 ko→en 전환 시 `GET /api/admin/codes/usage?resourceKey=...` 호출 여부 확인
2. **resourceKey=menu.autonomous-operations.cases** 호출 시:
   - auth에 sys_code_usages 매핑 + sys_codes(CASE_STATUS, CASE_TYPE, SEVERITY) 추가 필요
3. **synapsex API 호출** 시:
   - `dwp_aura.app_codes`에 name_ko, name_en 컬럼 추가 + 시드 + synapsex codes API에서 locale-resolved 반환 필요

---

### B-2) Accept-Language가 menus/tree에 반영되는지 ✅ **코드 검증 완료**

**검증 결과**:
- `AcceptLanguageLocaleResolver`: `Accept-Language` 헤더 파싱 → `LocaleContextHolder` 세팅
- `MenuService.getMenuTree()`: `LocaleUtil.resolveLabel(menuNameKo, menuNameEn, menuName)` 사용
- `LocaleUtil.getLang()`: `LocaleContextHolder.getLocale()` 기반 'ko'|'en' 반환
- V28 시드로 `menu_name_ko`, `menu_name_en` 채워짐

**curl 검증 (실행 필요)**:
```bash
curl -X GET "$BASE_URL/api/auth/menus/tree" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"
```
→ `menuName`이 영어로 내려와야 함 (예: "Cases", "Documents", "Admin")

---

## 3. T1 백엔드 필수 회귀 테스트 체크리스트

| # | 항목 | 검증 방법 |
|---|------|-----------|
| 1 | codes API: Accept-Language=en 시 label/name 영어 | `GET /api/admin/codes?groupKey=RESOURCE_TYPE` + `Accept-Language: en` |
| 2 | menus/tree: Accept-Language=en 시 menuName 영어 | `GET /api/auth/menus/tree` + `Accept-Language: en` |
| 3 | 케이스 라벨 소스 확정 | FE 네트워크 탭에서 Cases 화면 코드 조회 API 확인 |

---

## 4. 결론

- **B-2 (menus/tree)**: 코드상 정상 동작. curl로 최종 확인 권장.
- **B-1 (케이스 라벨)**: FE가 실제로 호출하는 API 확인 후, auth 또는 synapsex 중 해당 소스에 i18n 보완 필요.
