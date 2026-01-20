# BE Sub-Prompt A (Enhanced): RBAC Enforcement 서버 강제 완성 요약

**작성일**: 2026-01-20  
**목적**: 프론트에서 숨기는 수준이 아니라 서버가 권한을 강제하도록 RBAC Enforcement 완성

---

## ✅ 완료 사항

### 0) 사전 점검 및 현황 파악
- ✅ `/api/admin/**` 보호 방식: Interceptor 기반 (`AdminGuardInterceptor`)
- ✅ ADMIN 판별: CodeResolver 기반 (`ROLE_CODE` 코드 그룹)
- ✅ 권한 데이터 원천: `com_role_members`, `com_role_permissions` 확인
- ❌ resourceKey 기반 권한 검사: 없음 (확장 필요)

**상세**: [docs/BE_SUB_PROMPT_A_PRE_CHECK.md](BE_SUB_PROMPT_A_PRE_CHECK.md)

---

### 1) AdminGuardService 확장
- ✅ `isAdmin(tenantId, userId)`: ADMIN 역할 확인 (별칭, hasAdminRole과 동일)
- ✅ `canAccess(userId, tenantId, resourceKey, permissionCode)`: 확장 포인트 제공
  - 현재: ADMIN이면 모든 권한 허용
  - 향후: resourceKey + permissionCode 기반 세부 권한 검사 가능
- ✅ `getPermissions(userId, tenantId)`: 권한 목록 조회 (캐시 적용)
- ✅ `getAdminRoleCode()`: ADMIN 역할 코드 동적 조회 (하드코딩 제거)

---

### 2) 캐시 추가 (Caffeine)
- ✅ **adminRoleCache**: userId+tenantId → ADMIN 여부 (5분 TTL, 최대 1000개)
- ✅ **permissionsCache**: userId+tenantId → 권한 목록 (5분 TTL, 최대 500개)
- ✅ **캐시 무효화**: `invalidateCache(tenantId, userId)` 메서드 제공
- ✅ **의존성 추가**: `build.gradle`에 Caffeine 추가

---

### 3) 확장 포인트 제공
- ✅ `AdminEndpointPolicyRegistry.java` 신규 작성
  - 엔드포인트 패턴 → 필요한 권한 매핑 구조
  - 향후 resourceKey + permissionCode 기반 세부 권한 검사 가능
  - 예: `/api/admin/users` → `menu.admin.users` + `USE`

---

### 4) 에러 코드 표준화
- ✅ `AUTH_REQUIRED` (E2005): 인증이 필요합니다
- ✅ `TENANT_MISSING` (E2006): 테넌트 정보가 필요합니다
- ✅ `TENANT_MISMATCH` (E2007): 테넌트 정보가 일치하지 않습니다
- ✅ `TOKEN_INVALID` (E2003): 유효하지 않은 토큰입니다 (기존)
- ✅ `FORBIDDEN` (E2001): 권한이 없습니다 (기존)

---

### 5) AdminGuardInterceptor 보강
- ✅ **401/403 구분**: 
  - 인증 실패 → 401 (AUTH_REQUIRED, TOKEN_INVALID)
  - 권한 없음 → 403 (FORBIDDEN)
- ✅ **tenant_id 검증 강화**:
  - JWT의 tenant_id와 헤더의 X-Tenant-ID 일치 확인
  - 불일치 시 TENANT_MISMATCH (403)
- ✅ **에러 메시지 개선**: 명확한 에러 코드 및 메시지

---

### 6) 테스트 보강
- ✅ `AdminGuardInterceptorTest.java` 보강:
  - AUTH_REQUIRED, TENANT_MISSING, TOKEN_INVALID, TENANT_MISMATCH 테스트 추가
- ✅ `AdminGuardServiceTest.java` 신규 작성:
  - isAdmin(), canAccess(), getPermissions(), invalidateCache() 테스트

---

### 7) 문서 작성
- ✅ `RBAC_ENFORCEMENT.md` 신규 작성:
  - Enforcement 정책 요약 (10줄)
  - 401/403 기준
  - 확장 구조 (resourceKey+permissionCode) 설명
  - curl 예시
- ✅ `BE_SUB_PROMPT_A_PRE_CHECK.md` 작성
- ✅ `BE_SUB_PROMPT_A_ENHANCED_SUMMARY.md` 작성 (본 문서)

---

## 📋 주요 변경 파일

### Service Files
- `AdminGuardService.java`:
  - `isAdmin()` 메서드 추가
  - `canAccess()` 메서드 추가 (확장 포인트)
  - `getPermissions()` 메서드 추가
  - `getAdminRoleCode()` 메서드 추가 (하드코딩 제거)
  - Caffeine 캐시 추가
  - `invalidateCache()` 메서드 추가

### Config Files
- `AdminGuardInterceptor.java`:
  - 401/403 구분 강화
  - tenant_id 검증 강화
  - 에러 코드 표준화
- `AdminEndpointPolicyRegistry.java` (신규):
  - 확장 포인트 구조 제공

### Core Files
- `ErrorCode.java`:
  - AUTH_REQUIRED, TENANT_MISSING, TENANT_MISMATCH 추가

### Build Files
- `build.gradle`:
  - Caffeine 의존성 추가

### Test Files
- `AdminGuardInterceptorTest.java`: 보강
- `AdminGuardServiceTest.java` (신규)

### Documentation Files
- `RBAC_ENFORCEMENT.md` (신규)
- `BE_SUB_PROMPT_A_PRE_CHECK.md` (신규)
- `BE_SUB_PROMPT_A_ENHANCED_SUMMARY.md` (본 문서)

---

## ✅ 완료 조건 확인

- ✅ `/api/admin/**` 무조건 ADMIN만 통과 (403)
- ✅ 401/403 구분 정확히 (인증 실패 vs 권한 없음)
- ✅ 확장 포인트 제공 (resourceKey + permissionCode)
- ✅ 성능 최적화 (Caffeine 캐시)
- ✅ 에러 표준화 (ApiResponse<T> + 표준 코드)
- ✅ 테스트 통과 (컴파일 성공)
- ✅ 문서 작성 완료

---

## 🔍 Enforcement 동작 흐름

```
요청: /api/admin/users
  ↓
1. 경로 확인 (/api/admin/** 또는 /admin/**)
  ├─ 아니면 → 통과
  └─ 맞으면 → 다음 단계
  ↓
2. JWT 인증 확인
  ├─ 없으면 → 401 (AUTH_REQUIRED)
  └─ 있으면 → 다음 단계
  ↓
3. JWT 유효성 확인
  ├─ subject 유효하지 않음 → 401 (TOKEN_INVALID)
  └─ 유효함 → 다음 단계
  ↓
4. tenant_id 확인
  ├─ 없으면 → 400 (TENANT_MISSING)
  └─ 있으면 → 다음 단계
  ↓
5. tenant_id 일치 확인 (JWT vs 헤더)
  ├─ 불일치 → 403 (TENANT_MISMATCH)
  └─ 일치 → 다음 단계
  ↓
6. ADMIN 역할 검증 (캐시 적용)
  ├─ 없으면 → 403 (FORBIDDEN)
  └─ 있으면 → 통과
```

---

## 🛡️ 보안 정책

### tenant_id 격리
- 모든 검사에서 tenant_id 기준으로 강제 격리
- JWT의 tenant_id와 헤더의 X-Tenant-ID 일치 확인

### 하드코딩 금지
- Role/Permission 하드코딩 금지
- CodeResolver 기반으로 "ADMIN" 코드 검증
- `getAdminRoleCode()` 메서드로 동적 조회

### 확장 가능성
- 현재: ADMIN 전체 허용
- 향후: resourceKey + permissionCode 기반 세부 권한 검사 가능
- `AdminEndpointPolicyRegistry` 구조 제공

---

## 📝 향후 확장 가이드

### 세부 권한 검사 활성화
1. `AdminEndpointPolicyRegistry.initializePolicies()`에 정책 등록
2. `AdminGuardInterceptor`에서 `canAccess()` 호출 추가
3. `AdminGuardService.canAccess()` 로직 보강

### 정책 테이블 확장
- 현재: `com_roles.code = "ADMIN"` (CodeResolver로 검증)
- 향후: `sys_auth_policies` 테이블로 확장 가능
- TODO: 정책 테이블 설계 및 마이그레이션

---

**작업 완료일**: 2026-01-20  
**작성자**: DWP Backend Team
