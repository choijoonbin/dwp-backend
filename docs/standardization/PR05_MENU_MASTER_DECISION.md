# PR-05A: 메뉴 마스터 데이터 기준 확정

## 현재 구조 분석

### 1. sys_menus 테이블
- **역할**: 메뉴 트리 메타 정보 저장
- **컬럼**: menu_key, menu_name, menu_path, menu_icon, menu_group, parent_menu_key, sort_order, depth, is_visible, is_enabled
- **용도**: 메뉴 트리 구조, 정렬, 라우트, 아이콘, 노출 제어
- **특징**: tenant_id + menu_key 유니크 제약

### 2. com_resources 테이블
- **역할**: 권한 관리 및 이벤트 추적 리소스
- **컬럼**: resource_id, tenant_id, type, key, name, resource_category, resource_kind, event_key, event_actions, tracking_enabled
- **용도**: RBAC 권한 체크, 이벤트 추적
- **특징**: menu.* 키를 가진 리소스가 메뉴 권한을 나타냄

### 3. 현재 동작 방식
1. **MenuService.getMenuTree()**:
   - com_resources에서 MENU 타입 리소스의 VIEW=ALLOW 권한 확인
   - sys_menus에서 메뉴 메타 정보 조회
   - 권한이 있는 메뉴만 트리 구성

2. **권한 체크**:
   - com_role_permissions에서 resource_id 기반 권한 확인
   - com_resources의 key가 menu.* 형식이면 메뉴로 취급

## 결정: Source of Truth

### ✅ sys_menus를 메뉴 전용으로 사용
**이유**:
1. **명확한 책임 분리**: sys_menus는 메뉴 트리 구조/정렬/노출 전담, com_resources는 권한/추적 전담
2. **운영 편의성**: 메뉴 등록/수정/삭제가 sys_menus만 관리하면 단순함
3. **기존 구조 유지**: 현재 MenuService가 이미 sys_menus 기반으로 동작
4. **최소 변경**: 기존 코드 변경 최소화

### com_resources는 권한/추적 리소스로 활용
**역할**:
- Menu 생성 시 자동으로 com_resources에 대응 MENU 리소스 생성 (PR-05C)
- menuKey = resourceKey로 동기화
- 권한 체크는 com_resources 기반 유지

## 동기화 정책 (PR-05C)

### Menu 생성 시
1. sys_menus에 메뉴 생성
2. com_resources에 대응 MENU 리소스 자동 생성 (upsert)
   - resourceKey = menuKey
   - type = "MENU"
   - resourceCategory = "MENU"
   - resourceKind = "PAGE" (또는 parent가 없으면 "MENU_GROUP")

### Menu 수정 시
- sys_menus만 수정 (com_resources는 변경 없음)
- 단, menuKey 변경 시 com_resources의 key도 동기화 (운영 위험, 별도 API 권장)

### Menu 삭제 시
- sys_menus에서 삭제 (soft delete: is_enabled='N')
- com_resources는 유지 (권한 이력 보존)

## 장점

1. **단일 책임 원칙**: sys_menus는 메뉴 구조, com_resources는 권한
2. **운영 편의성**: Admin에서 메뉴만 관리하면 됨
3. **권한 연계**: Menu 생성 시 자동으로 권한 리소스 생성
4. **하위 호환성**: 기존 MenuService 로직 유지

## 다음 단계

PR-05B에서 Admin Menus CRUD API 구현
PR-05C에서 Menu 생성 시 com_resources 자동 생성 로직 구현
