# BE P1-5 Final - SubPrompt 1/4: 사전 점검 결과

**작성일**: 2026-01-20  
**목적**: Endpoint Policy Registry 기반 RBAC Enforcement 구현 전 현황 파악

---

## ✅ 사전 점검 결과 (10줄 요약)

1. **Admin 보호 방식**: Spring Security **Interceptor** 기반 (`AdminGuardInterceptor`)
2. **tenantId/userId 추출**: JWT claim에서 추출 (`tenant_id` claim, `subject` = userId)
3. **/api/auth/permissions 응답**: `List<PermissionDTO>` (resourceKey, permissionCode, effect 포함)
4. **권한 계산**: `com_role_members` (USER/DEPARTMENT) + `com_role_permissions` 기반 구현됨
5. **effect 지원**: ALLOW/DENY 지원, **DENY 우선 정책** 구현됨
6. **EndpointPolicyRegistry**: 이미 구현됨 (method + pathPattern 기반)
7. **캐시**: Caffeine 캐시 적용됨 (adminRoleCache, permissionsCache, permissionSetCache)
8. **감사로그**: RBAC_DENY 이벤트 기록 구현됨 (`AuditLogService`)
9. **정책 모드**: RELAX 모드 구현됨 (STRICT 모드는 TODO)
10. **하드코딩 금지**: CodeResolver 기반 검증 적용됨

---

## 상세 점검 결과

### 1. Admin 보호 방식
- ✅ **Interceptor 기반**: `AdminGuardInterceptor`가 `/api/admin/**` 경로를 가로챔
- ✅ **등록 위치**: `WebConfig`에 등록됨
- ✅ **동작 순서**: JWT 인증 확인 → tenant_id 확인 → Policy 기반 권한 검증

### 2. tenantId/userId 추출
- ✅ **JWT claim 기반**: `AdminGuardInterceptor`에서 JWT의 `subject` (userId)와 `tenant_id` claim 추출
- ✅ **헤더 검증**: JWT의 tenant_id와 헤더의 X-Tenant-ID 일치 확인
- ✅ **에러 처리**: tenantId 없으면 400 (TENANT_MISSING), userId 없으면 401 (AUTH_REQUIRED)

### 3. /api/auth/permissions 응답 구조
- ✅ **엔드포인트**: `GET /api/auth/permissions`
- ✅ **응답 타입**: `ApiResponse<List<PermissionDTO>>`
- ✅ **PermissionDTO 필드**:
  - `resourceKey`: 리소스 키 (예: menu.admin.users)
  - `permissionCode`: 권한 코드 (예: VIEW, USE, EDIT, EXECUTE)
  - `effect`: 효과 (ALLOW, DENY)
  - `resourceCategory`, `resourceKind`, `eventKey`, `trackingEnabled` 등 확장 필드 포함

### 4. 권한 계산 구현 상태
- ✅ **com_role_members**: USER/DEPARTMENT 할당 (subject_type으로 구분)
- ✅ **com_role_permissions**: role_id + resource_id + permission_id + effect
- ✅ **부서 role 포함**: `findAllRoleIdsByTenantIdAndUserIdAndDepartmentId()` 메서드 구현됨
- ✅ **권한 계산 순서**:
  1) 사용자 role (subject_type=USER)
  2) 부서 role (subject_type=DEPARTMENT)
  3) role_permissions 조회
  4) (resourceKey + permissionCode) 기준 effect 판정

### 5. effect 지원 여부
- ✅ **ALLOW/DENY 지원**: `com_role_permissions.effect` 컬럼 사용
- ✅ **DENY 우선 정책**: `canAccess()` 메서드에서 DENY가 하나라도 있으면 거부
- ✅ **CodeResolver 검증**: EFFECT_TYPE 코드 그룹으로 검증

---

## 현재 구현 상태

### EndpointPolicyRegistry
- ✅ 구현됨: method + pathPattern 기반 정책 등록/조회
- ✅ 초기 정책 세트 등록됨 (Code/CodeUsage/Users/Roles/Resources)
- ⚠️ **보완 필요**: PUT 메서드도 지원하도록 확인 필요

### AdminGuardService
- ✅ `canAccess()` 구현됨: DENY 우선, 부서 role 포함
- ✅ `getPermissionSet()` 구현됨: 권한 Set 캐시
- ✅ CodeResolver 기반 검증 적용됨

### AdminGuardInterceptor
- ✅ Policy 기반 검사 로직 구현됨
- ✅ RBAC_DENY 감사로그 자동 기록 구현됨
- ✅ RELAX 모드 구현됨 (STRICT 모드는 TODO)

### 캐시
- ✅ `adminRoleCache`: userId+tenantId → ADMIN 여부
- ✅ `permissionsCache`: userId+tenantId → 권한 목록
- ✅ `permissionSetCache`: userId+tenantId → 권한 Set
- ⚠️ **보완 필요**: TTL이 configurable한지 확인 필요

### 감사로그
- ✅ `AuditLogService.recordAuditLog()` Map 기반 오버로드 구현됨
- ✅ RBAC_DENY 이벤트 자동 기록 구현됨

---

## 보완 필요 사항

### 1. EndpointPolicyRegistry
- PUT 메서드 지원 확인 및 추가 (PATCH와 동일하게 처리)
- 모든 endpoint 매핑이 요구사항에 맞는지 확인

### 2. 캐시 TTL
- `rbac.cache.ttl-seconds` 설정 확인 및 추가 (dev 60s / prod 5m)

### 3. 테스트
- Policy enforced 테스트 추가
- DENY 우선 테스트 추가
- 부서 role 테스트 추가
- Policy 없는 endpoint 테스트 추가

### 4. 문서화
- `RBAC_ENFORCEMENT.md` 업데이트 (현재 상태 반영)

---

**결론**: 기본 구조는 완성되었으나, 세부 사항 보완 및 테스트 추가가 필요합니다.
