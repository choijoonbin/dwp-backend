# PR-04A: 현재 스키마/데이터 점검 요약

## 1. com_resources 컬럼 구조 확인

### ✅ 존재하는 컬럼
- `resource_category` (VARCHAR(50), NOT NULL, DEFAULT 'MENU') - 리소스 대분류 (MENU/UI_COMPONENT)
- `resource_kind` (VARCHAR(50), NOT NULL, DEFAULT 'PAGE') - 리소스 세부 분류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION)
- `event_key` (VARCHAR(120), nullable) - 이벤트 추적 표준 키 (예: menu.admin.users:view)
- `event_actions` (JSONB, nullable) - 허용되는 action 목록 JSON 배열 (예: ["VIEW","CLICK","SUBMIT"])
- `tracking_enabled` (BOOLEAN, NOT NULL, DEFAULT true) - 이벤트 추적 활성화 여부
- `ui_scope` (VARCHAR(30), nullable) - 적용 범위 (GLOBAL/MENU/PAGE/COMPONENT)

### 마이그레이션 이력
- V16 (2026-01-19): com_resources 확장 (resource_category, resource_kind, event_key, event_actions, tracking_enabled, ui_scope 추가)
- V19 (2026-01-19): event_logs에 resource_kind 추가

## 2. 기존 Seed 데이터 상태

### Seed 데이터 위치
- V2__insert_seed_data.sql: 초기 seed 데이터 (13건)
- V5__add_admin_menu_resources.sql: Admin 메뉴 리소스 추가

### 데이터 구조
- 기존 seed 데이터는 `type`, `key`, `name` 필드만 포함
- V16 마이그레이션에서 기존 데이터에 대한 기본값 설정:
  - `resource_category`: type 기반으로 설정 (MENU → MENU, UI_COMPONENT → UI_COMPONENT)
  - `resource_kind`: 기본값 'PAGE'
  - `tracking_enabled`: 기본값 true
- 최신 컬럼 구조로 유지되지만, resource_kind는 기본값으로 설정됨 (운영 수준 CRUD 필요)

## 3. Menu Tree API 구조

### 이중 구조 사용
1. **com_resources**: 권한 관리 (RBAC)
   - `type = 'MENU'`인 리소스만 필터링
   - `com_role_permissions`와 연계하여 VIEW=ALLOW 권한 확인
   - `resourceKind`, `trackingEnabled` 정보 포함

2. **sys_menus**: 메뉴 메타 정보
   - `menu_key`가 `com_resources.key`와 매칭
   - `menu_name`, `menu_path`, `menu_icon`, `menu_group`, `parent_menu_key`, `sort_order` 등 UI 메타 정보
   - 권한이 있는 메뉴만 조회 후 sys_menus에서 메타 정보 조인

### MenuService.getMenuTree() 로직
1. 사용자 역할 ID 목록 조회
2. VIEW=ALLOW 권한의 MENU 리소스 키 수집 (com_resources)
3. sys_menus에서 메뉴 메타 조회
4. 부모 메뉴 자동 포함 (자식이 허용되면 부모도 포함)
5. parent_menu_key 기반 트리 구성
6. sort_order 기준 정렬

## 4. 현재 ResourceManagementService 상태

### ✅ 구현된 기능
- 리소스 트리 조회 (`getResourceTree`)
- 리소스 목록 조회 (`getResources`) - keyword, type, category, kind, parentId, enabled 필터 지원
- 리소스 생성 (`createResource`) - 중복 체크 포함
- 리소스 수정 (`updateResource`) - resourceKey 변경 가능 (운영 위험)
- 리소스 삭제 (`deleteResource`) - Soft delete (enabled=false)

### ❌ 부족한 부분 (PR-04 보강 필요)
1. **resourceCategory/resourceKind 코드 기반 검증** - CodeResolver 사용 필요
2. **eventActions 코드 기반 검증** - UI_ACTION 코드 검증 필요
3. **resourceKey 변경 금지** - PR-04D에서 금지
4. **하위 리소스 존재 시 삭제 충돌 (409)** - PR-04E에서 구현
5. **trackingEnabled 필터** - PR-04B에서 추가
6. **캐시 무효화** - PR-04F에서 구현
7. **created_at desc 정렬** - PR-04B에서 수정

## 5. 다음 단계

PR-04B ~ PR-04F에서 다음 작업 수행:
- 목록 조회 API 보강 (trackingEnabled 필터, created_at desc 정렬)
- 생성 API 보강 (resourceCategory/resourceKind/eventActions 코드 검증, 중복 409)
- 수정 API 보강 (resourceKey 변경 금지)
- 삭제 API 보강 (하위 리소스 존재 시 409)
- 감사로그 및 캐시 무효화 연계
