# Phase 4: Menu Migration (V39) & Policy Access — Pre-Check 및 계획

## Pre-Check (MUST ANSWER)

### 1. `menu.governance-config`의 정확한 `sys_menu_id`를 식별했습니까?

**답변:**  
마이그레이션에서는 **`sys_menu_id`를 사용하지 않습니다.**  
`sys_menus` 테이블은 **`parent_menu_key`**(V1, V16)로 부모를 참조합니다.  
따라서 V39에서는 `menu.knowledge-policy.dictionary` 행의 **`parent_menu_key`** 만 `'menu.governance-config'`로 변경하면 됩니다.  
`menu.governance-config`의 `sys_menu_id`를 조회해 넣을 필요 없음.

---

### 2. 정책 수정 권한 제한 시 기존에 작성된 `AuthServerPermissionClient`를 활용할 계획인가요?

**답변:** **예.**  
- `AuthServerPermissionClient.check(tenantId, userId, resourceKey, permissionCode)` 를 사용합니다.
- 정책 API용 **resourceKey**: `menu.knowledge-policy.policies` (정책 프로파일 메뉴).
- **GET** → `permissionCode = "VIEW"` (OPERATOR 포함 조회 허용).
- **PATCH / POST** → `permissionCode = "EDIT"` (SYNAPSEX_ADMIN, ADMIN만 허용).
- `SynapseAdminGuardFilter`와 동일한 패턴으로, `/synapse/policies/**` 전용 필터 또는 컨트롤러 진입 전 검사에서 Feign 호출로 권한 검증.

---

## Task 1: Flyway V39

- **파일:** `dwp-auth-server/src/main/resources/db/migration/V39__migrate_dictionary_menu_to_governance.sql`
- **내용:**
  1. `menu.knowledge-policy.dictionary` 의 `parent_menu_key` 를 `'menu.governance-config'` 로 UPDATE.
  2. `sort_order` 재조정: 거버넌스·설정 하위가 현재 51~54(governance, agent-config, integrations, admin)이므로, 용어·코드 사전을 **55**로 두어 설정 메뉴 하단에 배치.
  3. `depth` = 2 유지, `menu_path` 등은 기존 값 유지(`/dictionary`).

---

## Task 2: Policy Access Control

### 현재 상태

- **PolicyController** (`/synapse/policies`):  
  - **GET** 만 존재 (listProfiles, getProfileDetail, getEffectivePolicy).  
  - **PATCH/POST** 메서드 없음.
- **권한 적용:**  
  - `@PreAuthorize` 없음.  
  - `/synapse/policies/**` 전용 필터/인터셉터 없음.

### 보강 계획

1. **AuthServerPermissionClient 활용**
   - resourceKey: `menu.knowledge-policy.policies`
   - GET 요청: `check(tenantId, userId, resourceKey, "VIEW")` → 실패 시 403.
   - PATCH/POST 요청: `check(tenantId, userId, resourceKey, "EDIT")` → 실패 시 403.
   - V23 기준: SYNAPSEX_OPERATOR는 해당 메뉴에 VIEW만 부여됨 → 조회만 가능. SYNAPSEX_ADMIN/ADMIN은 VIEW, USE, EDIT → 수정 가능.

2. **구현 (적용됨)**
   - **SynapsePolicyGuardFilter** 추가: `/synapse/policies` 경로에 대해 `AuthServerPermissionClient.check` 호출.
   - GET → VIEW, PATCH/POST/PUT → EDIT. `@Order(-99)`, `@Profile("!test")` 적용.

3. **추가 시 고려**
   - 이후 정책 프로파일 생성/수정용 **PATCH/POST**가 추가되면, 동일 필터가 자동으로 EDIT 검사 (추가 코드 없음).

---

## 요약

| 항목 | 내용 |
|------|------|
| menu.governance-config 식별 | sys_menu_id 불필요; parent_menu_key 로 'menu.governance-config' 지정 |
| AuthServerPermissionClient | 사용. resourceKey=menu.knowledge-policy.policies, GET=VIEW, PATCH/POST=EDIT |
| V39 | parent_menu_key 업데이트 + sort_order=55 |
| Policy 보강 | GET에 VIEW 검사, (추후) PATCH/POST에 EDIT 검사 적용 |
