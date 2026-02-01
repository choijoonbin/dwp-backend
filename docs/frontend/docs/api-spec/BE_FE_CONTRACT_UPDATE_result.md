# BE-FE 계약 업데이트 제안 (BE 점검 결과)

**작성일:** 2025-01-29  
**관련 문서:** `docs/essentials/BE_FE_CONTRACT_INSPECTION_REPORT.md`

BE 측에서 FE 계약(README 기준)을 코드로 점검한 결과, **유지할 계약**과 **보완 후 합의할 항목**을 정리했습니다. OpenAPI/예시 payload는 아래와 같이 사용하시면 됩니다.

---

## 1. 유지되는 계약 (변경 없음)

- **Gateway base:** `NX_API_URL=http://localhost:8080` (단일 진입점)
- **CORS 허용 헤더:** Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, Last-Event-ID 포함
- **Auth 정책:** GET /api/auth/policy — 테넌트별 LOCAL/SSO 스키마 (아래 예시 참고)
- **HITL 경로:** POST /api/aura/hitl/approve/{requestId}, POST /api/aura/hitl/reject/{requestId}
- **axiosInstance:** Authorization, X-Tenant-ID, X-Agent-ID 항상 전송
- **401/403:** ApiResponse 형식; 401 → 로그아웃 유도, 403 → 전역 에러 알림
- **메뉴:** `menuPath` 별도 필드로 라우팅용 path 제공 (path vs key 분리)

---

## 2. OpenAPI/예시 Payload

### GET /api/auth/policy

**요청 헤더**
- `X-Tenant-ID`: (필수) Long

**응답 예시**
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

---

### POST /api/aura/hitl/approve/{requestId}

**요청**
- Path: `requestId` (string)
- Body: `{ "userId": "<userId>" }`
- Headers: **X-Tenant-ID (필수)**, Authorization, X-User-ID, X-Agent-ID

**응답 예시 (200 OK)**
```json
{
  "success": true,
  "data": {
    "requestId": "<requestId>",
    "sessionId": "<sessionId>",
    "status": "approved"
  }
}
```

**멱등:** 동일 requestId로 **이미 처리된 경우 409 Conflict** + 동일 본문(현재 상태). FE는 409를 성공으로 처리. 최초 승인 시 200 OK.

**409 Already Processed (BE 확정)**
- 이미 approved/rejected인 requestId로 재요청 시 **409 Conflict**.
- body: `{ "success": true, "message": "Request was already processed (idempotent)", "data": { "requestId", "sessionId", "status", "reason"(reject 시) } }`

**403 Tenant 불일치**
- 요청 헤더 X-Tenant-ID와 해당 HITL 요청의 tenantId가 다르면 403.
- body: `{ "success": false, "code": "E2007", "message": "테넌트 정보가 일치하지 않습니다." }`

---

### POST /api/aura/hitl/reject/{requestId}

**요청**
- Path: `requestId` (string)
- Body: `{ "userId": "<userId>", "reason": "<reason>" }`
- Headers: **X-Tenant-ID (필수)**, Authorization, X-User-ID, X-Agent-ID

**응답 예시 (200 OK)**
```json
{
  "success": true,
  "data": {
    "requestId": "<requestId>",
    "sessionId": "<sessionId>",
    "status": "rejected",
    "reason": "<reason>"
  }
}
```

**멱등:** 이미 처리된 경우 **409 Conflict** + 현재 상태 본문. FE는 409를 성공으로 처리. 최초 거절 시 200 OK.

**403:** X-Tenant-ID와 HITL 요청 tenantId 불일치 시 동일 (code E2007).

---

### 401 / 403

- **401:** `ApiResponse` + HTTP 401. FE: 로그아웃 유도.
- **403:** `ApiResponse` + HTTP 403. FE: 전역 에러 알림.

응답 body 구조는 기존 ApiResponse(code, message 등) 동일.

---

## 3. SSE 계약 (Aura 스트림)

- **수신:** fetch ReadableStream
- **이벤트:** `data: {"type":"<type>","content":"..."}\n\n` (JSON 한 줄)
- **종료:** `data: [DONE]\n\n`
- **재연결:** 요청 시 `Last-Event-ID: <id>` 헤더 전송. 서버(Aura-Platform)가 가능 시 해당 id 이후부터 재전송.
- **이벤트 타입:** thought, plan_step, tool_execution, hitl, content, plan_step_update, timeline_step_update 등 (Aura와 합의한 목록 유지)
- **(권장)** data 객체에 `version` 필드(예: "1.0") — BE/Aura 보완 시 FE는 무시 가능

---

## 4. SSO 콜백 규격 (OIDC)

- **FE 라우트:** IdP redirect_uri와 일치해야 함. BE 기본값은 `{redirectBaseUrl}/auth/oidc/callback` 이므로 **FE는 `/auth/oidc/callback` 라우트 권장.** (`/sso-callback` 사용 시 IdP에 등록한 redirect_uri를 FE 기준으로 통일)
- **IdP → FE 쿼리:** `code`, `state`, (선택) `providerKey`
- **FE → BE:** GET `/api/auth/oidc/callback?code=...&state=...&providerKey=...` + 헤더 `X-Tenant-ID` 또는 쿼리 `tenantId`
- **BE 응답:** JSON `ApiResponse<LoginResponse>` (JWT 등). 리다이렉트 없음. FE가 JWT 저장 후 라우터로 이동.
- 상세: `docs/essentials/SSO_CALLBACK_SPEC.md`

---

## 5. HITL 감사 이벤트 (참고)

감사 조회 API에서 노출될 수 있는 리소스:

- **resourceType:** `HITL`
- **action:** `HITL_REQUEST` | `HITL_APPROVE` | `HITL_REJECT`
- **metadata 예시:** requestId, sessionId, actionType, timestamp, reason(거절 시) 등 (PII 마스킹)

---

## 6. BE 적용 완료 (PR#1 + HITL 409 확정)

- HITL approve/reject **멱등:** 이미 처리된 requestId → **409 Conflict** + 현재 상태 본문. FE는 409를 성공으로 처리.
- HITL **X-Tenant-ID 검증:** 불일치 시 403 (E2007)
- HITL **감사 로그:** REQUEST/APPROVE/REJECT 시 com_audit_logs 기록
- **Refresh Token API 없음.** 401 시 FE 로그아웃. FE PR#6(Refresh)은 TODO.

(선택) CORS **exposedHeaders**: FE에서 읽을 응답 헤더가 있으면 합의 후 추가.

---

## 7. FE 확인 Q1~Q5 계약 요약

| 항목 | 계약 |
|------|------|
| **Q1 SSE id** | 이벤트마다 **"id: &lt;eventId&gt;"** 라인 보장. dwp-gateway가 Aura에 없으면 주입. |
| **Q2 Last-Event-ID** | **Replay 주체 = Aura-Platform.** Gateway는 Last-Event-ID 전달만. 해당 ID 이후 재전송·중복/순서는 Aura 정책. |
| **Q3 Refresh Token** | **Refresh API 없음.** 401 시 FE는 **로그아웃** 유지. 재인증은 로그인/SSO만. |
| **Q4 X-User-ID** | **필수 아님.** CORS 허용 + 전파만. Gateway에서 필수 검증 없음. |
| **Q5 메뉴 path** | **path** (트리) / **menuPath** (요약) = DB 값 그대로. /app 접두사 없음. FE normalizeRoutePath는 FE 책임. |

상세: `docs/essentials/FRONTEND_VERIFICATION_Q1_Q5_CONTRACT_AND_OPENAPI.md`

---

이 문서는 BE 점검 결과를 바탕으로 한 FE 계약서 업데이트 제안이며, 상세 점검·PR 계획은 `docs/essentials/BE_FE_CONTRACT_INSPECTION_REPORT.md`, `BE_CONTRACT_CONFIRMATION_AND_PR_PLAN.md`를 참고하시면 됩니다.
