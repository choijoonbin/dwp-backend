# FE 확인 질문 Q1~Q5 — 코드 계약 확정 및 OpenAPI 반영

**작성일:** 2025-01-29  
**대상:** FRONTEND_VERIFICATION_REPORT_AND_PR_PLAN.md (또는 동일 질문)의 BE 확인 Q1~Q5

---

## 1. 결론 표

| 질문 | 현재 코드 근거 (파일:라인) | 최종 계약(결정) | 필요 변경 |
|------|----------------------------|-----------------|-----------|
| **Q1** SSE가 이벤트마다 "id: &lt;eventId&gt;" 라인을 내려주는가? | **dwp-gateway가 id 주입.** `SseReconnectionFilter.java` L93~114: `addEventIdIfNeeded(content)` — 이미 `id:` 있으면 통과, 없으면 `id: {eventId}\n` 맨 앞에 추가. Aura-Platform 코드는 본 레포 없음 → Gateway가 **보장 주체**. | **"id:" 라인을 항상 보장.** Gateway가 Aura 응답에 id 없으면 주입, 있으면 그대로 전달. FE는 Last-Event-ID 재연결 시 동일 id 기준 사용 가능. | 없음 (이미 보장). |
| **Q2** Last-Event-ID가 오면 해당 ID 이후 이벤트만 재전송하는가? | **Gateway는 전달만.** `HeaderPropagationFilter.java` L77~80: Last-Event-ID 로깅·전파. `SseReconnectionFilter.java` L55~59: Last-Event-ID 로깅, "Aura-Platform으로 전달 (HeaderPropagationFilter가 처리)". **Replay(재전송) 로직은 Gateway에 없음.** | **Replay 주체 = Aura-Platform.** Gateway는 Last-Event-ID를 Aura로 전달만 함. 해당 ID 이후 이벤트만 재전송하는지는 Aura-Platform 구현 책임. 중복/순서 보장도 Aura 정책. | 문서화 + 테스트 시나리오 추가 (아래 2절). |
| **Q3** Refresh Token endpoint/계약이 존재하는가? | **없음.** `dwp-auth-server`: `LoginController.java` (login, oidc, saml), `AuthController.java` (policy, idp, me, permissions). `/api/auth/refresh` 또는 `refresh`/`reissue` 검색 결과 없음. | **Refresh Token API 없음.** FE는 Access Token 만료 시 **401 → 로그아웃** 유지가 계약. 재발급은 로그인(/api/auth/login 또는 SSO)으로만. | OpenAPI에 "refresh 없음 + 401 시 로그아웃" 명시 (아래 3절). |
| **Q4** X-User-ID 헤더가 일반 API에 필수인가? | **Gateway에서 필수 아님.** `RequiredHeaderFilter.java` L33~46: 필수 검증은 **X-Tenant-ID** 만. X-User-ID는 `CorsConfig.java` L35, L72에서 **허용·전파**만. `HeaderPropagationFilter.java` L65~67: 전파 확인. | **X-User-ID는 필수 아님.** CORS 허용 + 다운스트림 전파만. 일부 API(예: HITL body의 userId)는 비즈니스상 필요하지만, Gateway 단에서 차단하지 않음. | 없음. (필수로 바꿀 경우: RequiredHeaderFilter에 X-User-ID 검증 추가 + 문서/OpenAPI 반영.) |
| **Q5** 메뉴 트리 path 규칙은? (/app/admin vs /admin) | **BE는 menu_path 값을 그대로 내려줌.** `MenuNode.java` L46: `path` = 라우트 경로 (예: /admin/users). `MenuQueryService.java` L81: `.path(menu.getMenuPath())`. `MenuSummary.java` L22: `menuPath`. Admin 생성 시 `CreateMenuRequest.routePath` → `menu_path` 저장 (`MenuCommandService.java` L76). | **Canonical path = `path`(트리) / `menuPath`(요약).** BE는 **/app 접두사 없이** DB에 저장된 값 그대로 반환 (예: /admin/users). FE normalizeRoutePath는 FE 책임. pathNormalized 필드는 없음. | 없음. (FE가 /app 필요 시 FE에서 prefix 적용.) |

---

## 2. Q2 보완: Last-Event-ID Replay 책임 및 테스트 시나리오

### 2.1 책임 확정

- **Replay 주체:** **Aura-Platform** (SSE 스트림 생성 주체). Gateway는 Last-Event-ID 헤더를 Aura로 전달만 함.
- **중복/순서 보장:** Aura-Platform 정책. 권장: Last-Event-ID 이후 이벤트만 재전송, 동일 id 중복 미전송, 순서 유지.

### 2.2 문서화 (간단)

- Gateway: Last-Event-ID를 `/api/aura/**` 요청 시 Aura-Platform으로 전달. Replay 미구현.
- Aura-Platform: Last-Event-ID 수신 시 해당 id 이후 이벤트만 재전송하도록 구현 권장. (별도 레포/문서에 명시)

### 2.3 테스트 시나리오 제안

1. **Gateway:**  
   - 요청에 `Last-Event-ID: 12345` 포함 → Aura로 전달되는지 로그/프록시로 확인.  
   - (이미 `SseStreamingTest.java` L82~91 등에서 Last-Event-ID 전달 검증 가능.)
2. **Aura-Platform (해당 레포):**  
   - Last-Event-ID 수신 시 이후 이벤트만 스트리밍하는지, 중복/순서가 맞는지 시나리오 추가.

---

## 3. PR 계획 (변경 필요 시)

| 우선순위 | PR | 내용 | 수정 파일 |
|----------|----|------|-----------|
| **P0** | - | Q1/Q4/Q5는 코드상 이미 계약 충족. 변경 없음. | - |
| **P1** | PR-FE-VERIFY | Q2: Last-Event-ID Replay 책임·정책 문서화 + Gateway 테스트 시나리오 보강. Q3: Refresh 없음 + 401 로그아웃 계약 OpenAPI 반영. | `docs/essentials/FRONTEND_VERIFICATION_Q1_Q5_CONTRACT_AND_OPENAPI.md` (본 문서), `docs/frontend/docs/api-spec/` OpenAPI 반영본, 필요 시 `dwp-gateway` 테스트 추가. |

---

## 4. OpenAPI 반영본 (예시)

### 4.1 SSE 이벤트 포맷 (Q1 반영)

**Content-Type:** `text/event-stream`

**이벤트 형식 (계약):**  
모든 SSE 이벤트에 **`id:` 라인**이 포함된다. (Gateway가 Aura에 없으면 주입.)

```
id: 1738166400123
data: {"type":"thought","content":"..."}

id: 1738166400124
data: {"type":"plan_step","content":"..."}

data: [DONE]
```

- **id:** 단조 증가 권장. 재연결 시 클라이언트가 `Last-Event-ID: <id>` 로 보냄.
- **data:** JSON 한 줄 또는 `[DONE]` (종료).
- **이벤트 타입 예:** thought, plan_step, tool_execution, hitl, content, plan_step_update, timeline_step_update.

### 4.2 Refresh Token (Q3 반영)

**결론:** **Refresh Token API 없음.**

| 항목 | 내용 |
|------|------|
| **엔드포인트** | `/api/auth/refresh` (또는 동등) **없음.** |
| **계약** | Access Token 만료 시 **401 Unauthorized** 수신 → FE는 **로그아웃** 처리. 재인증은 **POST /api/auth/login** 또는 SSO(예: GET /api/auth/oidc/login 등)로만 가능. |
| **실패 정책** | 401 시: 로그아웃 유지. Refresh/재발급 API 호출하지 않음. |

**OpenAPI 예시 (존재하지 않음을 명시):**

```yaml
# Auth API 요약
paths:
  /api/auth/login:
    post:
      summary: 로그인 (LOCAL)
      # ...
  /api/auth/policy:
    get:
      summary: 테넌트별 로그인 정책
      # ...
  # /api/auth/refresh 는 존재하지 않음.
  # Token 갱신: 없음. 401 시 FE는 로그아웃 후 로그인/SSO로 재인증.
```

**에러 응답 (401):**

```json
{
  "success": false,
  "code": "E2000",
  "message": "인증이 필요합니다."
}
```

- FE: 401 수신 시 로그아웃(토큰 제거, 로그인 화면 이동).

### 4.3 X-User-ID (Q4 반영)

- **헤더:** `X-User-ID` — **선택(optional).** Gateway에서 필수 검증 없음.
- **CORS:** 허용됨 (`CorsConfig`).
- **전파:** 다운스트림 서비스로 전달됨 (`HeaderPropagationFilter`).
- **에러:** X-User-ID 없음으로 인한 Gateway 400 없음. (일부 API가 비즈니스상 userId를 body/별도 검증할 수 있음.)

### 4.4 메뉴 트리 path (Q5 반영)

**GET /api/auth/menus/tree** (또는 GET /api/admin/menus/tree) 응답 노드 예:

```json
{
  "id": 1,
  "parentId": null,
  "menuKey": "menu.admin.users",
  "menuName": "사용자 관리",
  "path": "/admin/users",
  "icon": "solar:user-bold",
  "group": "MANAGEMENT",
  "depth": 1,
  "sortOrder": 1,
  "children": []
}
```

- **path:** DB `menu_path` 값. **Canonical path 필드.** 형식: BE는 `/app` 접두사 없이 저장값 그대로 반환 (예: `/admin/users`). FE에서 `/app` 등 prefix가 필요하면 `normalizeRoutePath` 등으로 처리.
- **pathNormalized:** 없음. 라우팅용은 `path` 사용.

---

이 문서로 Q1~Q5에 대한 BE 코드 계약 확정과 OpenAPI 반영이 완료됩니다. FE는 위 계약을 기준으로 Last-Event-ID 재연결(재전송은 Aura 책임), 401 시 로그아웃, X-User-ID 선택 사용, 메뉴 `path` 사용 및 필요 시 FE에서 path 정규화를 적용하면 됩니다.
