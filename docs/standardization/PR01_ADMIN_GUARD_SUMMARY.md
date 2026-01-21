# PR-01A: Admin API 보안 흐름 점검 요약

## 현재 보안 구조

### 1. 보호 방식
- **Interceptor 기반**: `AdminGuardInterceptor` (HandlerInterceptor)
- **등록 위치**: `WebConfig.addInterceptors()` → `/api/admin/**`, `/admin/**` 경로에 적용
- **실행 순서**: Spring Security JWT 필터 → AdminGuardInterceptor

### 2. 인증 (401) 처리
- **Spring Security Filter Chain**: `JwtConfig.securityFilterChain()`
  - 공개 엔드포인트는 `permitAll()`
  - 나머지는 `authenticated()` → JWT 토큰 검증 필수
- **예외 처리**: `SecurityExceptionHandler` (AuthenticationEntryPoint)
  - 401 응답: `ErrorCode.UNAUTHORIZED`, `ErrorCode.TOKEN_EXPIRED`, `ErrorCode.TOKEN_INVALID`, `ErrorCode.AUTH_REQUIRED`
- **JWT 클레임**: `subject` (userId), `tenant_id` (tenantId) 필수

### 3. 권한 (403) 처리
- **AdminGuardInterceptor**: JWT 인증 후 권한 검증
  - Policy 없음: `requireAdminRole()` → ADMIN 역할 필수
  - Policy 있음: `canAccess()` → resourceKey + permissionCode 기반 RBAC
- **예외 처리**: `BaseException(ErrorCode.FORBIDDEN)`
- **AdminGuardService**: ADMIN 역할 판별 및 권한 검사
  - `hasAdminRole()`: ADMIN 역할 확인 (캐시 적용)
  - `canAccess()`: RBAC 권한 검사 (ADMIN이면 모든 권한 허용)

### 4. ADMIN 판별 기준
- **CodeResolver 기반**: `ROLE_CODE` 그룹에서 "ADMIN" 코드 조회
- **하드코딩 제거**: `getAdminRoleCode()` 메서드에서 CodeResolver 사용
- **검증**: `codeResolver.require("ROLE_CODE", adminCode)` 호출

### 5. Tenant 격리
- **JWT 클레임**: `tenant_id` 필수 (없으면 `TENANT_MISSING` 예외)
- **헤더 검증**: `X-Tenant-ID` 헤더와 JWT `tenant_id` 불일치 시 `TENANT_MISMATCH` 예외 (403)
- **모든 DB 조회**: tenant_id 필터 강제 (멀티테넌시 격리)

### 6. 현재 문제점
- **401/403 구분**: Spring Security (401) vs AdminGuardInterceptor (403) → 명확히 분리됨
- **에러 코드**: `ErrorCode`에 필요한 코드들이 이미 정의되어 있음
- **표준화 필요**: Interceptor 내부 로직이 복잡함 (Policy Registry + 기본 정책)

## 다음 단계 (PR-01B)
- AdminGuardService의 `requireAdmin()` 메서드로 단일 진입점 통일
- Tenant 검증 로직 명확화
- 에러 코드 표준화 (401/403 구분)
