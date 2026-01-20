# RBAC Enforcement (BE P1-5 Final: Endpoint Policy Registry 기반) 명세서

**작성일**: 2026-01-20  
**최종 업데이트**: 2026-01-20 (BE P1-5 Final)  
**목적**: Endpoint 단위로 resourceKey + permissionCode까지 강제하는 운영 수준 RBAC Enforcement 완성

---

## 핵심 정책 (상단 10줄)

1. **401/403 구분**: JWT 없음/만료/위조 → 401 (AUTH_REQUIRED/TOKEN_INVALID), 인증은 됐지만 권한 없음 → 403 (FORBIDDEN)
2. **Endpoint Policy Registry**: HTTP Endpoint 단위로 필요한 권한을 명시적으로 맵핑 (`AdminEndpointPolicyRegistry`)
3. **DENY 우선 정책**: 동일 user에 ALLOW + DENY 공존 시 → DENY 승리 (403)
4. **부서 role 포함**: 사용자 직접 role 할당 (USER) + 부서 role 할당 (DEPARTMENT) 모두 포함
5. **RELAX/STRICT 모드**: Policy 없으면 RELAX (admin만 통과) / STRICT (deny, TODO)
6. **tenant_id 격리**: 모든 검사에서 tenant_id 기준으로 강제 격리, JWT의 tenant_id와 헤더의 X-Tenant-ID 일치 확인
7. **하드코딩 금지**: Role/Permission/Effect 하드코딩 금지, CodeResolver 기반 검증
8. **성능 최적화**: Caffeine 캐시 적용 (configurable TTL: dev 60s / prod 5m), 권한 Set 캐싱 추가
9. **운영 감사로그**: RBAC 실패 시 `com_audit_logs`에 `RBAC_DENY` 이벤트 기록 (endpoint, method, resourceKey, permissionCode 포함)
10. **테스트 필수**: Policy enforced, DENY 우선, 부서 role 테스트 포함

---

## 1. Endpoint Policy Registry 기반 Enforcement

### 1.1 MUST: /api/admin/** 전체 보호
- `AdminGuardInterceptor`가 `/api/admin/**` 또는 `/admin/**` 경로를 가로챔
- JWT 인증 확인 → tenant_id 확인 → Policy 기반 권한 검증
- 실패 시 403 ApiResponse 에러 반환

### 1.2 Endpoint Policy Registry 구조
- **등록**: `registerPolicy(method, pathPattern, resourceKey, permissionCode)`
- **조회**: `findPolicies(method, path)` → `List<RequiredPermission>`
- **정책 모드**: RELAX (기본, admin만 통과) 또는 STRICT (policy 없으면 deny, TODO)

### 1.3 초기 정책 세트 (반드시 포함)
- **Code Management**: GET/POST/PATCH/DELETE `/api/admin/codes/**` → `menu.admin.codes` + `VIEW/EDIT/EXECUTE`
- **Code Usage**: GET/POST/PATCH/DELETE `/api/admin/code-usages/**` → `menu.admin.code-usages` + `VIEW/EDIT/EXECUTE`
- **Users**: GET/POST/PATCH/DELETE `/api/admin/users/**` → `menu.admin.users` + `VIEW/EDIT/EXECUTE`
- **Roles**: GET/POST/PATCH/DELETE `/api/admin/roles/**` → `menu.admin.roles` + `VIEW/EDIT/EXECUTE`
- **Resources**: GET/POST/PATCH/DELETE `/api/admin/resources/**` → `menu.admin.resources` + `VIEW/EDIT/EXECUTE`

### 1.4 정책 적용 방식
1. ADMIN인지 먼저 검사 (optional)
2. registry에서 policy 찾기 (`findPolicies(method, path)`)
3. policy가 있으면 `canAccess(userId, tenantId, resourceKey, permissionCode)` 검사
4. 없으면 기본 정책:
   - **RELAX 모드**: admin만 통과
   - **STRICT 모드**: deny (TODO)

---

## 2. 401/403 구분 기준

### 2.1 401 Unauthorized (인증 실패)
- JWT 토큰 없음: `AUTH_REQUIRED` (E2005)
- JWT 토큰 만료: `TOKEN_EXPIRED` (E2002)
- JWT 토큰 위조/유효하지 않음: `TOKEN_INVALID` (E2003)
- JWT subject가 유효하지 않음: `TOKEN_INVALID` (E2003)

### 2.2 403 Forbidden (권한 없음)
- 인증은 성공했지만 ADMIN 역할 없음: `FORBIDDEN` (E2001)
- tenant_id 불일치: `TENANT_MISMATCH` (E2007)

### 2.3 400 Bad Request
- tenant_id 없음: `TENANT_MISSING` (E2006)

---

## 3. AdminGuardService API

### 3.1 isAdmin()
```java
boolean isAdmin(Long tenantId, Long userId)
```
- ADMIN 역할 보유 여부 확인 (캐시 적용)
- 별칭: `hasAdminRole()`과 동일

### 3.2 canAccess() (Ultra Enhanced: DENY 우선, 부서 role 포함)
```java
boolean canAccess(Long userId, Long tenantId, String resourceKey, String permissionCode)
```

**권한 계산 정책**:
1. ADMIN이면 모든 권한 허용
2. 사용자 직접 role 할당 (USER) + 부서 role 할당 (DEPARTMENT) 모두 포함
3. **DENY 우선**: DENY가 하나라도 있으면 거부
4. ALLOW 하나라도 있으면 허용 (DENY 없을 때)
5. 아무것도 없으면 거부

**예시**:
```java
// 성공: ALLOW 있음
canAccess(1L, 1L, "menu.admin.users", "VIEW") → true

// 실패: DENY 우선
// com_role_permissions에 (menu.admin.users, VIEW, DENY) 있으면 → false

// 실패: 권한 없음
// com_role_permissions에 해당 권한 없으면 → false
```

### 3.3 getPermissions()
```java
List<PermissionDTO> getPermissions(Long userId, Long tenantId)
```
- 사용자의 권한 목록 조회 (캐시 적용)
- resourceKey, permissionCode, effect 포함

### 3.4 requireAdminRole()
```java
void requireAdminRole(Long tenantId, Long userId)
```
- ADMIN 역할 검증 (없으면 예외 발생)
- `AdminGuardInterceptor`에서 사용

### 3.5 invalidateCache()
```java
void invalidateCache(Long tenantId, Long userId)
```
- 캐시 무효화 (RoleMember/RolePermission/Role 변경 시 호출)

---

## 4. 성능 최적화 (캐시)

### 4.1 Caffeine 캐시 적용
- **adminRoleCache**: userId+tenantId → ADMIN 여부 (5분 TTL, 최대 1000개)
- **permissionsCache**: userId+tenantId → 권한 목록 (5분 TTL, 최대 500개)
- **permissionSetCache**: userId+tenantId → 권한 Set<(resourceKey, permissionCode, effect)> (configurable TTL, 최대 500개)

### 4.2 캐시 무효화 트리거
- RoleMember 변경 시: `adminGuardService.invalidateCache(tenantId, userId)`
- RolePermission 변경 시: `adminGuardService.invalidateCache(tenantId, userId)`
- Role 변경 시: 해당 역할을 가진 모든 사용자 캐시 무효화
- **TODO**: RolePermission CRUD 완료 시 해당 tenant 전체 무효화

---

## 5. 운영 감사로그 (RBAC_DENY)

### 5.1 RBAC 실패 기록
- **이벤트 타입**: `RBAC_DENY`
- **리소스 타입**: `RBAC`
- **메타데이터 포함**:
  - `method`: HTTP 메서드 (GET, POST, PATCH, DELETE 등)
  - `path`: 요청 경로
  - `resourceKey`: 리소스 키 (예: menu.admin.users)
  - `permissionCode`: 권한 코드 (예: VIEW, EDIT, EXECUTE)
  - `ipAddress`: 클라이언트 IP 주소
  - `userAgent`: User-Agent 헤더

### 5.2 기록 시점
- `AdminGuardInterceptor`에서 권한 검사 실패 시 자동 기록
- 비동기 처리 (`@Async`)로 메인 로직에 영향 없음
- Silent fail: 감사 로그 실패가 메인 로직에 영향을 주지 않도록

## 6. 확장 구조 (resourceKey + permissionCode)

### 6.1 현재 구조 (Ultra Enhanced)
- **Endpoint Policy Registry**: 초기 정책 세트 등록 완료
- **권한 계산**: DENY 우선, 부서 role 포함
- **정책 적용**: Policy 있으면 `canAccess()` 검사, 없으면 RELAX 모드

### 6.2 정책 등록 예시
```java
// AdminEndpointPolicyRegistry.initializePolicies()에 자동 등록
registerPolicy("GET", "^/api/admin/users$", "menu.admin.users", "VIEW");
registerPolicy("POST", "^/api/admin/users$", "menu.admin.users", "EDIT");
registerPolicy("DELETE", "^/api/admin/users/\\d+$", "menu.admin.users", "EXECUTE");
```

### 6.3 정책 적용 흐름
```java
// AdminGuardInterceptor에서
List<RequiredPermission> policies = endpointPolicyRegistry.findPolicies(method, path);
if (policies.isEmpty()) {
    // RELAX 모드: admin만 통과
    adminGuardService.requireAdminRole(tenantId, userId);
} else {
    // Policy 있음: 각 policy에 대해 권한 검사
    for (RequiredPermission policy : policies) {
        boolean canAccess = adminGuardService.canAccess(userId, tenantId, 
                policy.getResourceKey(), policy.getPermissionCode());
        if (!canAccess) {
            // RBAC_DENY 감사로그 기록
            recordRbacDenyAuditLog(...);
            throw new BaseException(ErrorCode.FORBIDDEN, "권한이 없습니다.");
        }
    }
}
```

---

## 7. 에러 코드

| 에러 코드 | HTTP 상태 | 설명 |
|---------|---------|------|
| AUTH_REQUIRED | 401 | 인증이 필요합니다 |
| FORBIDDEN | 403 | 권한이 없습니다 (ADMIN 역할 필요) |
| TENANT_MISSING | 400 | 테넌트 정보가 필요합니다 |
| TENANT_MISMATCH | 403 | 테넌트 정보가 일치하지 않습니다 |
| TOKEN_INVALID | 401 | 유효하지 않은 토큰입니다 |
| TOKEN_EXPIRED | 401 | 토큰이 만료되었습니다 |

---

## 8. curl 예시

### 7.1 ADMIN 계정으로 접근 (성공)
```bash
curl -X GET "http://localhost:8080/api/admin/users" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -H "X-Tenant-ID: 1"
```

**Response**: 200 OK

### 7.2 일반 계정으로 접근 (403)
```bash
curl -X GET "http://localhost:8080/api/admin/users" \
  -H "Authorization: Bearer <USER_JWT>" \
  -H "X-Tenant-ID: 1"
```

**Response**:
```json
{
  "success": false,
  "status": "ERROR",
  "message": "관리자 권한이 필요합니다.",
  "errorCode": "E2001",
  "timestamp": "2026-01-20T10:00:00"
}
```

### 7.3 JWT 없이 접근 (401)
```bash
curl -X GET "http://localhost:8080/api/admin/users" \
  -H "X-Tenant-ID: 1"
```

**Response**:
```json
{
  "success": false,
  "status": "ERROR",
  "message": "인증이 필요합니다.",
  "errorCode": "E2005",
  "timestamp": "2026-01-20T10:00:00"
}
```

### 7.4 tenant_id 불일치 (403)
```bash
curl -X GET "http://localhost:8080/api/admin/users" \
  -H "Authorization: Bearer <JWT_WITH_TENANT_1>" \
  -H "X-Tenant-ID: 2"
```

**Response**:
```json
{
  "success": false,
  "status": "ERROR",
  "message": "테넌트 정보가 일치하지 않습니다.",
  "errorCode": "E2007",
  "timestamp": "2026-01-20T10:00:00"
}
```

---

## 9. 테스트 (Ultra Enhanced)

### 9.1 Policy enforced 테스트
- ✅ 일반 계정으로 GET `/api/admin/users` → 403 (policy 있음, 권한 없음)
- ✅ 권한 부여 후 GET `/api/admin/users` → 200

### 9.2 DENY 우선 테스트
- ✅ 동일 user에 ALLOW + DENY 공존 시 → DENY 승리 (403)
- ✅ DENY만 있으면 → 403
- ✅ ALLOW만 있으면 → 200

### 9.3 부서 role 테스트
- ✅ 사용자 직접 role 없지만 부서 role 있으면 → 권한 부여됨
- ✅ 사용자 직접 role + 부서 role 모두 있으면 → 합집합으로 권한 계산

### 9.4 Policy 없는 endpoint 테스트
- ✅ RELAX 모드: admin OK / non-admin 403
- ⚠️ STRICT 모드: TODO (policy 없으면 deny)

### 9.5 ADMIN enforcement 테스트
- ✅ ADMIN 계정으로 `/api/admin/codes/groups` → 200
- ✅ 일반 계정으로 `/api/admin/codes/groups` → 403

### 9.6 tenant 격리 테스트
- ✅ tenantId 다르게 요청 시 403 (TENANT_MISMATCH)

### 9.7 에러 코드 테스트
- ✅ JWT 없음 → 401 (AUTH_REQUIRED)
- ✅ tenant_id 없음 → 400 (TENANT_MISSING)
- ✅ JWT subject 유효하지 않음 → 401 (TOKEN_INVALID)

---

## 10. 구현 파일

### 9.1 Core Files
- `AdminGuardInterceptor.java`: Interceptor 구현
- `AdminGuardService.java`: 권한 검증 서비스 (캐시 포함)
- `AdminEndpointPolicyRegistry.java`: 확장 포인트 구조

### 9.2 Test Files
- `AdminGuardInterceptorTest.java`: Interceptor 테스트
- `AdminGuardServiceTest.java`: Service 테스트

### 9.3 Configuration Files
- `WebConfig.java`: Interceptor 등록
- `ErrorCode.java`: 에러 코드 정의

---

## 11. 향후 확장 가이드

### 11.1 STRICT 모드 구현 (TODO)
- 현재: RELAX 모드만 구현 (policy 없으면 admin만 통과)
- 향후: STRICT 모드 구현 (policy 없으면 deny)
- `AdminEndpointPolicyRegistry.setMode(PolicyMode.STRICT)` 호출 시 적용

### 11.2 정책 테이블 확장
- 현재: `com_roles.code = "ADMIN"` 하드코딩 (CodeResolver로 검증)
- 향후: `sys_auth_policies` 테이블로 확장 가능
- TODO: 정책 테이블 설계 및 마이그레이션

### 11.3 캐시 무효화 개선
- 현재: RolePermission CRUD 완료 시 해당 tenant 전체 무효화
- 향후: 더 세밀한 무효화 (특정 resourceKey/permissionCode만 무효화)

---

---

## 12. 주요 변경 사항 (Ultra Enhanced)

### 12.1 Endpoint Policy Registry 구현
- `AdminEndpointPolicyRegistry`: method + pathPattern 기반 정책 등록/조회
- 초기 정책 세트 자동 등록 (`@PostConstruct`)
- RELAX/STRICT 모드 지원

### 12.2 권한 계산 로직 강화
- 부서 role 포함: `findAllRoleIdsByTenantIdAndUserIdAndDepartmentId()` 추가
- DENY 우선 정책: DENY가 하나라도 있으면 거부
- CodeResolver 기반 검증: PERMISSION_CODE, EFFECT_TYPE 검증

### 12.3 AdminRbacInterceptor 보강
- Policy 기반 검사 로직 추가
- RBAC_DENY 감사로그 자동 기록
- RELAX 모드: policy 없으면 admin만 통과

### 12.4 성능 최적화
- 권한 Set 캐시 추가: `permissionSetCache`
- Configurable TTL: `rbac.cache.ttl-seconds` 설정 지원

### 12.5 운영 감사로그
- `AuditLogService.recordAuditLog()` Map 기반 오버로드 추가
- RBAC_DENY 이벤트 자동 기록 (endpoint, method, resourceKey, permissionCode 포함)

---

**작성자**: DWP Backend Team  
**최종 업데이트**: 2026-01-20 (Ultra Enhanced)
