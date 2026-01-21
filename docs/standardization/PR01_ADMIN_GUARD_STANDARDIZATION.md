# PR-01: Admin API 권한 Enforcement 표준화

## 목표
Admin API 권한 enforcement의 단일 표준을 만들고, 401/403 정책을 고정하며, 테스트로 검증합니다.

## 작업 완료 내역

### PR-01A: 현재 보안 흐름 점검 ✅
- **문서**: `docs/PR01_ADMIN_GUARD_SUMMARY.md`
- **요약**:
  - `/api/admin/**`는 `AdminGuardInterceptor`로 보호됨
  - Spring Security JWT 필터가 먼저 인증 처리 (401)
  - AdminGuardInterceptor가 권한 검증 처리 (403)
  - ADMIN 판별은 CodeResolver 기반 (하드코딩 제거됨)
  - Tenant 격리는 JWT `tenant_id` + `X-Tenant-ID` 헤더 일치 확인

### PR-01B: AdminGuard Enforcement 표준화 ✅
- **변경 파일**:
  - `AdminGuardService.java`: `requireAdmin(tenantId, userId)` 메서드 추가
  - `AdminGuardInterceptor.java`: 표준화된 검증 흐름 적용
- **표준화 내용**:
  1. **인증 (401)**: Spring Security JWT 필터에서 처리
  2. **권한 (403)**: AdminGuardInterceptor에서 `requireAdmin()` 호출
  3. **Tenant 격리**: JWT `tenant_id` + `X-Tenant-ID` 헤더 불일치 시 `TENANT_MISMATCH` (403)
  4. **ADMIN 판별**: CodeResolver 기반 (`ROLE_CODE` 그룹에서 "ADMIN" 조회)

### PR-01C: 공통 예외/응답 규격 통일 ✅
- **변경 파일**:
  - `ErrorCode.java`: `ADMIN_FORBIDDEN` 추가 (E2008)
- **에러 코드 표준**:
  - **401 Unauthorized**: `UNAUTHORIZED`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `AUTH_REQUIRED`
  - **403 Forbidden**: `FORBIDDEN`, `ADMIN_FORBIDDEN`, `TENANT_MISMATCH`
- **응답 형식**: 모든 응답은 `ApiResponse<T>` 형식 유지

### PR-01D: 테스트 작성 ✅
- **테스트 파일**: 
  - `AdminMonitoringControllerSecurityTest.java` (Controller 레벨 테스트)
  - `AdminGuardInterceptorTest.java` (기존 Interceptor 레벨 테스트 - 이미 검증 완료)
- **검증 항목**:
  1. ✅ 토큰 없음 → 401 (AdminGuardInterceptorTest에서 검증)
  2. ✅ admin 아닌 토큰 → 403 (AdminGuardInterceptorTest에서 검증)
  3. ✅ admin 토큰 + tenant 일치 → 200 (AdminGuardInterceptorTest에서 검증)
  4. ✅ admin 토큰 + tenant 불일치 → 403 (AdminGuardInterceptorTest에서 검증)
- **참고**: `AdminGuardInterceptorTest`가 이미 Interceptor 레벨의 모든 보안 검증을 완료하고 있습니다.

## 변경 파일 리스트

### Core 변경
- `dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java`
  - `ADMIN_FORBIDDEN` 추가 (E2008)

### Auth Server 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/rbac/AdminGuardService.java`
  - `requireAdmin(tenantId, userId)` 메서드 추가
  - `requireAdminRole()`에서 `ADMIN_FORBIDDEN` 사용

- `dwp-auth-server/src/main/java/com/dwp/services/auth/config/AdminGuardInterceptor.java`
  - 표준화된 검증 흐름 적용 (주석 추가)
  - `requireAdmin()` 사용

- `dwp-auth-server/src/test/java/com/dwp/services/auth/controller/admin/monitoring/AdminMonitoringControllerSecurityTest.java`
  - PR-01D 테스트 추가

### 문서
- `docs/PR01_ADMIN_GUARD_SUMMARY.md`: 현재 보안 흐름 점검 결과
- `docs/PR01_ADMIN_GUARD_STANDARDIZATION.md`: 본 문서

## API 응답 예시

### 1. 토큰 없음 → 401
```bash
curl -X GET http://localhost:8001/api/admin/monitoring/summary \
  -H "X-Tenant-ID: 1"
```

**응답**:
```json
{
  "success": false,
  "errorCode": "E2000",
  "message": "인증이 필요합니다."
}
```

### 2. admin 아닌 토큰 → 403
```bash
curl -X GET http://localhost:8001/api/admin/monitoring/summary \
  -H "Authorization: Bearer <일반사용자토큰>" \
  -H "X-Tenant-ID: 1"
```

**응답**:
```json
{
  "success": false,
  "errorCode": "E2008",
  "message": "관리자 권한이 필요합니다."
}
```

### 3. admin 토큰 + tenant 일치 → 200
```bash
curl -X GET http://localhost:8001/api/admin/monitoring/summary \
  -H "Authorization: Bearer <admin토큰>" \
  -H "X-Tenant-ID: 1"
```

**응답**:
```json
{
  "success": true,
  "data": {
    "pv": 1000,
    "uv": 500,
    "events": 200,
    "apiErrorRate": 0.01,
    "pvDeltaPercent": 5.0,
    "uvDeltaPercent": 3.0,
    "eventDeltaPercent": 2.0,
    "apiErrorDeltaPercent": -1.0
  }
}
```

## 보안 정책 요약

### 1. 인증 (401)
- **처리 위치**: Spring Security JWT Filter
- **조건**: JWT 토큰 없음 또는 유효하지 않음
- **에러 코드**: `UNAUTHORIZED`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `AUTH_REQUIRED`

### 2. 권한 (403)
- **처리 위치**: AdminGuardInterceptor
- **조건**: 
  - ADMIN 권한 없음 → `ADMIN_FORBIDDEN`
  - Tenant 불일치 → `TENANT_MISMATCH`
  - 일반 권한 부족 → `FORBIDDEN`

### 3. Tenant 격리
- **JWT 클레임**: `tenant_id` 필수
- **헤더**: `X-Tenant-ID` 권장 (불일치 시 403)
- **정책**: JWT `tenant_id`와 헤더 `X-Tenant-ID` 불일치 시 `TENANT_MISMATCH` (403)

### 4. ADMIN 판별
- **방식**: CodeResolver 기반 (`ROLE_CODE` 그룹에서 "ADMIN" 조회)
- **하드코딩 금지**: `"ADMIN".equals(roleCode)` 같은 하드코딩 사용 금지
- **검증**: `codeResolver.require("ROLE_CODE", adminCode)` 호출

## 다음 단계
- 모든 Admin CRUD API에 동일한 보안 정책 적용
- Policy Registry에 누락된 엔드포인트 추가
- 통합 테스트 보강 (실제 JWT 토큰 사용)
