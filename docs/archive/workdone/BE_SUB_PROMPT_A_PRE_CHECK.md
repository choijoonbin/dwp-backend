# BE Sub-Prompt A (Enhanced): 사전 점검 결과

**작성일**: 2026-01-20  
**목적**: RBAC Enforcement 서버 강제 구현 상태 확인

---

## ✅ 확인 결과

### 0) 사전 점검

#### /api/admin/** 보호 방식
- ✅ **Interceptor 기반**: `AdminGuardInterceptor`가 `/api/admin/**` 경로를 가로챔
- ✅ **등록 위치**: `WebConfig`에 등록됨
- ✅ **동작 순서**: JWT 인증 확인 → tenant_id 확인 → ADMIN role 검증

#### ADMIN 판별 방식
- ✅ **CodeResolver 기반**: `ROLE_CODE` 코드 그룹으로 "ADMIN" 검증
- ⚠️ **하드코딩 부분**: `String adminRoleCode = "ADMIN"` 문자열이 하드코딩되어 있음
- ✅ **개선 필요**: CodeResolver에서 ADMIN 코드를 동적으로 가져오도록 변경

#### 권한 데이터 원천
- ✅ **com_role_members**: USER/DEPARTMENT 할당
- ✅ **com_role_permissions**: role_id + resource_id + permission_id + effect
- ✅ **구조**: Resource.key (resourceKey), Permission.code (permissionCode)

#### resourceKey 기반 권한 검사
- ❌ **현재 없음**: `canAccess(userId, tenantId, resourceKey, permissionCode)` 메서드 없음
- ✅ **확장 필요**: 향후 확장 가능한 구조 제공 필요

---

## 현재 구현 상태

### AdminGuardInterceptor
- ✅ 존재: `/api/admin/**` 경로 보호
- ✅ JWT 인증 확인
- ✅ tenant_id 확인
- ✅ ADMIN role 검증

### AdminGuardService
- ✅ `hasAdminRole(tenantId, userId)`: ADMIN 역할 보유 여부 확인
- ✅ `requireAdminRole(tenantId, userId)`: ADMIN 역할 없으면 예외 발생
- ❌ `isAdmin(tenantId, userId)`: 별칭 없음 (hasAdminRole과 동일)
- ❌ `canAccess(userId, tenantId, resourceKey, permissionCode)`: 없음
- ❌ `getPermissions(userId, tenantId)`: 없음

### 캐시
- ❌ **없음**: 매 요청마다 DB 조회
- ✅ **필요**: Caffeine 캐시 추가 필요

### 확장 포인트
- ❌ **없음**: resourceKey + permissionCode 기반 검사 구조 없음
- ✅ **필요**: AdminEndpointPolicyRegistry 같은 구조 제공 필요

### 에러 코드
- ✅ **표준화됨**: UNAUTHORIZED (E2000), FORBIDDEN (E2001) 등 존재
- ⚠️ **보강 필요**: TENANT_MISSING, TENANT_MISMATCH, INVALID_TOKEN 추가 검토

---

## 보강 필요 사항

### 1) AdminGuardService 확장
- `isAdmin()` 메서드 추가 (hasAdminRole 별칭)
- `canAccess()` 메서드 추가 (resourceKey + permissionCode 기반)
- `getPermissions()` 메서드 추가 (권한 목록 조회)

### 2) 캐시 추가
- Caffeine 캐시 적용
- 캐시 무효화 트리거 (RoleMember/RolePermission/Role 변경 시)

### 3) 확장 포인트 제공
- AdminEndpointPolicyRegistry 구조 제공
- resourceKey + permissionCode 매핑 테이블

### 4) 에러 코드 보강
- TENANT_MISSING, TENANT_MISMATCH, INVALID_TOKEN 확인/추가

### 5) 테스트 보강
- ADMIN enforcement 테스트
- tenant 격리 테스트

### 6) 문서 작성
- RBAC_ENFORCEMENT.md 작성

---

**결론**: 기본 구조는 있으나 확장 포인트와 캐시가 필요합니다.
