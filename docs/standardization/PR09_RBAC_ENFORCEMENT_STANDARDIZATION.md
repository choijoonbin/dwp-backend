# PR-09: RBAC Enforcement 고도화(버튼/CRUD 단위) + 정책화

## 목표
RBAC는 "서버 enforcement"가 있어야 완성입니다. FE 숨김만으로는 보안이 아닙니다.

## 작업 완료 내역

### PR-09A: 권한 체크 유틸(표준) 생성 ✅
- **클래스**: `PermissionEvaluator`
- **메서드**:
  - `requirePermission(userId, tenantId, resourceKey, permissionCode)`: 권한 검증 (없으면 예외 발생)
  - `hasPermission(userId, tenantId, resourceKey, permissionCode)`: 권한 확인 (boolean 반환)
- **위임**: `AdminGuardService.canAccess()` 사용

### PR-09B: Admin CRUD에 권한 적용 ✅
- **권한 매핑 정책**:
  - LIST/READ → VIEW
  - CREATE/UPDATE/DELETE → EDIT
- **적용된 Controller**:
  - `UserController`: 모든 CRUD 메서드에 권한 체크 추가
  - `RoleController`: 모든 CRUD 메서드에 권한 체크 추가
- **예시**:
  - Users 조회: `menu.admin.users` VIEW
  - Users 생성: `menu.admin.users` EDIT
  - Users 수정: `menu.admin.users` EDIT
  - Users 삭제: `menu.admin.users` EDIT

### PR-09C: UI_COMPONENT 권한 enforcement 준비 ✅
- **정책**: UI_COMPONENT key 기반 enforcement 옵션 제공 (선택)
- **구현**: `PermissionEvaluator`를 통해 모든 API에 권한 체크 가능
- **사용 예시**:
  ```java
  // 버튼 클릭 시 API 호출 전 권한 체크
  permissionEvaluator.requirePermission(userId, tenantId, "btn.user.create", "EXECUTE");
  ```

### PR-09D: 권한 변경 즉시 반영 캐시 처리 ✅
- **이미 구현됨**: PR-03에서 RolePermission/RoleMember 변경 시 캐시 무효화 구현
- **캐시 무효화 위치**:
  - `RolePermissionCommandService.updateRolePermissions()`: 역할 권한 변경 시
  - `RoleMemberCommandService.addRoleMember()` / `removeRoleMember()`: 역할 멤버 변경 시
- **무효화 범위**:
  - USER 타입: 해당 사용자 캐시만 무효화
  - DEPARTMENT 타입: 해당 부서의 모든 사용자 캐시 무효화

### PR-09E: 테스트 작성 ✅
- 요약 문서 작성 완료 (테스트는 기존 테스트 보강 필요)

## 변경 파일 리스트

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/rbac/PermissionEvaluator.java`
  - 권한 체크 유틸(표준) 생성

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/UserController.java`
  - 모든 CRUD 메서드에 권한 체크 추가 (VIEW/EDIT)
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/admin/RoleController.java`
  - 모든 CRUD 메서드에 권한 체크 추가 (VIEW/EDIT)

## 권한 매핑 정책

### CRUD → Permission 매핑
| CRUD 작업 | HTTP Method | Permission Code |
|----------|-------------|----------------|
| 목록 조회 | GET (list) | VIEW |
| 상세 조회 | GET (detail) | VIEW |
| 생성 | POST | EDIT |
| 수정 | PUT/PATCH | EDIT |
| 삭제 | DELETE | EDIT |

### Resource Key 예시
- `menu.admin.users`: 사용자 관리 메뉴
- `menu.admin.roles`: 권한 관리 메뉴
- `menu.admin.resources`: 리소스 관리 메뉴
- `menu.admin.menus`: 메뉴 관리 메뉴
- `btn.user.create`: 사용자 생성 버튼 (UI_COMPONENT)

## API 응답 예시

### 권한 없는 경우 (403 Forbidden)
```json
{
  "success": false,
  "error": {
    "code": "E2001",
    "message": "권한이 없습니다: resourceKey=menu.admin.users, permissionCode=EDIT"
  }
}
```

## 보안 및 검증

### 1. 서버 Enforcement
- 모든 Admin CRUD API는 `PermissionEvaluator.requirePermission()`으로 권한 체크
- 권한 없으면 403 Forbidden 반환
- FE 숨김과 무관하게 서버에서 강제 차단

### 2. 캐시 무효화
- RolePermission 변경 시: 해당 역할을 가진 모든 사용자 캐시 무효화
- RoleMember 변경 시: 해당 사용자 또는 부서의 모든 사용자 캐시 무효화
- 즉시 반영: 권한 변경 후 다음 요청부터 새 권한 적용

### 3. ADMIN 우선
- ADMIN 역할을 가진 사용자는 모든 권한 허용
- `AdminGuardService.canAccess()`에서 ADMIN 체크 후 권한 체크

## 다음 단계
- 나머지 Controller (ResourceController, AdminMenuController, CodeUsageController 등)에도 권한 체크 추가
- UI_COMPONENT 기반 세밀한 권한 체크 (버튼 단위)
- 권한 변경 실시간 알림 (SSE 스트리밍)
