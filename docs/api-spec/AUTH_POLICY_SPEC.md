# 인증 정책 및 Identity Provider 관리 스펙

## 개요

DWP Backend는 테넌트별 로그인 정책(LOCAL/SSO)을 Contract로 고정하여 프론트엔드가 로그인 UI를 자동 분기할 수 있도록 합니다.

## 핵심 설계 원칙

### 1. 프론트엔드 UI 자동 분기

프론트엔드는 `/api/auth/policy` API를 호출하여:
- 기본 로그인 타입 확인 (`defaultLoginType`)
- 허용된 로그인 타입 확인 (`allowedLoginTypes`)
- 로컬/SSO 로그인 활성화 여부 확인

이를 기반으로 로그인 UI를 자동으로 분기합니다.

### 2. 코드 기반 검증

로그인 타입은 하드코딩하지 않고 `LOGIN_TYPE` 코드 그룹을 통해 검증합니다:
- `LOCAL`: 로컬 DB 기반 인증
- `SSO`: Single Sign-On 인증

## 데이터베이스 스키마

### sys_auth_policies (인증 정책)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| auth_policy_id | BIGSERIAL | PK |
| tenant_id | BIGINT | 테넌트 ID (UNIQUE) |
| default_login_type | VARCHAR(30) | 기본 로그인 타입 (LOCAL/SSO) |
| allowed_login_types | VARCHAR(100) | 허용된 로그인 타입 (CSV: LOCAL,SSO) |
| sso_provider_key | VARCHAR(100) | 기본 SSO 제공자 키 (예: AZURE_AD) |
| local_login_enabled | BOOLEAN | 로컬 로그인 활성화 여부 |
| sso_login_enabled | BOOLEAN | SSO 로그인 활성화 여부 |
| require_mfa | BOOLEAN | MFA 필수 여부 |

**제약조건**: `UNIQUE(tenant_id)` (테넌트당 1개 정책)

### sys_identity_providers (Identity Provider)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| identity_provider_id | BIGSERIAL | PK |
| tenant_id | BIGINT | 테넌트 ID |
| provider_type | VARCHAR(20) | 제공자 타입 (OIDC/SAML) |
| provider_id | VARCHAR(100) | 제공자 ID (테넌트 범위 유니크) |
| provider_key | VARCHAR(100) | 제공자 키 (예: AZURE_AD) |
| name | VARCHAR(200) | 제공자명 |
| enabled | BOOLEAN | 활성화 여부 |
| auth_url | VARCHAR(500) | 인증 URL |
| token_url | VARCHAR(500) | 토큰 URL |
| metadata_url | VARCHAR(500) | 메타데이터 URL |
| jwks_url | VARCHAR(500) | JWKS URL |
| client_id | VARCHAR(255) | 클라이언트 ID |
| config_json | TEXT | 기타 설정 (JSON) |
| ext1~ext3 | VARCHAR(500) | 확장 필드 |

**제약조건**: `UNIQUE(tenant_id, provider_id)`

## API 명세

### 1. 로그인 정책 조회

**GET** `/api/auth/policy`

프론트엔드가 로그인 UI를 자동 분기하기 위해 사용합니다.

**Request**
```
GET /api/auth/policy
Headers:
  X-Tenant-ID: 1
```

**Response**
```json
{
  "success": true,
  "data": {
    "tenantId": 1,
    "defaultLoginType": "LOCAL",
    "allowedLoginTypes": ["LOCAL"],
    "localLoginEnabled": true,
    "ssoLoginEnabled": false,
    "ssoProviderKey": null,
    "requireMfa": false
  }
}
```

**정책이 없는 경우**: 기본 정책 반환 (LOCAL만 허용)

**SSO 활성화 예시**
```json
{
  "success": true,
  "data": {
    "tenantId": 1,
    "defaultLoginType": "SSO",
    "allowedLoginTypes": ["LOCAL", "SSO"],
    "localLoginEnabled": true,
    "ssoLoginEnabled": true,
    "ssoProviderKey": "AZURE_AD",
    "requireMfa": false
  }
}
```

### 2. Identity Provider 목록 조회

**GET** `/api/auth/idp`

활성화된 SSO Identity Provider 목록을 반환합니다.

**Request**
```
GET /api/auth/idp
Headers:
  X-Tenant-ID: 1
```

**Response**
```json
{
  "success": true,
  "data": [
    {
      "tenantId": 1,
      "enabled": true,
      "providerType": "OIDC",
      "providerKey": "AZURE_AD",
      "authUrl": "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
      "metadataUrl": "https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration",
      "clientId": "client-123",
      "ext": {
        "ext1": "...",
        "ext2": "..."
      }
    }
  ]
}
```

**활성화된 Provider가 없는 경우**: 빈 배열 반환

### 3. 특정 Provider Key 조회

**GET** `/api/auth/idp/{providerKey}`

특정 Provider Key의 Identity Provider 정보를 조회합니다.

**Request**
```
GET /api/auth/idp/AZURE_AD
Headers:
  X-Tenant-ID: 1
```

**Response**
```json
{
  "success": true,
  "data": {
    "tenantId": 1,
    "enabled": true,
    "providerType": "OIDC",
    "providerKey": "AZURE_AD",
    "authUrl": "...",
    "metadataUrl": "...",
    "clientId": "..."
  }
}
```

**Provider가 없는 경우**: 404 에러 반환

## 프론트엔드 사용 예시

### 로그인 UI 자동 분기

```typescript
// 1. 정책 조회
const policy = await fetch('/api/auth/policy', {
  headers: { 'X-Tenant-ID': tenantId }
}).then(r => r.json());

// 2. UI 분기
if (policy.data.localLoginEnabled && policy.data.ssoLoginEnabled) {
  // 로컬/SSO 선택 UI 표시
  showLoginTypeSelector();
} else if (policy.data.ssoLoginEnabled) {
  // SSO만 활성화: SSO 버튼만 표시
  showSSOButton();
} else {
  // 로컬만 활성화: 로컬 로그인 폼 표시
  showLocalLoginForm();
}

// 3. 기본 로그인 타입 설정
const defaultType = policy.data.defaultLoginType;
```

## Admin API 보호 강화

### 현재 구조

`/api/admin/**` 경로는 다음 방식으로 보호됩니다:

1. **JWT 인증 필수**: Spring Security에서 JWT 검증
2. **ADMIN 역할 검증**: `AdminGuardInterceptor`에서 역할 검증
   - `AdminGuardService.hasAdminRole()`: ADMIN 역할 확인
   - `CodeResolver`를 사용하여 하드코딩 제거

### 향후 확장 가능성

현재 구조는 확장 가능하도록 설계되었습니다:

- **Role 기반 검증**: `CodeResolver`를 통해 역할 코드 검증
- **Policy 기반 확장**: 향후 정책 기반 권한 검사로 확장 가능
- **동적 권한**: AuthService에서 내려주는 roleCode 기반으로 확장 가능

## Curl 예시

### 로그인 정책 조회

```bash
curl -X GET "http://localhost:8080/api/auth/policy" \
  -H "X-Tenant-ID: 1"
```

### Identity Provider 목록 조회

```bash
curl -X GET "http://localhost:8080/api/auth/idp" \
  -H "X-Tenant-ID: 1"
```

### 특정 Provider 조회

```bash
curl -X GET "http://localhost:8080/api/auth/idp/AZURE_AD" \
  -H "X-Tenant-ID: 1"
```

## 테스트

### 필수 테스트 케이스

1. **GET /api/auth/policy**
   - 정책이 있는 경우: 정책 반환 확인
   - 정책이 없는 경우: 기본 정책 반환 확인

2. **GET /api/auth/idp**
   - 활성화된 Provider가 있는 경우: 목록 반환 확인
   - 활성화된 Provider가 없는 경우: 빈 배열 반환 확인

3. **GET /api/auth/idp/{providerKey}**
   - Provider가 있는 경우: 정보 반환 확인
   - Provider가 없는 경우: 404 에러 확인

4. **Admin API 보호**
   - ADMIN 역할이 있는 경우: 접근 허용 확인
   - ADMIN 역할이 없는 경우: 403 에러 확인

## 관련 파일

- Entity: `AuthPolicy.java`, `IdentityProvider.java`
- Repository: `AuthPolicyRepository.java`, `IdentityProviderRepository.java`
- Service: `AuthPolicyService.java`, `IdentityProviderService.java`
- Controller: `AuthController.java`
- Migration: `V14__extend_auth_policies_and_idp.sql`, `V15__seed_auth_policy_and_idp.sql`
