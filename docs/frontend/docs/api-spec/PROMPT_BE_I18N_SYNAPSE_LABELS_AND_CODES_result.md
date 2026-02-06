# [BE 완료] Synapse 화면 라벨/코드 다국어(ko/en) 동기화

> **원본**: docs/job/PROMPT_BE_I18N_SYNAPSE_LABELS_AND_CODES.md  
> **마이그레이션**: V30 (synapse labels/codes), V31 (null 복구), V32 (Documents/Open Items codes)

---

## 1. 완료 항목

| 구분 | 항목 | 완료 시 |
|------|------|---------|
| **menus/tree** | §2.2 전체 menuKey에 menuName_ko, menuName_en 저장 | `Accept-Language: ko` → 한글, `en` → 영문 menuName 반환 |
| **sys_codes** | ACTION_TYPE, CASE_STATUS, SEVERITY (필수) | `GET /api/admin/codes?groupKey=XXX` 응답 시 Accept-Language 기반 name 반환 |
| **sys_codes** | ENTITY_TYPE, COUNTRY (선택) | Entities 화면 필터 라벨 BE 제공 완료. FE useCodes로 전환 가능 |
| **동작 확인** | 언어 전환 후 사이드바·코드 라벨 변경 | FE에서 언어 토글 시 Accept-Language 헤더 변경 → BE 즉시 반영 |

---

## 2. FE i18n 완료 화면 (2025.02 업데이트)

| 화면 | menuKey | BE 지원 (sys_menus + sys_codes) |
|------|---------|--------------------------------|
| **전표 조회 (Documents)** | menu.master-data-history.documents | menuName_ko/en, INTEGRITY_STATUS (PASS/WARN/FAIL) |
| **미결제 항목 (Open Items)** | menu.master-data-history.open-items | menuName_ko/en, OPEN_ITEM_TYPE (AR/AP), OPEN_ITEM_STATUS (OPEN/PARTIALLY_CLEARED/CLEARED) |
| **거래처 허브 (Entities)** | menu.master-data-history.entities | menuName_ko/en, ENTITY_TYPE (VENDOR/CUSTOMER), COUNTRY (KOR/USA/JPN/CHN) |

---

## 3. API 호출 형식 (확인)

- **코드 조회**: `GET /api/admin/codes?groupKey=CASE_TYPE` (쿼리 파라미터, path 아님)
- **메뉴 트리**: `GET /api/auth/menus/tree`
- **헤더**: `Accept-Language: ko` 또는 `Accept-Language: en`

---

## 4. 추가된 코드 그룹

| groupKey | 용도 | codes |
|----------|------|-------|
| **ACTION_TYPE** | Actions, Archive 화면 | POST_REVERSAL, BLOCK_PAYMENT, FLAG_REVIEW, CLEAR_ITEM, UPDATE_MASTER |
| **ENTITY_TYPE** | Entities 화면 필터 | VENDOR, CUSTOMER |
| **COUNTRY** | Entities 화면 필터 | KOR, USA, JPN, CHN |
| **INTEGRITY_STATUS** | Documents 화면 필터 | PASS, WARN, FAIL |
| **OPEN_ITEM_TYPE** | Open Items 화면 필터 | AR, AP |
| **OPEN_ITEM_STATUS** | Open Items 화면 필터 | OPEN, PARTIALLY_CLEARED, CLEARED |

---

## 5. FE 전환 가이드

- **Actions, Archive**: `useCodes('ACTION_TYPE')`
- **Documents**: `useCodes('INTEGRITY_STATUS')` — 무결성 필터/배지 (PASS/WARN/FAIL)
- **Open Items**: `useCodes('OPEN_ITEM_TYPE')`, `useCodes('OPEN_ITEM_STATUS')` — 유형·상태 필터/칩
- **Entities**: `useCodes('ENTITY_TYPE')`, `useCodes('COUNTRY')`
- **메뉴명**: `GET /api/auth/menus/tree`의 `menuName` 필드 사용 (하드코딩 fallback 제거)
