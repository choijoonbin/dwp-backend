# Security: RBAC Enforcement 정책

**작성일**: 2026-01-20  
**버전**: v1.0.0

---

## 개요

DWP Backend는 서버 측 RBAC(Role-Based Access Control) Enforcement를 통해 모든 Admin CRUD API에 대한 권한 차단을 보장합니다. **"FE 숨김은 보안이 아니다"** 원칙을 엄격히 준수합니다.

---

## 핵심 원칙

### 1. 서버 Enforcement 필수 🔒

- **FE 숨김만으로는 보안이 아니다**: FE에서 버튼을 숨겨도 URL 직접 접근 시 서버가 반드시 차단해야 함
- **모든 Admin CRUD API**: `PermissionEvaluator.requirePermission()`으로 권한 체크
- **권한 없으면 403 Forbidden**: 명확한 에러 메시지 반환

### 2. 권한 매핑 정책

| 작업 | 권한 코드 | 설명 |
|------|----------|------|
| LIST/READ | VIEW | 목록 조회, 상세 조회 |
| CREATE | EDIT | 생성 |
| UPDATE | EDIT | 수정 |
| DELETE | EDIT | 삭제 |

**예시**:
- `GET /api/admin/users` → `menu.admin.users` VIEW 권한 필요
- `POST /api/admin/users` → `menu.admin.users` EDIT 권한 필요
- `PATCH /api/admin/users/{id}` → `menu.admin.users` EDIT 권한 필요
- `DELETE /api/admin/users/{id}` → `menu.admin.users` EDIT 권한 필요

---

## 권한 체크 기준

### Resource Key + Permission Code

**형식**: `resourceKey` + `permissionCode`

**예시**:
- `menu.admin.users` + `VIEW` → Users 목록/상세 조회 가능
- `menu.admin.users` + `EDIT` → Users 생성/수정/삭제 가능
- `menu.admin.roles` + `VIEW` → Roles 목록/상세 조회 가능
- `menu.admin.roles` + `EDIT` → Roles 생성/수정/삭제 가능

### Resource Key 규칙

- **Menu 리소스**: `menu.admin.{feature}` 형식
  - 예: `menu.admin.users`, `menu.admin.roles`, `menu.admin.resources`
- **UI Component 리소스**: `btn.{feature}.{action}` 형식
  - 예: `btn.mail.send`, `btn.user.delete`

---

## 권한 계산 로직

### 1. 사용자 역할 조회

- **직접 역할**: `com_role_members`에서 `subject_type=USER` 조회
- **부서 역할**: 사용자의 `primary_department_id`를 통해 `subject_type=DEPARTMENT` 조회
- **병합**: 두 역할 목록을 합산하여 최종 역할 ID 목록 생성

### 2. DENY 우선 정책

- **DENY 우선**: DENY가 하나라도 있으면 거부
- **ALLOW 확인**: DENY가 없을 때만 ALLOW 확인
- **기본값**: 아무것도 없으면 거부

**예시**:
```
사용자 A:
  - Role 1: menu.admin.users VIEW = ALLOW
  - Role 2: menu.admin.users VIEW = DENY
→ 결과: DENY (DENY 우선)
```

### 3. 권한 체크 흐름

```
1. Resource Key로 com_resources 조회
2. Permission Code로 com_permissions 조회
3. 사용자의 모든 역할 ID 조회 (USER + DEPARTMENT)
4. com_role_permissions에서 역할-권한 매핑 조회
5. DENY 우선 정책 적용
6. ALLOW 확인
```

---

## 구현 상세

### PermissionEvaluator

**위치**: `com.dwp.services.auth.service.rbac.PermissionEvaluator`

**메서드**:
- `requirePermission(userId, tenantId, resourceKey, permissionCode)`: 권한 검증 (없으면 예외 발생)
- `hasPermission(userId, tenantId, resourceKey, permissionCode)`: 권한 확인 (boolean 반환)

**사용 예시**:
```java
@Autowired
private PermissionEvaluator permissionEvaluator;

@PostMapping("/users")
public ApiResponse<UserSummary> createUser(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        Authentication authentication,
        @RequestBody CreateUserRequest request) {
    Long userId = getUserId(authentication);
    
    // 권한 체크 (없으면 403 Forbidden)
    permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.users", "EDIT");
    
    // 생성 로직
    return ApiResponse.success(userService.createUser(tenantId, userId, request));
}
```

### AdminGuardInterceptor

**위치**: `com.dwp.services.auth.config.AdminGuardInterceptor`

**역할**: `/api/admin/**` 모든 요청에 대해 ADMIN 역할 강제 검증

**체크 항목**:
1. JWT 인증 필수
2. ADMIN 역할 필수
3. tenant_id 헤더 필수

**에러**: 권한 없으면 403 Forbidden 반환

---

## 401 vs 403 정책

### 401 Unauthorized
- **의미**: 인증 실패 (JWT 없음, 만료, 서명 오류)
- **발생 시점**: `AdminGuardInterceptor`에서 JWT 검증 실패
- **해결 방법**: JWT 재발급 필요

### 403 Forbidden
- **의미**: 권한 없음 (인증은 성공했으나 권한 부족)
- **발생 시점**: `PermissionEvaluator.requirePermission()`에서 권한 체크 실패
- **해결 방법**: 역할에 권한 부여 필요

**예시**:
```json
// 401 Unauthorized
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "JWT 토큰이 없거나 만료되었습니다."
  }
}

// 403 Forbidden
{
  "success": false,
  "error": {
    "code": "FORBIDDEN",
    "message": "권한이 없습니다: resourceKey=menu.admin.users, permissionCode=EDIT"
  }
}
```

---

## 테스트 가이드

### 권한 없는 사용자 테스트

```bash
# 1. 권한 없는 토큰 발급
TOKEN=$(curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"principal":"user","password":"password"}' | jq -r '.data.accessToken')

# 2. 권한 없는 사용자로 Users 생성 시도
curl -X POST "http://localhost:8080/api/admin/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{"displayName":"테스트","email":"test@example.com"}'

# 3. 예상 결과: 403 Forbidden
# {
#   "success": false,
#   "error": {
#     "code": "FORBIDDEN",
#     "message": "권한이 없습니다: resourceKey=menu.admin.users, permissionCode=EDIT"
#   }
# }
```

### 권한 있는 사용자 테스트

```bash
# 1. 권한 있는 토큰 발급 (ADMIN 역할 + menu.admin.users EDIT 권한)
TOKEN=$(curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"principal":"admin","password":"admin1234!"}' | jq -r '.data.accessToken')

# 2. Users 생성 시도
curl -X POST "http://localhost:8080/api/admin/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-ID: 1" \
  -H "Content-Type: application/json" \
  -d '{"displayName":"테스트","email":"test@example.com"}'

# 3. 예상 결과: 200 OK
# {
#   "success": true,
#   "data": {
#     "comUserId": 100,
#     "displayName": "테스트",
#     ...
#   }
# }
```

---

## 운영 주의사항

### 1. 권한 변경 즉시 반영

- **권한 변경 시**: `PermissionCacheManager.clearCache(userId, tenantId)` 호출
- **캐시 무효화**: 권한 변경 후 즉시 반영되도록 보장

### 2. 멀티테넌시 격리

- **tenant_id 필터**: 모든 권한 체크는 tenant_id 기준으로 격리
- **테스트**: tenant A 권한이 tenant B에서 작동하지 않는지 확인

### 3. 로그 모니터링

- **권한 거부 로그**: `WARN` 레벨로 기록
- **모니터링**: 권한 거부 빈도 모니터링 (잘못된 권한 설정 감지)

**로그 예시**:
```
WARN: Permission denied: userId=11, tenantId=1, resourceKey=menu.admin.users, permissionCode=EDIT
```

---

## UI_COMPONENT 권한 Enforcement (향후)

현재는 Menu 리소스 기반 권한만 적용되지만, 향후 UI_COMPONENT 리소스 기반 권한도 적용 예정입니다.

**예시**:
- `btn.mail.send` + `EXECUTE` → 메일 전송 버튼 실행 가능
- `btn.user.delete` + `EXECUTE` → 사용자 삭제 버튼 실행 가능

---

## 참고 문서

- [PR09_RBAC_ENFORCEMENT_STANDARDIZATION.md](./PR09_RBAC_ENFORCEMENT_STANDARDIZATION.md): RBAC Enforcement 구현 상세
- [ADMIN_API_QUICKREF.md](./ADMIN_API_QUICKREF.md): Admin API 사용법

---

**작성일**: 2026-01-20  
**작성자**: DWP Backend Team
