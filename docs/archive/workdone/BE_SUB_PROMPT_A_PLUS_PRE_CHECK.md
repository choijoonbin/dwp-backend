# BE Sub-Prompt A+ (Ultra Enhanced): 사전 점검 결과

**작성일**: 2026-01-20  
**목적**: Endpoint Policy Registry 기반 RBAC Enforcement 구현 전 현황 파악

---

## ✅ 확인 결과

### 0) 사전 점검

#### Spring Security FilterChain vs Interceptor
- ✅ **Interceptor 기반**: `AdminGuardInterceptor`가 `/api/admin/**` 경로를 가로챔
- ✅ **등록 위치**: `WebConfig`에 등록됨
- ✅ **동작 순서**: JWT 인증 확인 → tenant_id 확인 → ADMIN role 검증

#### AuthContext(tenantId/userId) 추출
- ✅ **JWT claim 기반**: `AdminGuardInterceptor`에서 JWT의 `subject` (userId)와 `tenant_id` claim 추출
- ✅ **헤더 검증**: JWT의 tenant_id와 헤더의 X-Tenant-ID 일치 확인
- ✅ **Gateway 주입**: Gateway에서 헤더 전파 (FeignHeaderInterceptor)

#### /api/admin/** 403 처리 로직
- ✅ **현재 존재**: `AdminGuardService.requireAdminRole()`에서 ADMIN 없으면 403 (FORBIDDEN)
- ✅ **에러 코드**: FORBIDDEN (E2001) 사용

#### Role/Permission 계산 소스
- ✅ **com_role_members**: USER/DEPARTMENT 할당 (subject_type으로 구분)
- ✅ **com_role_permissions**: role_id + resource_id + permission_id + effect
- ⚠️ **부서 role 조회**: 현재는 USER만 조회하는 메서드만 있음 (부서 role 조회 메서드 추가 필요)

#### 권한 DTO
- ✅ **PermissionDTO**: resourceKey, permissionCode, effect 제공
- ✅ **확장 필드**: resourceCategory, resourceKind, eventKey, trackingEnabled, eventActions 포함

---

## 현재 구현 상태

### AdminGuardInterceptor
- ✅ 존재: `/api/admin/**` 경로 보호
- ✅ JWT 인증 확인
- ✅ tenant_id 확인
- ✅ ADMIN role 검증 (현재는 ADMIN만 체크)

### AdminGuardService
- ✅ `hasAdminRole()`: ADMIN 역할 보유 여부 확인 (캐시 적용)
- ✅ `canAccess()`: resourceKey + permissionCode 기반 검사 (현재는 ADMIN이면 모두 허용)
- ⚠️ **부서 role 미포함**: 현재는 USER만 조회
- ⚠️ **DENY 우선 미구현**: 현재는 ALLOW만 체크

### AdminEndpointPolicyRegistry
- ✅ 존재: 확장 포인트 구조 제공
- ❌ **정책 미등록**: 현재는 비어있음
- ❌ **method 미지원**: 현재는 path만 지원

### 캐시
- ✅ **adminRoleCache**: userId+tenantId → ADMIN 여부 (5분 TTL)
- ✅ **permissionsCache**: userId+tenantId → 권한 목록 (5분 TTL)
- ⚠️ **권한 캐시 구조**: Set<(resourceKey, permissionCode, effect)> 형태로 변경 필요

### 감사로그
- ✅ **AuditLog 엔티티**: com_audit_logs 테이블
- ✅ **AuditLogService**: 비동기 기록 지원
- ❌ **RBAC_DENY 기록**: 현재 없음

---

## 보강 필요 사항

### 1) EndpointPolicyRegistry 확장
- method (GET/POST/PATCH/DELETE) 지원 추가
- 초기 정책 세트 등록 (Code/CodeUsage/Users/Roles/Resources)
- RELAX/STRICT 모드 지원

### 2) 권한 계산 로직 강화
- 부서 role 조회 메서드 추가
- DENY 우선 정책 구현
- CodeResolver 기반 검증 강화

### 3) AdminRbacInterceptor 보강
- policy 기반 검사 로직 추가
- RELAX 모드: policy 없으면 admin만 통과
- STRICT 모드: policy 없으면 deny (TODO)

### 4) 성능 최적화
- 권한 캐시 구조 변경: Set<(resourceKey, permissionCode, effect)>
- 캐시 무효화 트리거 추가

### 5) 운영 감사로그
- RBAC_DENY 이벤트 기록
- endpoint, method, resourceKey, permissionCode 포함

### 6) 테스트
- Policy enforced 테스트
- DENY 우선 테스트
- 부서 role 테스트

### 7) 문서화
- RBAC_ENFORCEMENT.md 업데이트
- EndpointPolicyRegistry 사용법
- RELAX/STRICT 모드 설명

---

**결론**: 기본 구조는 있으나 Endpoint Policy Registry 기반 강제 검사와 DENY 우선 정책이 필요합니다.
