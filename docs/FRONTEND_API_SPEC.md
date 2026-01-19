# DWP Frontend IAM API Spec (v1.0)

이 문서는 DWP 프론트엔드에서 사용하는 인증 및 권한 관련 API 명세를 정의합니다.

## 1. 개요
- **Base URL**: `http://localhost:8080/api/auth` (Gateway 경유)
- **공통 응답**: `ApiResponse<T>` 형식
- **인증 방식**: JWT (Bearer Token)

## 2. API 목록

### 2.1 로그인 (LOCAL)
사용자 아이디와 비밀번호로 로그인을 시도하고 JWT 토큰을 발급받습니다.

- **URL**: `POST /login`
- **Headers**:
  - `X-Tenant-ID`: 테넌트 식별자 (예: `dev`)
- **Body**:
```json
{
  "username": "admin",
  "password": "admin1234!",
  "tenantId": "dev"
}
```
- **Response**: `ApiResponse<LoginResponse>`
```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJhbG...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "userId": "1",
    "tenantId": "1"
  }
}
```

### 2.2 내 정보 조회
토큰 기반으로 현재 로그인한 사용자의 프로필 정보를 조회합니다.

- **URL**: `GET /me`
- **Headers**:
  - `Authorization`: `Bearer <JWT>`
- **Response**: `ApiResponse<MeResponse>`
```json
{
  "status": "SUCCESS",
  "data": {
    "userId": 1,
    "displayName": "Admin User",
    "email": "admin@dev.local",
    "tenantId": 1,
    "tenantCode": "dev",
    "roles": ["ADMIN"]
  }
}
```

### 2.3 내 권한 목록 조회
토큰 기반으로 현재 사용자가 가진 리소스별 권한 목록을 조회합니다. 프론트엔드 메뉴/버튼 제어에 사용됩니다.

- **URL**: `GET /permissions`
- **Headers**:
  - `Authorization`: `Bearer <JWT>`
- **Response**: `ApiResponse<List<PermissionDTO>>`
```json
{
  "status": "SUCCESS",
  "data": [
    {
      "resourceType": "MENU",
      "resourceKey": "menu.dashboard",
      "resourceName": "Dashboard",
      "permissionCode": "VIEW",
      "permissionName": "조회",
      "effect": "ALLOW"
    },
    {
      "resourceType": "UI_COMPONENT",
      "resourceKey": "btn.mail.send",
      "resourceName": "Send Button",
      "permissionCode": "USE",
      "permissionName": "사용",
      "effect": "ALLOW"
    }
  ]
}
```

## 3. 테스트 계정 (Seed Data)
| 구분 | 아이디 | 비밀번호 | 테넌트 ID | 역할 |
|---|---|---|---|---|
| 관리자 | admin | admin1234! | dev (1) | ADMIN |

## 4. curl 예시

```bash
# 1. 로그인
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: dev" \
  -d '{"username": "admin", "password": "admin1234!", "tenantId": "dev"}'

# 2. 내 정보 조회 (TOKEN에 위에서 받은 accessToken 대입)
curl -X GET http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"

# 3. 권한 조회
curl -X GET http://localhost:8080/api/auth/permissions \
  -H "Authorization: Bearer $TOKEN"
```
