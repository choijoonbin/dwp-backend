# BE-FE 계약 Q1~Q3 확정 (SSE / Refresh / HITL 409)

**작성일:** 2025-01-29  
**배경:** FE PR#4(SSE fallback + dedupe), PR#5(HITL 409 멱등) 완료. BE 계약 확정 및 OpenAPI/테스트 반영.

---

## 1. Q1~Q3 결론표

| 질문 | 현재 코드 근거 (파일:라인) | 최종 계약(결정) | 필요 변경 |
|------|----------------------------|-----------------|-----------|
| **Q1** SSE id / Last-Event-ID replay | **id:** dwp-gateway `SseReconnectionFilter.java` L93~114: `addEventIdIfNeeded(content)` — id 없으면 `id: {eventId}\n` 주입, 있으면 통과. **Replay:** `HeaderPropagationFilter.java` L77~80, `SseReconnectionFilter.java` L55~59: Last-Event-ID **전달만**. Gateway에 replay 로직 없음. | **(1) id:** 이벤트마다 **`id: <eventId>` 라인 항상 제공.** Gateway가 Aura에 없으면 주입. FE는 id 없어도 data.id/eventId로 lastEventId 저장 가능(단, replay 품질은 서버 정책에 따름). **(2) Replay:** **Last-Event-ID가 오면 “해당 ID 이후 이벤트만” 재전송하는 주체 = Aura-Platform.** Gateway는 Last-Event-ID 전달만. 중복/순서 보장 정책은 Aura-Platform 책임. | 없음. 문서화만. |
| **Q2** Refresh Token | dwp-auth-server: `/api/auth/refresh` 또는 refresh/reissue **없음** (LoginController, AuthController만 존재). | **Refresh 엔드포인트 없음.** 401이면 **로그아웃이 계약.** FE PR#6(Refresh)은 TODO로 고정. 재인증은 POST /api/auth/login 또는 SSO만. | OpenAPI/문서에 “refresh 없음, 401 시 로그아웃” 명시. |
| **Q3** HITL approve/reject 멱등 | **변경 완료.** `HitlManager.java`: approve는 `HitlApproveResult(sessionId, status, alreadyProcessed)` 반환. reject는 `HitlRejectResult(sessionId, status, reason, alreadyProcessed)` 반환. 이미 approved/rejected면 `alreadyProcessed=true` 반환. `HitlController.java`: `alreadyProcessed`일 때 **409 Conflict** + 동일 본문(현재 상태). 최초 처리 시 200 OK. | **이미 처리된 requestId → 409 Conflict + body(현재 상태).** FE는 409를 성공으로 처리. 최초 승인/거절 → 200 OK. 중복 클릭/재시도/레이스 시 409로 일관. | 구현 완료. 테스트 시나리오 추가 완료. |

---

## 2. 변경 PR 계획 (P0 우선)

| 우선순위 | PR | 내용 | 수정 파일 |
|----------|----|------|-----------|
| **P0** | BE-HITL-409 | HITL 이미 처리 시 **409 + 현재 상태** 반환 (FE PR#5 계약). | `HitlManager.java` (HitlApproveResult/HitlRejectResult 반환), `HitlController.java` (409 반환), `HitlApproveResult.java`, `HitlRejectResult.java` (신규), `HitlManagerIdempotentTest.java` (신규) |
| **P1** | BE-CONTRACT-DOC | Q1~Q3 결론 + OpenAPI 반영 + 테스트 시나리오 문서화. | `docs/essentials/BE_FE_CONTRACT_Q1_Q3_FINAL.md` (본 문서), `docs/frontend/docs/api-spec/BE_FE_CONTRACT_UPDATE_result.md` 갱신 |

---

## 3. OpenAPI/문서 반영본

### 3.1 SSE 예시 (Q1)

**Content-Type:** `text/event-stream`

**계약:** 이벤트마다 `id:` 라인 보장 (Gateway 또는 Aura). FE는 id 없을 때 data.id/eventId로 lastEventId 저장 가능. Last-Event-ID 재전송은 **Aura-Platform** 책임.

**최소 2개 이벤트 + DONE 예시:**

```
id: 1738166400123
data: {"type":"thought","content":"분석 중입니다.","eventId":"1738166400123"}

id: 1738166400124
data: {"type":"plan_step","content":"1단계 완료","eventId":"1738166400124"}

data: [DONE]
```

- **id:** 단조 증가 권장. 재연결 시 클라이언트가 `Last-Event-ID: <id>` 전송.
- **data:** JSON 한 줄 또는 `[DONE]` (종료).
- **Replay:** Last-Event-ID 수신 시 해당 ID **이후** 이벤트만 재전송하는 주체 = Aura-Platform. 중복/순서 보장은 Aura 정책.

---

### 3.2 Refresh Token (Q2)

**결론:** **Refresh Token API 없음.**

| 항목 | 내용 |
|------|------|
| **엔드포인트** | POST /api/auth/refresh (또는 동등) **없음.** |
| **계약** | Access Token 만료 시 **401 Unauthorized** → FE는 **로그아웃** 처리. FE PR#6(Refresh)은 TODO. 재인증은 POST /api/auth/login 또는 SSO만. |
| **실패 정책** | 401 시: 로그아웃 유지. Refresh 호출 없음. |

**OpenAPI 예시 (없음 명시):**

```yaml
# Auth API 요약
paths:
  /api/auth/login:
    post:
      summary: 로그인 (LOCAL)
      # ...
  # /api/auth/refresh 는 존재하지 않음.
  # 401 시 FE는 로그아웃 후 로그인/SSO로 재인증.
```

**401 응답 예시:**

```json
{
  "success": false,
  "code": "E2000",
  "message": "인증이 필요합니다."
}
```

---

### 3.3 HITL approve/reject (Q3)

**계약:** 이미 처리된 requestId → **409 Conflict** + body(현재 상태). FE는 409를 성공으로 처리.

**POST /api/aura/hitl/approve/{requestId}**

| 상황 | HTTP | body |
|------|------|------|
| 최초 승인 성공 | 200 OK | `{ "success": true, "data": { "requestId": "...", "sessionId": "...", "status": "approved" } }` |
| 이미 처리됨 (멱등) | **409 Conflict** | `{ "success": true, "message": "Request was already processed (idempotent)", "data": { "requestId": "...", "sessionId": "...", "status": "approved" } }` (또는 status가 "rejected"일 수 있음) |

**POST /api/aura/hitl/reject/{requestId}**

| 상황 | HTTP | body |
|------|------|------|
| 최초 거절 성공 | 200 OK | `{ "success": true, "data": { "requestId": "...", "sessionId": "...", "status": "rejected", "reason": "..." } }` |
| 이미 처리됨 (멱등) | **409 Conflict** | `{ "success": true, "message": "Request was already processed (idempotent)", "data": { "requestId": "...", "sessionId": "...", "status": "rejected", "reason": "<저장된 reason>" } }` |

**테스트 시나리오 (중복 클릭/재시도/레이스):**

1. **중복 클릭:** 동일 requestId로 approve(또는 reject) 2회 호출 → 1회 200, 2회 409. body는 동일한 requestId/sessionId/status.
2. **재시도:** 네트워크 타임아웃 후 재시도 시 이미 처리됐으면 409 + 현재 상태.
3. **레이스:** 동시에 2개 요청 시 한 쪽 200, 한 쪽 409 (또는 둘 다 409). Redis 단일 키 업데이트이므로 순서에 따라 200/409 결정.

단위 테스트: `HitlManagerIdempotentTest.java` — 이미 approved/rejected인 경우 `alreadyProcessed=true` 반환, tenant 불일치 시 TENANT_MISMATCH 예외.

---

이 문서로 Q1~Q3에 대한 BE 코드 구현·OpenAPI·테스트 시나리오 확정이 완료됩니다.
