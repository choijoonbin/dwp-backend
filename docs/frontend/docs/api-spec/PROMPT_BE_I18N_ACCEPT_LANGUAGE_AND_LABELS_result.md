# 다국어(ko/en) 지원 구현 결과

**요청 문서**: `docs/job/PROMPT_BE_I18N_ACCEPT_LANGUAGE_AND_LABELS.txt`  
**완료일**: 2025-01-29

---

## 1. 구현 요약

### 1.1 Accept-Language 처리

- **dwp-core**: `AcceptLanguageLocaleResolver` 등록
  - `Accept-Language` 헤더 파싱
  - 허용: `ko`, `en` (그 외: `ko` fallback)
  - `LocaleContextHolder`에 세팅 → `LocaleUtil.getLang()`에서 사용

### 1.2 LocaleUtil

- `LocaleUtil.getLang()`: `'ko'` | `'en'` 반환
- `LocaleUtil.resolveLabel(nameKo, nameEn, fallback)`: locale 기반 라벨 선택

### 1.3 DB 마이그레이션 (V27)

| 테이블 | 추가 컬럼 |
|--------|-----------|
| `sys_codes` | `name_ko`, `name_en` |
| `sys_menus` | `menu_name_ko`, `menu_name_en` |

### 1.4 API 응답 규칙

| API | 필드 | 동작 |
|-----|------|------|
| `GET /api/admin/codes` | `name`, `label` | locale-resolved (name_ko/name_en/name fallback) |
| `GET /api/admin/codes/usage` | `name` | locale-resolved |
| `GET /api/auth/menus/tree` | `menuName` | locale-resolved (menu_name_ko/menu_name_en/menu_name fallback) |

---

## 2. 검증 curl

```bash
# 코드 조회 (영어)
curl -X GET "$BASE_URL/api/admin/codes?groupKey=CASE_TYPE" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"

# 메뉴 트리 (영어)
curl -X GET "$BASE_URL/api/auth/menus/tree" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: $TENANT" \
  -H "Accept-Language: en"
```

- `Accept-Language` 미지정/잘못된 값 → `ko` fallback
- `name_ko`, `name_en` 컬럼이 비어 있으면 기존 `name` 사용

---

## 3. FE 연동 사항

- **CORS**: `Accept-Language` 헤더 허용됨
- **Feign 전파**: `Accept-Language`가 downstream 서비스로 전파됨
- **언어 전환**: FE에서 `Accept-Language: en` 또는 `ko`로 변경 시 API 응답 라벨이 즉시 변경됨

---

## 4. 2차 미구현 (문서 기준)

- 번역 관리 UI (어드민에서 번역 편집)
- 문장 단위 다국어 DB(translation_key/value) 전면 적용
