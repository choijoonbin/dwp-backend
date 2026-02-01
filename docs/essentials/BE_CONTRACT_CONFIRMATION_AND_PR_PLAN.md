# BE 계약 확정 리포트 및 PR 작업 계획

**작성일:** 2025-01-29  
**기준 문서:** BE_FE_CONTRACT_INSPECTION_REPORT.md, BE_FE_CONTRACT_UPDATE_result.md

---

## 1. 확정 리포트

### 1.1 SSO 콜백 — 실제 코드 경로/흐름 확정

| 구분 | 내용 | 코드 경로(파일:라인) |
|------|------|----------------------|
| **콜백 URL** | IdP에 등록되는 redirect_uri = `redirectBaseUrl` + `/auth/oidc/callback` | `OidcService.java` L50 `redirectBaseUrl`, L83 `redirectUri = redirectBaseUrl + "/auth/oidc/callback"` |
| **redirectBaseUrl** | 환경 변수 `sso.redirect-base-url`, 기본값 `http://localhost:3000` | `OidcService.java` L50 `@Value("${sso.redirect-base-url:http://localhost:3000}")` |
| **결론** | IdP가 리다이렉트하는 최종 URL은 **FE URL** = `{FE_ORIGIN}/auth/oidc/callback?code=...&state=...` | FE는 반드시 **라우트 `/auth/oidc/callback`** 을 제공해야 함. `/sso-callback` 이 아닌 `/auth/oidc/callback` |
| **BE OIDC 콜백 엔드포인트** | GET `/api/auth/oidc/callback` — Code를 받아 토큰 교환 후 JWT 발급, **JSON 응답** (리다이렉트 아님) | `LoginController.java` L75~76 `@GetMapping("/oidc/callback")`, L77 `ApiResponse<LoginResponse>` |
| **요청 파라미터** | `code` (필수), `state` (필수), `providerKey` (선택, 없으면 "AZURE_AD") | L78~81 `@RequestParam("code")`, `state`, `providerKey` |
| **X-Tenant-ID** | 리다이렉트 후에는 헤더 없음 → 쿼리 `tenantId` 허용 | L77 `@RequestHeader(value = "X-Tenant-ID", required = false)`, L86~99 tenantId 없으면 `request.getParameter("tenantId")` |
| **토큰 발급** | `oidcService.exchangeCodeForUserInfo` → `authService.loginWithSso` → **LoginResponse(JWT 등)** 반환 | L109~114 |
| **FE로의 “리다이렉트”** | BE는 **리다이렉트하지 않음**. JSON `ApiResponse<LoginResponse>` 반환. FE가 `/auth/oidc/callback` 페이지에서 code를 읽고 **BE GET /api/auth/oidc/callback?code=...&state=...&providerKey=...** 호출 후 JWT 저장하고 **FE 라우터로** 대시보드 이동 | - |
| **SAML** | POST `/api/auth/saml/callback` — **스켈레톤만**, 현재 500 "SAML 연동은 아직 구현되지 않았습니다" | `LoginController.java` L139~156 |

**결론 및 리스크**

- **FE 라우팅:** FE는 **`/auth/oidc/callback`** 경로를 사용해야 IdP redirect_uri와 일치. `/sso-callback` 사용 시 불일치 → **수정안:** FE 라우트를 `/auth/oidc/callback`으로 통일하거나, BE에서 `redirectBaseUrl`을 FE 기준으로 두고 IdP에는 `{FE_ORIGIN}/auth/oidc/callback`만 등록하도록 문서화.
- **쿼리 파라미터 규격:**  
  - IdP → FE: `code`, `state`, (선택) `providerKey`.  
  - FE → BE: `code`, `state`, `providerKey`(선택), (헤더 없을 때) `tenantId` 쿼리.  
- **불일치 시 수정안:** FE가 `/sso-callback`만 쓸 경우, BE `redirectBaseUrl`을 `.../sso-callback`으로 두면 IdP가 FE `/sso-callback`으로 보냄. 그때 FE는 BE를 **GET /api/auth/oidc/callback?code=...&state=...&tenantId=...** 로 호출하면 됨. 즉 **IdP redirect 경로 = FE 라우트**, **BE 경로 = /api/auth/oidc/callback** 고정.

---

### 1.2 HITL approve/reject 멱등성 — 현 코드 및 결론

| 구분 | 내용 | 코드 경로 |
|------|------|------------|
| **현재 동작** | `approve`/`reject` 호출 시 **항상** Redis 업데이트 + Pub/Sub 신호 발행. 이미 approved/rejected인지 확인 없음 | `HitlManager.java` L162~224 (approve), L234~298 (reject) |
| **리스크** | 동일 requestId로 재요청 시 중복 신호 → 에이전트 측 이중 처리 가능. 중복 클릭/재시도 시 비멱등 | - |
| **결론** | **미충족.** 이미 approved/rejected면 Redis 갱신·신호 재발행 없이 **200 + 동일 응답 본문** 반환(완전 멱등) 필요. |

**레이스 방지 방안**

- **권장:** Redis **Lua 스크립트**로 `hitl:request:{requestId}` 값을 읽고, `status == "pending"`일 때만 `approved`/`rejected`로 갱신하고 1 반환, 아니면 0 반환. 앱에서는 0이면 기존 sessionId/status로 200 반환, 1이면 신호 1회만 발행.
- **대안:** 앱에서 먼저 GET으로 status 확인 후 pending일 때만 업데이트. 동시 두 요청이 모두 pending을 읽을 수 있으므로, **Redis 분산 락**(`hitl:lock:{requestId}`)으로 직렬화 후 다시 읽어서 pending이면 처리.  
- **구현 포인트:** Lua로 원자적 상태 전이; 실패 시(이미 처리됨) 기존 sessionId/status 반환.

---

### 1.3 HITL X-Tenant-ID 검증 — 현 코드 및 결론

| 구분 | 내용 | 코드 경로 |
|------|------|------------|
| **Gateway** | `RequiredHeaderFilter`의 제외 경로에 `/api/aura/hitl/**` **없음** → HITL 요청은 **X-Tenant-ID 필수**. 없으면 Gateway가 400 | `RequiredHeaderFilter.java` L39~46 `EXCLUDED_PATHS`, L59~64 검증 |
| **main-service** | `HitlController`/`HitlManager`에서 **X-Tenant-ID와 Redis tenantId 비교 없음** | `HitlController.java` L44~58 (approve), L68~86 (reject) — 헤더 미사용. `HitlManager.java` L74 `tenantId` 저장만 |
| **결론** | Gateway는 이미 방어. **방어적 프로그래밍으로 main-service에서 tenant 불일치 시 403** 필요. |
| **정책** | 헤더 없음/형식 오류 → Gateway에서 400 (이미 적용). main-service 도달 시에는 헤더 존재. **Redis 저장 tenantId와 불일치 시 403 TENANT_MISMATCH.** |

---

### 1.4 HITL 감사 로그 — 저장소 전략 결정

| 구분 | 내용 | 코드 경로 |
|------|------|------------|
| **현재** | `saveApprovalRequest`, `approve`, `reject`에서 **감사 로그 없음** | `HitlManager.java` 전역 |
| **auth-server 감사** | `AuditLogService.recordAuditLog(tenantId, actorUserId, action, resourceType, resourceId, before, after, request)` 등. `com_audit_logs` 테이블 | `AuditLogService.java` L43~127, `AuditLog.java` L11 `com_audit_logs` |
| **결론** | **B안 채택:** main-service는 **auth-server 감사 API**를 호출해 기록. 이유: com_audit_logs 단일 소유(auth-server), main-service가 auth DB 직접 접근하지 않음, 네트워크 의존은 내부 통신으로 수용. |

**최소 필드**

- tenantId, actorUserId(userId), requestId, sessionId, action(REQUEST/APPROVE/REJECT), timestamp, payload summary(PII 마스킹).  
- resourceType = "HITL", resourceId = null 또는 requestId 해시값. metadata_json에 requestId, sessionId, action, context 요약(마스킹).

---

### 1.5 SSE 이벤트 경계 — 현 코드 및 결론

| 구분 | 내용 | 코드 경로 |
|------|------|------------|
| **현재 처리** | `Flux.from(body).map(dataBuffer -> ... addEventIdIfNeeded(content) ...).then()` | `SseReconnectionFilter.java` L67~84 |
| **문제 1** | **`.then()`은 구독 완료만 하고 변환된 데이터를 응답에 쓰지 않음.** `originalResponse.writeWith(modifiedFlux)`가 아니므로 **클라이언트가 빈 본문을 받을 가능성** 있음. | L84 `then()` |
| **문제 2** | `addEventIdIfNeeded`는 `content.split("\n\n")`으로 이벤트 분할. 한 청크가 `data: {...}\n` 까지만 오고 다음 청크에 `\ndata: ...` 가 오면 **이벤트 경계가 깨짐**. | L105~124 |
| **결론** | (1) **버그:** writeWith가 변환된 Flux를 실제로 write 하도록 수정 필요. (2) **경계:** 청크가 이벤트 중간에서 끊기면 잘못된 id 부여/이벤트 분할 가능. |
| **재현 계획** | 큰 payload 한 이벤트 + 작은 버퍼 크기로 다운스트림 응답, 또는 프록시 버퍼 비활성화 후 연속 이벤트 전송으로 청크 분할 재현. |
| **개선안 A** | 줄 단위 누적 버퍼: `\n` 단위로 누적 후 `\n\n` 등장 시 한 이벤트로 처리해 id 부여, 그 다음 이벤트로 전달. 청크 경계에 강함. |
| **개선안 B (단순화)** | **Aura-Platform이 모든 SSE 이벤트에 `id:` 라인을 포함**해 내려주면, Gateway는 `id:` 가 있으면 **수정 없이 통과**. 이벤트 경계·버퍼링 로직 제거 → 청크 이슈 없음. 계약으로 “Aura가 id 필수” 고정. |

**P2 결정 제안:** 먼저 **writeWith 버그 수정**(변환된 body를 실제로 write). 경계 로직은 **개선안 B(계약 단순화)** 로 가고, Aura가 id 포함할 때까지는 기존 split 방식 유지하되 “한 청크 = 한 이상의 완전한 이벤트” 가정 문서화.

---

## 2. PR 계획 및 변경안

### PR#1 — HITL 멱등 + X-Tenant-ID 검증 + 감사 로그 (P0)

**목표:** 운영 장애/보안/데이터 무결성/감사 대응.

**변경 파일 및 구현 포인트**

1. **dwp-main-service**

   - **`HitlManager.java`**
     - **멱등:**  
       - `approve`: getApprovalRequest 후 `requestData`의 `status` 확인. `"approved"`/`"rejected"`면 **Redis/신호 없이** 기존 `sessionId` 반환.  
       - `reject`: 동일하게 `"rejected"` 또는 `"approved"`면 기존 sessionId 반환.  
       - **레이스:** Redis Lua 스크립트로 원자적 전이: 키 `hitl:request:{requestId}` 값을 읽고, status가 pending일 때만 JSON 갱신 후 SET, 1 반환; 아니면 0 반환. 0이면 기존 sessionId/status로 응답만, 1이면 기존처럼 신호 1회 발행.  
       - **Tenant 검증:** 메서드 시그니처에 `Long headerTenantId` 추가. `requestData`의 `tenantId`(숫자 또는 문자열 파싱)와 불일치 시 `BaseException(ErrorCode.TENANT_MISMATCH)` throw.  
     - **감사:** approve/reject/saveApprovalRequest 성공 후, **Feign으로 auth-server POST /internal/audit-logs** 호출 (payload: tenantId, actorUserId, action, resourceType, resourceId, metadata map). 호출 실패는 로그만 하고 메인 로직은 성공 유지.
   - **`HitlController.java`**
     - approve/reject에 `@RequestHeader("X-Tenant-ID") Long tenantId` 추가 (required). `hitlManager.approve(requestId, request.getUserId(), tenantId)`, `reject(..., tenantId)` 호출.
   - **Feign Client:**  
     - 패키지 `com.dwp.services.main.client` (또는 config 하위)에 `AuthServerAuditClient` 생성.  
     - `POST ${SERVICE_AUTH_URL}/internal/audit-logs` (또는 auth-server에 정의한 경로), Body: DTO (tenantId, actorUserId, action, resourceType, resourceId, metadata).  
     - main-service `application.yml`에 `SERVICE_AUTH_URL` 또는 기존 auth URL 사용.

2. **dwp-auth-server**

   - **감사 API**
     - **`AuditLogService.java`**  
       - 오버로드 추가: `recordAuditLog(tenantId, actorUserId, action, resourceType, resourceId, Map<String,Object> metadataMap)` (HttpServletRequest 없음). 기존 메서드에서 request만 null로 두고 호출하거나, metadata만 저장하는 구현.
     - **Controller:**  
       - `POST /internal/audit-logs` (또는 `POST /internal/audit`)  
       - Body DTO: `tenantId`, `actorUserId`, `action`, `resourceType`, `resourceId`, `metadata` (Map).  
       - `JwtConfig`에 `/internal/**` permitAll 이미 있음 (`JwtConfig.java` L77).  
     - **보안:** 내부 전용이므로 같은 VPC/API Key 등으로 제한 권장. (선택, 별도 이슈)

3. **dwp-core (선택)**

   - `ErrorCode.TENANT_MISMATCH` 이미 존재 (`ErrorCode.java` L22). 추가 변경 없음.

4. **테스트**

   - **HitlManager 단위 테스트:**  
     - approve/reject 호출 시 이미 approved(rejected)인 경우: Redis set 및 publish 미호출, 기존 sessionId 반환.  
     - tenantId 불일치 시 BaseException(TENANT_MISMATCH).  
   - **HitlController 통합 테스트:**  
     - X-Tenant-ID 없으면 400 (Gateway에서 차단되므로, main-service 단독 테스트 시는 Mock으로 Gateway 없이 400 처리 여부 또는 헤더 필수 검증).  
     - X-Tenant-ID와 Redis tenantId 불일치 시 403, body에 TENANT_MISMATCH.

**Redis Lua 초안 (approve)**

```lua
local key = KEYS[1]
local new_status = ARGV[1]
local new_json = ARGV[2]
local ttl = ARGV[3]
local v = redis.call('GET', key)
if not v then return {0, nil, nil} end
local cjson = cjson or require('cjson')
local d = cjson.decode(v)
if d.status ~= 'pending' then return {0, d.sessionId, d.status} end
d.status = new_status
-- (approvedBy, approvedAt 등 설정은 애플리케이션에서 JSON 준비)
redis.call('SET', key, new_json, 'PX', ttl)
return {1, d.sessionId, d.status}
```

- 애플리케이션에서 new_json, ttl을 준비해 스크립트 인자로 전달. 반환값 0이면 신호 미발행, 1이면 발행.

---

### PR#2 — SSO 콜백 확정 및 문서화 (P1)

**변경 파일**

- **docs/essentials/SSO_CALLBACK_SPEC.md** (신규)
  - 콜백 URL: `{redirectBaseUrl}/auth/oidc/callback` (FE 라우트와 동일해야 함).
  - IdP → FE: `code`, `state`, (선택) `providerKey`.
  - FE → BE: GET `/api/auth/oidc/callback?code=...&state=...&providerKey=...`, 헤더 `X-Tenant-ID` 또는 쿼리 `tenantId`.
  - BE 응답: `ApiResponse<LoginResponse>` (JWT 등). 리다이렉트 없음.
  - FE 동작: `/auth/oidc/callback` 페이지에서 쿼리 읽음 → BE 호출 → JWT 저장 → FE 라우터로 대시보드 이동.
  - SAML: POST `/api/auth/saml/callback` 스켈레톤, 미구현 명시.

- **docs/frontend/docs/api-spec/BE_FE_CONTRACT_UPDATE_result.md**  
  - SSO 섹션 추가: 위 규격 요약 및 FE 라우트 `/auth/oidc/callback` 권장.

---

### PR#3 — SSE 경계 및 writeWith 버그 (P2)

**결정 사항**

- **적용 완료:** `SseReconnectionFilter.writeWith` 버그 수정 적용. 변환된 Flux를 **실제로 응답에 씀**.  
  - `return originalResponse.writeWith(modifiedFlux);` (기존 `.then()` 제거)
- **경계 로직:**  
  - **선택 B 채택:** 계약으로 “Aura-Platform이 모든 SSE 이벤트에 `id:` 포함” 권장. Gateway는 `id:` 있으면 통과, 없으면 기존 addEventIdIfNeeded 유지(한 청크 = 완전 이벤트 가정).  
  - 줄 단위 누적 버퍼(선택 A)는 재현 테스트 후 필요 시 별도 PR.
- **재현 테스트:** 큰 단일 이벤트 + 작은 HttpClient 버퍼 또는 프록시로 청크 분할 재현 후, 선택 A 적용 여부 결정.

**변경 파일**

- **dwp-gateway:** `SseReconnectionFilter.java` — writeWith에서 `originalResponse.writeWith(modifiedFlux)` 호출로 수정 완료.

---

## 3. FE 계약서 업데이트 반영본

### 3.1 변경된 Status Code / Error Body

| API | 상황 | HTTP | body (ApiResponse) |
|-----|------|------|---------------------|
| POST /api/aura/hitl/approve/{requestId} | 정상(최초 승인) | 200 | data: { requestId, sessionId, status: "approved" } |
| POST /api/aura/hitl/approve/{requestId} | 이미 승인됨(멱등) | 200 | data: { requestId, sessionId, status: "approved" } (동일) |
| POST /api/aura/hitl/approve/{requestId} | X-Tenant-ID와 요청 소유 tenant 불일치 | 403 | code: E2007, message: 테넌트 정보가 일치하지 않습니다. |
| POST /api/aura/hitl/reject/{requestId} | 정상(최초 거절) | 200 | data: { requestId, sessionId, status: "rejected", reason } |
| POST /api/aura/hitl/reject/{requestId} | 이미 거절됨(멱등) | 200 | data: { requestId, sessionId, status: "rejected", reason } (동일) |
| POST /api/aura/hitl/reject/{requestId} | Tenant 불일치 | 403 | code: E2007 |
| (Gateway) | X-Tenant-ID 없음 | 400 | Gateway가 400, body 형태는 기존 정책 |

### 3.2 HITL 감사 이벤트 스키마 (FE 참고용)

감사 로그는 BE 내부 com_audit_logs에 저장. FE에서 “감사 조회” API로 조회 시 아래와 같은 리소스 타입/액션으로 노출될 수 있음.

- **resourceType:** `HITL`
- **action:** `HITL_REQUEST` | `HITL_APPROVE` | `HITL_REJECT`
- **metadata_json 예시 (요약):**
  - requestId, sessionId, action, timestamp (ISO 또는 epoch)
  - payload summary: actionType, context 키 목록만 또는 마스킹된 요약 (PII 제외)

```json
{
  "requestId": "uuid",
  "sessionId": "session-uuid",
  "action": "HITL_APPROVE",
  "timestamp": 1738166400,
  "actionType": "send_email",
  "contextKeys": ["to", "subject"]
}
```

---

### 3.3 OpenAPI 예시 (추가/변경 부분)

**POST /api/aura/hitl/approve/{requestId}**

- Request headers: `X-Tenant-ID` (required), `Authorization`, `X-User-ID`, `X-Agent-ID`
- Request body: `{ "userId": "string" }`
- Responses:
  - 200: data.requestId, data.sessionId, data.status = "approved"
  - 403: `{ "success": false, "code": "E2007", "message": "테넌트 정보가 일치하지 않습니다." }`
  - 404: Approval request not found

**POST /api/aura/hitl/reject/{requestId}**

- Request body: `{ "userId": "string", "reason": "string" }`
- Responses: 200 (동일 본문 멱등), 403 (TENANT_MISMATCH), 404

이 내용을 `docs/frontend/docs/api-spec/BE_FE_CONTRACT_UPDATE_result.md`에 반영하면 됨.

---

## 4. 메뉴 마이그레이션(V16) 적용 및 검증

**적용 방법**

- auth-server 기동 시 Flyway가 자동 실행되므로, **dwp-auth-server를 한 번 기동**하면 `V16__enterprise_menus_management.sql`이 적용됨.
- 또는 DB에 직접 Flyway를 실행한 경우에는 별도 기동 없이 적용 완료.

**검증**

- `scripts/verify-management-menus.sql` 실행: MANAGEMENT 그룹 메뉴 27건(대메뉴 6 + 하위 21) 존재 여부 확인.
- FE: 메뉴 API(`/api/admin/menus` 또는 동일 계약)에서 `menu_group = 'MANAGEMENT'` 로 필터 시 새 트리(통합 관제 센터, 자율 운영 센터 등)가 노출되는지 확인.

**FE 전달 문서**

- **계약 확정:** `docs/essentials/BE_FE_CONTRACT_Q1_Q3_FINAL.md` (SSE id / Refresh 없음 / HITL 409).
- **API·계약 업데이트:** `docs/frontend/docs/api-spec/BE_FE_CONTRACT_UPDATE_result.md`.
- **HITL·감사:** `docs/essentials/HITL_AUDIT_INTEGRATION_CHECKLIST_AND_PR.md` (해당 시 있을 경우).
