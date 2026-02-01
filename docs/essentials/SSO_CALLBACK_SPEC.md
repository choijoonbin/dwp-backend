# SSO 콜백 규격 (OIDC)

**작성일:** 2025-01-29  
**근거 코드:** dwp-auth-server `LoginController.java`, `OidcService.java`

---

## 1. 콜백 URL 및 라우팅

| 항목 | 값 | 코드 경로 |
|------|-----|-----------|
| **IdP에 등록되는 redirect_uri** | `{redirectBaseUrl}/auth/oidc/callback` | `OidcService.java` L83 `redirectUri = redirectBaseUrl + "/auth/oidc/callback"` |
| **redirectBaseUrl** | 환경 변수 `sso.redirect-base-url`, 기본값 `http://localhost:3000` | `OidcService.java` L50 `@Value("${sso.redirect-base-url:http://localhost:3000}")` |
| **결론** | IdP가 브라우저를 리다이렉트하는 최종 URL = **FE URL** = `{FE_ORIGIN}/auth/oidc/callback?code=...&state=...` | FE는 **라우트 `/auth/oidc/callback`** 을 반드시 제공해야 함. `/sso-callback` 이 아닌 **`/auth/oidc/callback`** 사용 권장. |

FE가 `/sso-callback`만 사용하는 경우:

- **옵션 A:** FE 라우트를 `/auth/oidc/callback`으로 통일.
- **옵션 B:** BE `sso.redirect-base-url`을 FE 기준으로 두고, IdP에는 `{FE_ORIGIN}/sso-callback`을 등록. 그때 FE는 `/sso-callback` 페이지에서 쿼리(`code`, `state`)를 읽고 **BE GET /api/auth/oidc/callback?code=...&state=...&tenantId=...** 를 호출하면 됨.

---

## 2. IdP → FE 리다이렉트 (쿼리 파라미터)

IdP가 브라우저를 `{FE_ORIGIN}/auth/oidc/callback`으로 보낼 때 붙는 쿼리:

| 파라미터 | 필수 | 설명 |
|----------|------|------|
| `code` | O | Authorization Code (토큰 교환용) |
| `state` | O | CSRF 방지용 state (BE에서 생성·검증 권장) |
| `providerKey` | X | 없을 수 있음. BE에서 없으면 "AZURE_AD" 등 기본값 사용 |

---

## 3. FE → BE 호출 (OIDC 콜백 처리)

FE의 `/auth/oidc/callback` 페이지에서:

1. 쿼리에서 `code`, `state`, (선택) `providerKey` 읽기.
2. **BE GET** `/api/auth/oidc/callback?code=...&state=...&providerKey=...` 호출.
3. **헤더:** 리다이렉트 직후에는 `X-Tenant-ID` 헤더가 없을 수 있으므로, BE는 **쿼리 파라미터 `tenantId`** 도 허용. (`LoginController.java` L86~99)

| 항목 | 값 | 코드 경로 |
|------|-----|-----------|
| **BE 엔드포인트** | GET `/api/auth/oidc/callback` | `LoginController.java` L75~76 |
| **요청 파라미터** | `code` (필수), `state` (필수), `providerKey` (선택) | L78~81 |
| **테넌트** | `X-Tenant-ID` 헤더 또는 쿼리 `tenantId` (필수) | L77, L86~99 |
| **응답** | **JSON** `ApiResponse<LoginResponse>` (JWT 등). **리다이렉트 없음.** | L77, L114 |
| **토큰 발급** | `oidcService.exchangeCodeForUserInfo` → `authService.loginWithSso` → LoginResponse 반환 | L109~114 |

FE 동작:

- BE 호출 후 응답에서 JWT·사용자 정보 저장.
- **FE 라우터로** 대시보드 등 목적지 이동 (BE가 리다이렉트하지 않음).

---

## 4. SAML

| 항목 | 값 |
|------|-----|
| **시작** | GET `/api/auth/saml/login?providerKey=...` (X-Tenant-ID 헤더 필요) |
| **콜백** | POST `/api/auth/saml/callback` — **스켈레톤만**, 현재 500 "SAML 연동은 아직 구현되지 않았습니다" |
| **코드** | `LoginController.java` L139~156 |

SAML 사용 시에는 추후 구현 PR에서 콜백 URL·메서드·파라미터 규격을 확정할 것.
