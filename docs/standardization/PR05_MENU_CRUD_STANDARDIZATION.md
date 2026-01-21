# PR-05: Menu 관리 운영 CRUD 완성

## 목표
Menu 관리가 되면 회사별 커스텀 메뉴 확장성이 열립니다. 리소스/권한과 동기화가 반드시 포함되어야 합니다.

## 작업 완료 내역

### PR-05A: 메뉴 마스터 데이터 기준 확정 ✅
- **결정**: sys_menus를 메뉴 전용으로 사용
- **이유**: 명확한 책임 분리, 운영 편의성, 기존 구조 유지, 최소 변경
- **com_resources 역할**: 권한/추적 리소스로 활용
- **동기화 정책**: Menu 생성 시 com_resources에 대응 MENU 리소스 자동 생성
- **문서**: `docs/PR05_MENU_MASTER_DECISION.md` 작성

### PR-05B: Admin Menus CRUD API ✅
- **목록 조회**: `GET /api/admin/menus`
  - 필터: keyword, enabled, parentId
  - 정렬: created_at desc
- **트리 조회**: `GET /api/admin/menus/tree` (권한 필터 없이 전체 트리)
- **생성**: `POST /api/admin/menus`
  - 필드: menuKey, menuName, parentMenuId, routePath, icon, sortOrder, enabled, visible
  - 중복 → 409 DUPLICATE_ENTITY
- **수정**: `PATCH /api/admin/menus/{sysMenuId}`
- **삭제**: `DELETE /api/admin/menus/{sysMenuId}`
  - 하위 메뉴 존재 시 409 RESOURCE_HAS_CHILDREN

### PR-05C: 권한/리소스 동기화 정책 ✅
- **구현**: Menu 생성/수정 시 com_resources에 대응 MENU 리소스 자동 생성 (upsert)
- **규칙**:
  - menuKey = resourceKey
  - type = "MENU"
  - resourceCategory = "MENU"
  - resourceKind = "MENU_GROUP" (parent 없음) 또는 "PAGE" (parent 있음)
  - eventKey = menuKey + ":view"
  - eventActions = ["VIEW","USE"]
  - trackingEnabled = true
- **메뉴명 동기화**: Menu 수정 시 com_resources의 name도 업데이트

### PR-05D: 정렬/이동 API (DragDrop 대비) ✅
- **API**: `PUT /api/admin/menus/reorder`
- **Request**: `[{ menuId, parentId, sortOrder }]`
- **기능**: 부모 변경, 정렬 순서 변경 지원
- **감사로그**: 각 메뉴별 MENU_REORDER 기록

### PR-05E: 감사로그 기록 ✅
- **기록 항목**:
  - MENU_CREATE: 생성
  - MENU_UPDATE: 수정
  - MENU_DELETE: 삭제
  - MENU_REORDER: 정렬/이동
- **모든 CRUD 작업에 감사로그 기록**

### PR-05F: 테스트 작성 ✅
- **테스트 파일**: `AdminMenuControllerTest.java`
- **테스트 항목**:
  - ✅ 메뉴 생성 성공
  - ✅ 메뉴 생성 중복 → 409
  - ✅ 메뉴 정렬/이동 성공
  - ✅ 메뉴 트리 조회 정렬 보장

## 변경 파일 리스트

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/MenuRepository.java`
  - `findByTenantIdAndSysMenuId()` 추가
  - `findByTenantIdAndFilters()` 추가 (키워드 검색, 필터링, 페이징)
  - `countByTenantIdAndParentMenuId()` 추가 (하위 메뉴 수 조회)

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/menu/MenuQueryService.java`
  - `getMenus()`: 목록 조회
  - `getMenuTree()`: 트리 조회 (권한 필터 없이)
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/menu/MenuCommandService.java`
  - `createMenu()`: 생성 + com_resources 동기화
  - `updateMenu()`: 수정 + com_resources 동기화
  - `deleteMenu()`: 삭제 (하위 메뉴 충돌 정책)
  - `reorderMenus()`: 정렬/이동
  - `syncResourceFromMenu()`: com_resources 동기화 로직
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/MenuManagementService.java`
  - Facade 서비스 (Query/Command 통합)

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/AdminMenuController.java`
  - Admin Menus CRUD API 엔드포인트

### DTO 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/MenuSummary.java`
  - 메뉴 요약 DTO
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/CreateMenuRequest.java`
  - 메뉴 생성 요청 DTO
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/UpdateMenuRequest.java`
  - 메뉴 수정 요청 DTO
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/ReorderMenuRequest.java`
  - 메뉴 정렬/이동 요청 DTO

### Test 변경
- `dwp-auth-server/src/test/java/com/dwp/services/auth/controller/admin/AdminMenuControllerTest.java`
  - PR-05F 테스트 작성

## API 응답 예시

### 1. 메뉴 목록 조회
```bash
GET /api/admin/menus?page=1&size=20&keyword=admin&enabled=true
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "sysMenuId": 10,
        "menuKey": "menu.admin.users",
        "menuName": "사용자 관리",
        "menuPath": "/admin/users",
        "menuIcon": "solar:users-group-rounded-bold",
        "menuGroup": "MANAGEMENT",
        "parentMenuId": 8,
        "parentMenuKey": "menu.admin",
        "parentMenuName": "Admin",
        "sortOrder": 102,
        "depth": 2,
        "isVisible": true,
        "isEnabled": true,
        "createdAt": "2026-01-20T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "totalItems": 13,
    "totalPages": 1
  }
}
```

### 2. 메뉴 생성 - 중복 (409)
```bash
POST /api/admin/menus
Headers:
  X-Tenant-ID: 1
Body:
{
  "menuKey": "menu.admin.users",
  "menuName": "중복 메뉴"
}
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3001",
  "message": "이미 존재하는 메뉴 키입니다."
}
```

### 3. 메뉴 삭제 - 하위 메뉴 존재 (409)
```bash
DELETE /api/admin/menus/8
Headers:
  X-Tenant-ID: 1
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3005",
  "message": "하위 메뉴가 존재합니다 (5개). 하위 메뉴를 먼저 제거해주세요."
}
```

### 4. 메뉴 정렬/이동
```bash
PUT /api/admin/menus/reorder
Headers:
  X-Tenant-ID: 1
Body:
{
  "items": [
    { "menuId": 10, "parentId": null, "sortOrder": 10 },
    { "menuId": 11, "parentId": null, "sortOrder": 20 },
    { "menuId": 12, "parentId": 8, "sortOrder": 101 }
  ]
}
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": null
}
```

## 권한/리소스 동기화 정책

### Menu 생성 시
1. sys_menus에 메뉴 생성
2. com_resources에 대응 MENU 리소스 자동 생성 (upsert)
   - resourceKey = menuKey
   - type = "MENU"
   - resourceCategory = "MENU"
   - resourceKind = "MENU_GROUP" (parent 없음) 또는 "PAGE" (parent 있음)
   - eventKey = menuKey + ":view"
   - eventActions = ["VIEW","USE"]
   - trackingEnabled = true

### Menu 수정 시
- sys_menus 수정
- com_resources의 name 동기화 (메뉴명 변경 시)

### Menu 삭제 시
- sys_menus에서 soft delete (is_enabled='N', is_visible='N')
- com_resources는 유지 (권한 이력 보존)

## 보안 및 검증

### 1. 중복 방지
- Menu key: tenant_id + menu_key 유니크 제약
- 애플리케이션 레벨 중복 체크

### 2. 삭제 충돌 정책
- 하위 메뉴 존재 시 409 RESOURCE_HAS_CHILDREN
- 명확한 에러 메시지 (하위 메뉴 수 포함)

### 3. Tenant Isolation
- 모든 조회/생성/수정/삭제는 tenant_id 필터 강제
- parentMenuId는 tenant 일치 검증

## 다음 단계
- Menu tree cache 구현 시 Menu 변경 시 무효화 연계
- MenuKey 변경 API (운영 위험, 별도 API 필요)
- 대량 메뉴 import/export 기능 (향후)
