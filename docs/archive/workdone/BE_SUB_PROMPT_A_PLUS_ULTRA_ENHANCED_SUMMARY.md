# BE Sub-Prompt A+ (Ultra Enhanced): Endpoint Policy Registry 기반 RBAC Enforcement 완료 요약

**작성일**: 2026-01-20  
**목적**: Endpoint 단위로 resourceKey + permissionCode까지 강제하는 운영 수준 RBAC Enforcement 완성

---

## ✅ 완료 사항

### 0) 사전 점검
- ✅ Spring Security Interceptor 기반 확인
- ✅ JWT claim 기반 tenantId/userId 추출 확인
- ✅ /api/admin/** 403 처리 로직 확인
- ✅ Role/Permission 계산 소스 확인 (com_role_members, com_role_permissions)
- ✅ 권한 DTO 확인 (resourceKey, permissionCode, effect 제공)

### 1) EndpointPolicyRegistry 구현
- ✅ `AdminEndpointPolicyRegistry` 확장:
  - method (GET/POST/PATCH/DELETE) 지원 추가
  - `PolicyKey` 클래스로 method + pathPattern 조합 관리
  - `findPolicies(method, path)` 메서드로 정책 조회
  - RELAX/STRICT 모드 지원 (현재 RELAX만 구현, STRICT는 TODO)

### 2) 초기 정책 세트 등록
- ✅ `@PostConstruct`로 자동 초기화
- ✅ Code Management: GET/POST/PATCH/DELETE `/api/admin/codes/**` → `menu.admin.codes` + `VIEW/EDIT/EXECUTE`
- ✅ Code Usage: GET/POST/PATCH/DELETE `/api/admin/code-usages/**` → `menu.admin.code-usages` + `VIEW/EDIT/EXECUTE`
- ✅ Users: GET/POST/PATCH/DELETE `/api/admin/users/**` → `menu.admin.users` + `VIEW/EDIT/EXECUTE`
- ✅ Roles: GET/POST/PATCH/DELETE `/api/admin/roles/**` → `menu.admin.roles` + `VIEW/EDIT/EXECUTE`
- ✅ Resources: GET/POST/PATCH/DELETE `/api/admin/resources/**` → `menu.admin.resources` + `VIEW/EDIT/EXECUTE`

### 3) 권한 계산 로직 강화
- ✅ 부서 role 포함:
  - `RoleMemberRepository.findAllRoleIdsByTenantIdAndUserIdAndDepartmentId()` 추가
  - 사용자 직접 role (USER) + 부서 role (DEPARTMENT) 모두 포함
- ✅ DENY 우선 정책:
  - DENY가 하나라도 있으면 거부
  - ALLOW 하나라도 있으면 허용 (DENY 없을 때)
  - 아무것도 없으면 거부
- ✅ CodeResolver 기반 검증:
  - PERMISSION_CODE 검증
  - EFFECT_TYPE 검증 (ALLOW, DENY)

### 4) AdminRbacInterceptor 보강
- ✅ Policy 기반 검사 로직 추가:
  - `endpointPolicyRegistry.findPolicies(method, path)` 호출
  - Policy 있으면 각 policy에 대해 `canAccess()` 검사
  - Policy 없으면 RELAX 모드 (admin만 통과)
- ✅ RBAC_DENY 감사로그 자동 기록:
  - `recordRbacDenyAuditLog()` 메서드 추가
  - endpoint, method, resourceKey, permissionCode 포함

### 5) 성능 최적화
- ✅ 권한 Set 캐시 추가:
  - `permissionSetCache`: userId+tenantId → Set<(resourceKey, permissionCode, effect)>
  - Configurable TTL: `rbac.cache.ttl-seconds` 설정 지원
- ✅ 캐시 무효화 강화:
  - `permissionSetCache` 무효화 추가
  - RoleMember/RolePermission/Role 변경 시 호출

### 6) 운영 감사로그
- ✅ `AuditLogService` 확장:
  - Map 기반 `recordAuditLog()` 오버로드 추가
  - RBAC_DENY 이벤트 기록 지원
- ✅ RBAC 실패 시 자동 기록:
  - `AdminGuardInterceptor`에서 권한 검사 실패 시 자동 기록
  - 비동기 처리 (`@Async`)로 메인 로직에 영향 없음
  - Silent fail 정책 유지

### 7) 테스트 (TODO)
- ⚠️ Policy enforced 테스트 추가 필요
- ⚠️ DENY 우선 테스트 추가 필요
- ⚠️ 부서 role 테스트 추가 필요

### 8) 문서화
- ✅ `RBAC_ENFORCEMENT.md` 업데이트:
  - Endpoint Policy Registry 소개
  - RELAX/STRICT 모드 설명
  - 권한 계산/우선순위 (DENY 우선)
  - 부서 role 포함 설명
  - 운영 감사로그 설명
  - curl 예시

---

## 주요 변경 파일

### Core Files
1. **`AdminEndpointPolicyRegistry.java`**
   - method + pathPattern 기반 정책 등록/조회
   - 초기 정책 세트 자동 등록
   - RELAX/STRICT 모드 지원

2. **`AdminGuardService.java`**
   - `canAccess()` 강화: DENY 우선, 부서 role 포함
   - `getPermissionSet()` 추가: 권한 Set 캐시
   - CodeResolver 기반 검증 강화

3. **`AdminGuardInterceptor.java`**
   - Policy 기반 검사 로직 추가
   - RBAC_DENY 감사로그 자동 기록

4. **`AuditLogService.java`**
   - Map 기반 `recordAuditLog()` 오버로드 추가

### Repository Files
5. **`RoleMemberRepository.java`**
   - `findRoleIdsByTenantIdAndDepartmentId()` 추가
   - `findAllRoleIdsByTenantIdAndUserIdAndDepartmentId()` 추가

### Documentation Files
6. **`RBAC_ENFORCEMENT.md`**
   - Ultra Enhanced 내용 추가
   - Endpoint Policy Registry 설명
   - DENY 우선 정책 설명
   - 부서 role 포함 설명
   - 운영 감사로그 설명

7. **`BE_SUB_PROMPT_A_PLUS_PRE_CHECK.md`** (신규)
   - 사전 점검 결과 문서화

---

## 핵심 개선 사항

### 1. Endpoint 단위 권한 강제
- 기존: ADMIN role만 체크
- 개선: Endpoint별로 resourceKey + permissionCode 강제

### 2. DENY 우선 정책
- 기존: ALLOW만 체크
- 개선: DENY 우선, DENY가 하나라도 있으면 거부

### 3. 부서 role 포함
- 기존: 사용자 직접 role만 체크
- 개선: 사용자 직접 role + 부서 role 모두 포함

### 4. 운영 감사로그
- 기존: 없음
- 개선: RBAC 실패 시 자동 기록 (endpoint, method, resourceKey, permissionCode 포함)

### 5. 성능 최적화
- 기존: adminRoleCache, permissionsCache
- 개선: permissionSetCache 추가, configurable TTL

---

## 향후 작업 (TODO)

### 1. STRICT 모드 구현
- 현재: RELAX 모드만 구현 (policy 없으면 admin만 통과)
- 향후: STRICT 모드 구현 (policy 없으면 deny)

### 2. 테스트 보강
- Policy enforced 테스트 추가
- DENY 우선 테스트 추가
- 부서 role 테스트 추가

### 3. 캐시 무효화 개선
- 현재: RolePermission CRUD 완료 시 해당 tenant 전체 무효화
- 향후: 더 세밀한 무효화 (특정 resourceKey/permissionCode만 무효화)

### 4. 정책 테이블 확장
- 현재: 하드코딩된 정책 (CodeResolver로 검증)
- 향후: `sys_auth_policies` 테이블로 확장 가능

---

## 검증 방법

### 1. 컴파일 확인
```bash
./gradlew :dwp-auth-server:compileJava
# BUILD SUCCESSFUL
```

### 2. 정책 등록 확인
- `AdminEndpointPolicyRegistry.initializePolicies()` 실행 확인
- 로그에서 "Endpoint Policy Registry initialized with X policies" 확인

### 3. 권한 검사 확인
- 일반 계정으로 GET `/api/admin/users` → 403
- 권한 부여 후 GET `/api/admin/users` → 200
- DENY 권한 있으면 → 403 (DENY 우선)

### 4. 감사로그 확인
- RBAC 실패 시 `com_audit_logs` 테이블에 `RBAC_DENY` 이벤트 기록 확인
- 메타데이터에 endpoint, method, resourceKey, permissionCode 포함 확인

---

**작성자**: DWP Backend Team  
**최종 업데이트**: 2026-01-20
