# Phase 6: 사이드 메뉴 복원 및 워크벤치 경로 정렬

## 1. Flyway 마이그레이션 (auth-server)

**파일:** `dwp-auth-server/src/main/resources/db/migration/V43__restore_side_menus_workbench_path_resource_sync.sql`

(기존 V41: RAG_DOCUMENT_STATUS, V42: sidebar/경로 정리 이어서 **V43** 적용.)

### 1.1 하는 일

| 단계 | 내용 |
|------|------|
| **1. 통합 워크벤치 확정** | `menu.autonomous-operations.workbench` → `menu_path = '/synapse/workbench'`, `menu_name` / `menu_name_ko` = '통합 워크벤치', `menu_name_en` = 'Unified Workbench' |
| **2. command-center 진입점 통일** | `menu.command-center` → `menu_path = '/synapse/workbench'` (기존 경로 하드코딩 제거, 단일 진입점) |
| **3. 사이드 메뉴 전면 복원** | anomalies, cases, actions, rag, policies, dictionary, guardrails, feedback, documents, open-items, entities, lineage, reconciliation, audit, analytics, governance, agent-config, integrations, admin, workbench, command-center → `is_visible = 'Y'` |
| **4. com_resources route 동기화** | `com_resources`(type='MENU') 의 `metadata_json.route` 를 `sys_menus.menu_path` 와 100% 일치하도록 UPDATE (JOIN on tenant_id, key = menu_key) |

### 1.2 SQL 요약

- **sys_menus**: workbench·command-center 경로/명칭 확정, 위 메뉴 키들 `is_visible = 'Y'`.
- **com_resources**: `UPDATE com_resources c SET metadata_json = '{"route":"' || m.menu_path || '"}' FROM sys_menus m WHERE c.tenant_id = m.tenant_id AND c.type = 'MENU' AND c.key = m.menu_key AND m.menu_path IS NOT NULL AND TRIM(m.menu_path) <> ''`.

---

## 2. Path 하드코딩 정리 (소스)

- **검색 결과**: Java 소스에서 `/command-center` 문자열은 없음. `command-center` 는 **감사 로그 리소스 키 fallback**으로만 사용됨.
- **변경**: `services/synapsex-service/.../audit/AuditWriter.java`  
  - `logDashboardViewed` 의 fallback `"command-center"` → `"workbench"` (2곳).  
  - 대시보드/관제 조회 감사 시 리소스 식별을 통합 진입점(workbench)에 맞춤.

---

## 3. Resource 매핑 (com_resources)

- V43 4단계에서 **모든 MENU 타입 리소스**에 대해 `sys_menus.menu_path` 가 있는 행만 대상으로 `metadata_json` 의 `route` 를 일괄 갱신.
- 이후 메뉴 트리/권한 쪽에서 사용하는 route 는 `sys_menus.menu_path` 와 동일하게 유지됨.

---

## 4. 요약

| 항목 | 처리 |
|------|------|
| 워크벤치 경로/명칭 | sys_menus workbench 행: path `/synapse/workbench`, name '통합 워크벤치' 확정 |
| command-center 경로 | sys_menus command-center: path `/synapse/workbench` 로 통일 |
| 사이드 메뉴 복원 | 위 목록 메뉴 전부 `is_visible = 'Y'` |
| com_resources route | sys_menus.menu_path 기준으로 100% 동기화 (V43 4단계) |
| 소스 하드코딩 | AuditWriter 감사 fallback `command-center` → `workbench` |
