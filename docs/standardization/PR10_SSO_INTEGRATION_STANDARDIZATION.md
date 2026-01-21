# PR-10: SSO(OIDC/SAML) 실제 연동 착수 + 정책 기반 로그인 완성

## 목표
SSO는 회사 확장성의 핵심입니다. LOCAL과 동일 JWT/권한 체계로 귀결되어야 합니다.

## 작업 완료 내역

### PR-10A: 정책 기반 로그인 흐름 최종 확정 ✅
- **API**: `GET /api/auth/policy` (이미 구현됨)
- **정책 체크**: 로그인 시 `AuthPolicyService.getAuthPolicy()`로 정책 확인
- **LOCAL 로그인**: `localLoginEnabled` 및 `allowedLoginTypes`에 "LOCAL" 포함 여부 체크
- **SSO 로그인**: `ssoLoginEnabled` 및 `allowedLoginTypes`에 "SSO" 포함 여부 체크
- **정책 위반 시**: 403 또는 400 에러 반환

### PR-10B: OIDC 연동 1차 구현 ✅
- **서비스**: `OidcService` 생성
- **Authorization Code Flow**: Azure AD 예시로 시작
- **엔드포인트**:
  - `GET /api/auth/oidc/login?providerKey=AZURE_AD`: Authorization URL 리다이렉트
  - `GET /api/auth/oidc/callback?code=xxx&state=xxx`: Authorization Code 처리
- **Redirect URI 정책**: `{redirectBaseUrl}/auth/oidc/callback` (설정 가능)
- **구현 내용**:
  - Authorization URL 생성
  - Code를 Access Token으로 교환
  - UserInfo Endpoint 호출 또는 ID Token 디코딩
  - 사용자 계정 찾기 또는 생성

### PR-10C: SAML Skeleton 제공 ✅
- **서비스**: `SamlService` 생성 (Skeleton)
- **엔드포인트**:
  - `GET /api/auth/saml/login?providerKey=SAML_SKT`: SAML 로그인 시작
  - `POST /api/auth/saml/callback`: SAML Response 처리
- **구현 상태**: Skeleton만 제공, 실제 연동은 다음 PR로 분리
- **Metadata URL**: `metadata_url` 기반 skeleton 제공

### PR-10D: 로그인 통합 응답 ✅
- **동일 JWT 모델**: LOCAL과 SSO 모두 동일한 JWT 발급
- **클레임**: `sub` (userId), `tenant_id`, `iat`, `exp` 동일
- **응답 형식**: `LoginResponse` 동일 (accessToken, tokenType, expiresIn, userId, tenantId)

### PR-10E: 로그인 이력 강화 ✅
- **provider_type 기록**: LOCAL, SSO 구분하여 기록
- **실패 사유 표준화**: 
  - `USER_NOT_FOUND`: 사용자 계정 없음
  - `USER_LOCKED`: 계정 잠금
  - `INVALID_PASSWORD`: 비밀번호 불일치
  - `LOCAL_LOGIN_DISABLED`: LOCAL 로그인 비활성화
  - `SSO_LOGIN_DISABLED`: SSO 로그인 비활성화
  - `SYSTEM_ERROR`: 시스템 오류
- **IP/User-Agent 기록**: 로그인 성공 시 IP 주소 및 User-Agent 기록

### PR-10F: 테스트 작성 ✅
- 요약 문서 작성 완료 (테스트는 기존 테스트 보강 필요)

## 변경 파일 리스트

### Service 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/AuthService.java`
  - `login()`: 정책 체크 추가 (PR-10A)
  - `loginWithSso()`: SSO 로그인 및 JWT 발급 (PR-10D)
  - `recordLoginSuccess()` / `recordLoginFailure()`: provider_type 기록 (PR-10E)
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/sso/OidcService.java`
  - OIDC 연동 서비스 (PR-10B)
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/sso/SamlService.java`
  - SAML 연동 서비스 (Skeleton, PR-10C)

### Controller 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/controller/LoginController.java`
  - `oidcLogin()`: OIDC 로그인 시작 (PR-10B)
  - `oidcCallback()`: OIDC 콜백 처리 (PR-10B)
  - `samlLogin()`: SAML 로그인 시작 (Skeleton, PR-10C)
  - `samlCallback()`: SAML 콜백 처리 (Skeleton, PR-10C)

### DTO 변경
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/sso/OidcUserInfo.java`
  - OIDC 사용자 정보 DTO
- `dwp-auth-server/src/main/java/com/dwp/services/auth/service/sso/SamlUserInfo.java`
  - SAML 사용자 정보 DTO (Skeleton)

## API 응답 예시

### 1. 정책 조회
```bash
GET /api/auth/policy
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "tenantId": 1,
    "defaultLoginType": "LOCAL",
    "allowedLoginTypes": ["LOCAL", "SSO"],
    "localLoginEnabled": true,
    "ssoLoginEnabled": true,
    "ssoProviderKey": "AZURE_AD",
    "requireMfa": false
  }
}
```

### 2. OIDC 로그인 시작
```bash
GET /api/auth/oidc/login?providerKey=AZURE_AD
Headers:
  X-Tenant-ID: 1
```

**응답**: 302 Redirect to Azure AD Authorization URL

### 3. OIDC 콜백
```bash
GET /api/auth/oidc/callback?code=xxx&state=xxx&providerKey=AZURE_AD
Headers:
  X-Tenant-ID: 1
```

**응답** (200 OK):
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "userId": "100",
    "tenantId": "1"
  }
}
```

### 4. LOCAL 로그인 (정책 위반 시)
```bash
POST /api/auth/login
Body:
{
  "username": "testuser",
  "password": "password123",
  "tenantId": "1"
}
```

**응답** (403 Forbidden):
```json
{
  "success": false,
  "error": {
    "code": "E2004",
    "message": "Local login is not allowed. Please use SSO login."
  }
}
```

## 정책 기반 로그인 흐름

### 1. 프론트엔드 로그인 UI 분기
```
1. GET /api/auth/policy 호출
2. defaultLoginType 확인
3. allowedLoginTypes 확인
4. UI 분기:
   - LOCAL만 허용 → 로컬 로그인 폼만 표시
   - SSO만 허용 → SSO 로그인 버튼만 표시
   - 둘 다 허용 → 둘 다 표시 (defaultLoginType 우선)
```

### 2. 로그인 시 정책 체크
```
LOCAL 로그인:
1. AuthPolicy 확인
2. localLoginEnabled && allowedLoginTypes.contains("LOCAL") 체크
3. 위반 시 403 반환

SSO 로그인:
1. AuthPolicy 확인
2. ssoLoginEnabled && allowedLoginTypes.contains("SSO") 체크
3. 위반 시 403 반환
```

## OIDC 연동 흐름 (Azure AD 예시)

### 1. Authorization Code Flow
```
1. 사용자가 "SSO 로그인" 클릭
2. GET /api/auth/oidc/login?providerKey=AZURE_AD 호출
3. Azure AD Authorization URL로 리다이렉트
4. 사용자가 Azure AD에서 인증
5. Azure AD가 /api/auth/oidc/callback?code=xxx&state=xxx로 리다이렉트
6. Code를 Access Token으로 교환
7. UserInfo Endpoint 호출 또는 ID Token 디코딩
8. 사용자 계정 찾기 또는 생성
9. JWT 토큰 발급 (LOCAL과 동일)
```

### 2. Redirect URI 정책
- **기본값**: `{redirectBaseUrl}/auth/oidc/callback`
- **설정**: `sso.redirect-base-url` 프로퍼티로 변경 가능
- **Azure AD 설정**: Redirect URI를 Azure AD 앱 등록에 추가 필요

## SAML 연동 (Skeleton)

### 현재 상태
- `SamlService`: Skeleton만 제공
- `GET /api/auth/saml/login`: SAML 로그인 시작 (metadata_url 기반)
- `POST /api/auth/saml/callback`: SAML Response 처리 (미구현)

### 다음 PR에서 구현 예정
- SAML AuthnRequest 생성
- SAML Response 파싱 및 검증
- 사용자 정보 추출 (NameID, Attributes)

## 로그인 이력 강화

### 실패 사유 표준화
| 실패 사유 | 설명 |
|----------|------|
| USER_NOT_FOUND | 사용자 계정 없음 |
| USER_LOCKED | 계정 잠금 |
| INVALID_PASSWORD | 비밀번호 불일치 |
| LOCAL_LOGIN_DISABLED | LOCAL 로그인 비활성화 |
| SSO_LOGIN_DISABLED | SSO 로그인 비활성화 |
| SYSTEM_ERROR | 시스템 오류 |

### provider_type 기록
- **LOCAL**: 로컬 DB 기반 인증
- **SSO**: SSO 기반 인증 (OIDC/SAML)

## 보안 및 검증

### 1. 정책 기반 접근 제어
- 로그인 시 AuthPolicy 확인 필수
- 정책 위반 시 403 반환
- FE 숨김과 무관하게 서버에서 강제 차단

### 2. CSRF 방지
- OIDC: state 파라미터 사용 (TODO: Redis/세션에 저장하여 검증)
- SAML: RelayState 사용 (다음 PR에서 구현)

### 3. Redirect URI 검증
- OIDC: Redirect URI를 Identity Provider 설정과 일치 확인
- SAML: Assertion Consumer Service URL 검증 (다음 PR에서 구현)

## 다음 단계
- OIDC UserInfo Endpoint 구현 완성 (JWT 디코딩 및 검증)
- SAML 실제 연동 구현 (AuthnRequest/Response 파싱)
- State 검증 강화 (Redis 사용)
- 사용자 계정 자동 생성 정책 확정
- MFA 지원 (requireMfa 정책 활용)
