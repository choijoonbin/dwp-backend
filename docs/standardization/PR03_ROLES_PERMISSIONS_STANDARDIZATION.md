# PR-03: Roles/Permissions 운영 수준 API 완성

## 목표
Roles/Permissions는 시스템의 핵심입니다. 운영 수준으로 완성하고, 변경 즉시 반영을 보장합니다.

## 작업 완료 내역

### PR-03A: Roles CRUD ✅
- **API**: 
  - `GET /api/admin/roles` - 역할 목록 조회
  - `POST /api/admin/roles` - 역할 생성
  - `PATCH /api/admin/roles/{comRoleId}` - 역할 수정
  - `DELETE /api/admin/roles/{comRoleId}` - 역할 삭제
- **검증**:
  - `roleCode`는 CodeResolver(ROLE_CODE)로 검증 (하드코딩 금지)
  - ADMIN 역할 수정/삭제 금지
- **Soft Delete**: 현재는 hard delete (향후 개선)

### PR-03B: RoleMembers 관리 API ✅
- **API**:
  - `GET /api/admin/roles/{roleId}/members` - 역할 멤버 조회
  - `POST /api/admin/roles/{roleId}/members` - 역할 멤버 추가
  - `DELETE /api/admin/roles/{roleId}/members/{memberId}` - 역할 멤버 삭제
- **검증**:
  - `subjectType`(USER/DEPARTMENT)는 CodeResolver(SUBJECT_TYPE)로 검증
  - 중복 할당 금지 (409 DUPLICATE_ENTITY)
  - DB unique constraint로도 보장

### PR-03C: RolePermissions 조회 API ✅
- **API**: `GET /api/admin/roles/{roleId}/permissions`
- **Response**: 매트릭스 구성 가능한 구조
  - `resourceKey`
  - `resourceType` (MENU, UI_COMPONENT, PAGE_SECTION, API)
  - `permissionCode`
  - `effect` (ALLOW/DENY)
- **구현**: `RolePermissionView`에 모든 필수 필드 포함

### PR-03D: RolePermissions Bulk 저장 API ✅
- **API**: `PUT /api/admin/roles/{roleId}/permissions`
- **Request**: `[{ resourceKey, permissionCode, effect }]`
- **검증**:
  - 없는 `resourceKey`면 400 ENTITY_NOT_FOUND
  - 코드 유효성 검증 (CodeResolver)
  - `effect=null`이면 삭제, `effect=ALLOW/DENY`이면 upsert
- **정책**: delete 후 insert 방식 (audit log 포함)

### PR-03E: 변경 즉시 반영 (가장 중요) ✅
- **구현**:
  - RoleMembers 변경 시: 해당 역할을 가진 모든 사용자 캐시 무효화
  - RolePermissions 변경 시: 해당 역할을 가진 모든 사용자 캐시 무효화
  - USER인 경우: 해당 사용자 캐시 무효화
  - DEPARTMENT인 경우: 해당 부서의 모든 사용자 캐시 무효화
- **서비스**: `AdminGuardService.invalidateCache()` 호출
- **문서화**: 권한 변경 후 `/api/auth/permissions`, `/api/auth/me`, `/api/auth/menus/tree` 재조회 권장

### PR-03F: 삭제 충돌 정책 (409) ✅
- **구현**:
  - Role 삭제 시: members/permissions 존재하면 409 ROLE_IN_USE 반환
  - 에러 코드: `ROLE_IN_USE` (E3003, CONFLICT)
  - 메시지: "역할이 사용 중입니다. 멤버(X명)나 권한(Y개)을 먼저 제거해주세요."
- **Repository**: `countByTenantIdAndRoleId()` 메서드 추가

### PR-03G: 테스트 작성 ✅
- **테스트 파일**: `RoleControllerTest.java`
- **테스트 항목**:
  - ✅ role 생성 성공
  - ✅ role 수정 성공
  - ✅ member 할당 성공
  - ✅ member 할당 중복 → 409
  - ✅ permission bulk 저장 성공
  - ✅ role 삭제 충돌 → 409
- **참고**: 저장 후 `/api/auth/permissions` 결과 변화 검증은 통합 테스트에서 수행 권장

## 변경 파일 리스트

### Core 변경
- `dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java`
  - `ROLE_IN_USE` 추가 (E3003)

### Repository 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/RoleMemberRepository.java`
  - `countByTenantIdAndRoleId()` 추가
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/RolePermissionRepository.java`
  - `countByTenantIdAndRoleId()` 추가
- `dwp-auth-server/src/main/java/com/dwp/services/auth/repository/UserRepository.java`
  - `findByTenantIdAndPrimaryDepartmentId()` 추가 (캐시 무효화용)

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/role/RoleCommandService.java`
  - `deleteRole()`: 삭제 충돌 정책 (409) 추가
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/role/RoleMemberCommandService.java`
  - 캐시 무효화 추가 (PR-03E)
  - `AdminGuardService` 주입
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/role/RolePermissionCommandService.java`
  - 검증 강화 (PR-03D)
  - 캐시 무효화 추가 (PR-03E)
  - `AdminGuardService` 주입
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/admin/role/RoleQueryService.java`
  - `getRolePermissions()`: `resourceType` 추가 (PR-03C)

### DTO 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/dto/admin/RolePermissionView.java`
  - `resourceType` 필드 추가 (PR-03C)

### Cache 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/rbac/PermissionCacheManager.java`
  - `invalidateTenantCache()` 메서드 추가 (참고용)

## API 응답 예시

### 1. 역할 권한 조회 (매트릭스 구성 가능)
```bash
GET /api/admin/roles/1/permissions
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": [
    {
      "comRoleId": 1,
      "comResourceId": 10,
      "resourceKey": "menu.admin.users",
      "resourceName": "사용자 관리",
      "resourceType": "MENU",
      "comPermissionId": 1,
      "permissionCode": "VIEW",
      "permissionName": "조회",
      "effect": "ALLOW"
    },
    {
      "comRoleId": 1,
      "comResourceId": 10,
      "resourceKey": "menu.admin.users",
      "resourceName": "사용자 관리",
      "resourceType": "MENU",
      "comPermissionId": 2,
      "permissionCode": "EDIT",
      "permissionName": "수정",
      "effect": "ALLOW"
    }
  ]
}
```

### 2. 역할 권한 Bulk 저장
```bash
PUT /api/admin/roles/1/permissions
Headers:
  X-Tenant-ID: 1
Body:
{
  "items": [
    { "resourceKey": "menu.admin.users", "permissionCode": "VIEW", "effect": "ALLOW" },
    { "resourceKey": "menu.admin.users", "permissionCode": "EDIT", "effect": "ALLOW" },
    { "resourceKey": "menu.admin.users", "permissionCode": "EXECUTE", "effect": null }  // 삭제
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

### 3. 역할 삭제 - 충돌 (409)
```bash
DELETE /api/admin/roles/1
Headers:
  X-Tenant-ID: 1
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3003",
  "message": "역할이 사용 중입니다. 멤버(5명)나 권한(10개)을 먼저 제거해주세요."
}
```

### 4. 역할 멤버 추가 - 중복 (409)
```bash
POST /api/admin/roles/1/members
Headers:
  X-Tenant-ID: 1
Body:
{
  "subjectType": "USER",
  "subjectId": 100
}
```

**응답** (409 Conflict):
```json
{
  "success": false,
  "errorCode": "E3001",
  "message": "이미 할당된 멤버입니다."
}
```

## 캐시 무효화 전략

### 즉시 반영 보장
1. **RoleMembers 변경 시**:
   - USER: 해당 사용자 캐시 무효화
   - DEPARTMENT: 해당 부서의 모든 사용자 캐시 무효화
   - Bulk 업데이트: 해당 역할을 가진 모든 사용자 캐시 무효화

2. **RolePermissions 변경 시**:
   - 해당 역할을 가진 모든 사용자 캐시 무효화
   - USER 멤버: 직접 무효화
   - DEPARTMENT 멤버: 부서의 모든 사용자 무효화

3. **FE 권장 사항**:
   - 권한 변경 후 다음 API 재조회:
     - `/api/auth/permissions`
     - `/api/auth/me`
     - `/api/auth/menus/tree`

## 보안 및 검증

### 1. 코드 기반 검증
- `roleCode`: CodeResolver(ROLE_CODE)
- `subjectType`: CodeResolver(SUBJECT_TYPE)
- `permissionCode`: CodeResolver(PERMISSION_CODE)
- `effect`: CodeResolver(EFFECT_TYPE)

### 2. 중복 방지
- RoleMembers: DB unique constraint + 애플리케이션 검증
- Role code: DB unique constraint + 애플리케이션 검증

### 3. 삭제 충돌 정책
- Role 삭제 시 members/permissions 존재하면 409 ROLE_IN_USE
- 명확한 에러 메시지 (멤버 수, 권한 수 포함)

## 다음 단계
- Role soft delete 구현 (향후)
- 통합 테스트 보강 (실제 DB 사용)
- 성능 최적화 (대량 사용자 캐시 무효화 최적화)
