# V40 Data Purge & Path Sync — RBAC 영향 보고서

## 개요

- **auth-server V40**: 통합 워크벤치 메뉴 경로·명칭 동기화 및 `com_resources` route 일치  
- **synapsex-service V40**: `dwp_aura` 스키마 전표/케이스/로그 데이터 정리(Purge) 및 시연용 시드(3종 전표)

## 1. 권한 시스템(RBAC) 영향

### 1.1 Path Sync 및 Resource 업데이트 (auth DB)

| 변경 항목 | 내용 | RBAC 영향 |
|----------|------|-----------|
| `sys_menus` | `menu_path` = `/synapse/workbench`, `menu_name` / `menu_name_ko` = `'통합 워크벤치'` | **없음** |
| `com_resources` | `name` = `'통합 워크벤치'`, `metadata_json` = `{"route":"/synapse/workbench"}` | **없음** |

- **역할/권한 매핑**: `com_role_permissions`는 **리소스 키**(`menu.autonomous-operations.workbench`)와 **권한 코드**(VIEW, USE, EDIT 등)로 결합되어 있음.  
- **V40에서 변경하는 것은 표시명(menu_name, menu_name_ko, com_resources.name)과 라우트 메타데이터(menu_path, metadata_json.route)뿐**이며, 리소스 키·역할·권한 코드는 그대로이므로 **RBAC 판단 로직 및 접근 제어 동작은 동일**함.
- 기존에 workbench 메뉴에 대한 권한을 가진 역할(ADMIN, SYNAPSEX_*, 등)은 **동일한 권한 유지**, 화면에서만 라벨과 경로가 ‘통합 워크벤치’ / `/synapse/workbench`로 통일됨.

### 1.2 Data Purge 및 Demo Seed (synapse DB, dwp_aura)

- Purge와 시드는 **dwp_aura 스키마의 트랜잭션/케이스/로그 테이블만 대상**이며, **auth DB 및 `com_roles` / `com_role_permissions` / `com_resources`에는 전혀 미치지 않음**.
- 따라서 **RBAC 설정·동작에는 영향 없음**.

## 2. 운영 유의사항

- **Purge**: `dwp_aura` 내 전표·케이스·실행·로그 등이 **영구 삭제**되므로, **개발/데모 환경** 또는 명시적 승인 하에만 실행할 것.
- **시드 데이터**: 시연용 전표 3종(중복·정책위반·지연미결)은 `fi_doc_header` / `fi_doc_item` / `sap_raw_events`에만 추가되며, 권한/역할 데이터는 건드리지 않음.

## 3. 요약

| 구분 | RBAC 영향 |
|------|-----------|
| auth V40 (메뉴 경로·리소스 route/명칭) | **없음** — 동일 리소스 키·역할·권한 유지 |
| synapse V40 (Purge + Seed) | **없음** — auth DB 및 RBAC 미관련 |

결론: **V40 적용으로 인한 권한 시스템(RBAC) 동작 변경은 없음.**
