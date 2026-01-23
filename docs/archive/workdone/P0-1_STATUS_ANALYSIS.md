# P0-1 현황 분석 결과

## 0) 현황 분석 (파일 근거)

### A. Auth Server(dwp-auth-server)

#### 로그인(토큰 발급) API
- **[NO] Login API**: `AuthController.java`에는 `/auth/health` 엔드포인트만 존재
  - 파일: `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/AuthController.java`
  - 현재: `@GetMapping("/health")`만 구현됨
  - 필요: `POST /auth/login` 엔드포인트 구현 필요

#### JWT 검증 필터/리소스서버 구성
- **[OK] JWT 검증**: `JwtConfig.java`에 JWT Decoder와 SecurityFilterChain 구성됨
  - 파일: `dwp-auth-server/src/main/java/com/dwp/services/auth/config/JwtConfig.java`
  - HS256 알고리즘 사용
  - OAuth2 Resource Server 구성됨
  - `/auth/health`, `/auth/info`는 permitAll, 나머지는 authenticated

#### tenant_id 클레임 검증
- **[OK] tenant_id 클레임**: 문서상으로는 필수 클레임으로 명시됨
  - 파일: `docs/AURA_PLATFORM_INTEGRATION_GUIDE.md` (라인 89-99)
  - JWT Payload 구조에 `tenant_id` 필수로 명시
  - 코드상 명시적 검증은 없음 (JWT 검증만 수행)

#### userId를 어디서 얻는지
- **[OK] userId**: JWT의 `sub` 클레임 사용
  - 파일: `docs/FRONTEND_INTEGRATION_GUIDE.md` (라인 43-60)
  - JWT의 `sub` 클레임을 `X-User-ID` 헤더로 매핑하는 규칙 명시

### B. ApiResponse / ErrorCode / GlobalExceptionHandler (dwp-core)

#### ApiResponse<T> 필드 구조
- **[OK] ApiResponse 구조**: `ApiResponse.java`에 정의됨
  - 파일: `dwp-core/src/main/java/com/dwp/core/common/ApiResponse.java`
  - 필드: `status`, `message`, `data`, `errorCode`, `timestamp`, `success`, `agentMetadata`
  - 정적 메서드: `success()`, `error()` 제공

#### ErrorCode enum/에러 매핑
- **[OK] ErrorCode**: `ErrorCode.java`에 정의됨
  - 파일: `dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java`
  - 인증/인가 에러: `UNAUTHORIZED(E2000)`, `FORBIDDEN(E2001)`, `TOKEN_EXPIRED(E2002)`, `TOKEN_INVALID(E2003)`
  - **[NO] AUTH_INVALID_CREDENTIALS**: 로그인 실패용 에러코드 없음 (추가 필요)

#### 401/403 발생 시 응답
- **[PARTIAL] 401/403 ApiResponse wrapping**: 
  - `GlobalExceptionHandler`는 `BaseException`만 처리
  - 파일: `dwp-core/src/main/java/com/dwp/core/exception/GlobalExceptionHandler.java`
  - Spring Security의 `AuthenticationException`, `AccessDeniedException`은 별도 처리 필요
  - 현재: Spring Security 예외는 기본 응답 형식으로 반환됨 (ApiResponse 래핑 안 됨)

### C. Gateway(dwp-gateway)

#### /api/auth/** 라우팅
- **[OK] Gateway routing**: `application.yml`에 라우팅 설정됨
  - 파일: `dwp-gateway/src/main/resources/application.yml` (라인 57-62)
  - 라우팅: `/api/auth/**` → `http://localhost:8001` (StripPrefix=1 적용)

#### CORS 설정 허용 헤더
- **[PARTIAL] CORS allowed headers**: 
  - 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java`
  - 현재: `allowedHeaders = "*"` (모든 헤더 허용)
  - 필요: 명시적 헤더 목록 추가 권장 (Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, X-DWP-Source, X-DWP-Caller-Type)

#### HeaderPropagationFilter 헤더 전파
- **[PARTIAL] Header propagation**: 
  - 파일: `dwp-gateway/src/main/java/com/dwp/gateway/config/HeaderPropagationFilter.java`
  - 전파 중: Authorization, X-Tenant-ID, X-DWP-Source, X-DWP-Caller-Type, X-User-ID, Last-Event-ID
  - **[NO] X-Agent-ID**: 전파 목록에 없음 (추가 필요)

---

## 현황 분석 요약

- **[NO] Login API**: 구현 필요
- **[OK] JWT claims(tenant_id, sub/userId, iat/exp)**: 문서상 명시, 코드상 검증 로직은 간접적
- **[PARTIAL] 401/403 ApiResponse wrapping**: Spring Security 예외는 별도 처리 필요
- **[OK] Gateway routing /api/auth/**: 라우팅 설정됨
- **[PARTIAL] Gateway CORS allowed headers**: "*"로 설정되어 있으나 명시적 목록 권장
- **[PARTIAL] Header propagation for standard headers**: X-Agent-ID 누락

---

## 작업 우선순위

1. **P0 (필수)**: Login API 구현 + JWT 발급
2. **P0 (필수)**: Spring Security 401/403 → ApiResponse 변환
3. **P0 (필수)**: ErrorCode에 AUTH_INVALID_CREDENTIALS 추가
4. **P0 (필수)**: CORS 명시적 헤더 목록 추가
5. **P0 (필수)**: HeaderPropagationFilter에 X-Agent-ID 추가
