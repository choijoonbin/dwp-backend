# BE-FE 계약 점검 리포트 및 작업 계획

**작성일:** 2025-01-29  
**대상:** FE 계약(README 기준) 충족 여부, 운영 안정성 체크리스트, PR 계획, FE 계약서 업데이트 제안

---

## 1. 점검 리포트

### A. CORS / Gateway

| 항목 | 충족 여부 | 근거(코드 경로) | 리스크 | 개선안 |
|------|-----------|------------------|--------|--------|
| CORS 허용 헤더에 X-Tenant-ID, X-User-ID, Authorization, X-Agent-ID 포함 | ✅ 충족 | `dwp-gateway/.../CorsConfig.java` L35 `allowedHeaders`에 위 4종 + Last-Event-ID 포함. L70~76에서 표준 헤더 명시 추가. | 없음 | - |
| preflight(OPTIONS) 및 credentials 정책 | ✅ 충족 | `CorsConfig.java` L31 `allowedOrigins`, L36 `allowCredentials = true`, L50 `setAllowedMethods`(OPTIONS 포함), L82 `setAllowCredentials`, L83 `setMaxAge(3600)`. | 없음 | - |
| 단일 진입점(8080) 라우팅/프록시 | ✅ 충족 | `dwp-gateway/.../application.yml` L2 `server.port: 8080`, L19~100 모든 `/api/**` 경로가 Gateway를 거쳐 각 서비스로 라우팅. hitl-api(`/api/aura/hitl/**`)가 aura-platform(`/api/aura/**`)보다 먼저 정의되어 HITL이 main-service로 정확히 라우팅됨. | 없음 | 운영 배포 시 Nginx 등에서 8080 단일 진입점 유지 확인만 권장. |
| ExposedHeaders 설정 | ⚠️ 선택 | `CorsConfig.java`에 `setExposedHeaders` 없음. FE가 응답 헤더(X-Request-Id 등)를 읽지 않으면 불필요. | 낮음 | FE에서 커스텀 응답 헤더를 읽을 경우 `exposedHeaders` 추가. |

**확인할 파일:**  
- `dwp-gateway/src/main/java/com/dwp/gateway/config/CorsConfig.java`  
- `dwp-gateway/src/main/resources/application.yml`

---

### B. Auth Policy / SSO

| 항목 | 충족 여부 | 근거(코드 경로) | 리스크 | 개선안 |
|------|-----------|------------------|--------|--------|
| GET /api/auth/policy 응답 스키마(테넌트별 LOCAL/SSO) | ✅ 충족 | `dwp-auth-server/.../AuthController.java` L41 `getAuthPolicy(@RequestHeader("X-Tenant-ID") Long tenantId)`. `AuthPolicyResponse`(tenantId, defaultLoginType, allowedLoginTypes, localLoginEnabled, ssoLoginEnabled, ssoProviderKey, requireMfa) — `dwp-auth-server/.../dto/AuthPolicyResponse.java` L17~26. | 없음 | - |
| 401/403 정책 (FE: 401→로그아웃, 403→전역 에러) | ✅ 충족 | `dwp-auth-server/.../SecurityExceptionHandler.java`: 401은 `AuthenticationEntryPoint.commence`(L44~55), 403은 `AccessDeniedHandler.handle`(L64~76). 둘 다 `ApiResponse` JSON + `writeResponse`로 status 설정. `ErrorCode.java` UNAUTHORIZED=401, FORBIDDEN=403. | 없음 | FE와 401/403 시 동일한 `ApiResponse` 구조(code, message) 사용 중이면 문서화만 보강. |
| SSO 콜백(/sso-callback) 토큰 발급/리다이렉트 | ⚠️ 확인 필요 | SSO 콜백 경로 및 FE 라우팅 일치 여부는 `dwp-auth-server` 내 SSO 관련 컨트롤러/서비스에서 확인 필요. (검색 범위: `/sso`, `sso-callback`, `IdentityProvider`) | 중간 | SSO 사용 시 콜백 URL·리다이렉트 규격을 FE와 문서로 합의. |

**확인할 파일:**  
- `dwp-auth-server/.../AuthController.java`, `AuthPolicyResponse.java`  
- `dwp-auth-server/.../config/SecurityExceptionHandler.java`  
- `dwp-core/.../ErrorCode.java`  
- SSO: `dwp-auth-server` 내 `IdentityProvider`, `sso-callback` 검색

---

### C. SSE 스트리밍 규격

| 항목 | 충족 여부 | 근거(코드 경로) | 리스크 | 개선안 |
|------|-----------|------------------|--------|--------|
| Content-Type: text/event-stream, 버퍼링/플러시 | ✅ 충족 | `SseResponseHeaderFilter.java` L89~108: Content-Type, Cache-Control, Connection: keep-alive, X-Accel-Buffering: no 설정. Gateway `application.yml` L12~17 response-timeout 300s, pool 설정. | 없음 | - |
| 이벤트 포맷 data: JSON \n\n, 종료 data: [DONE] | ⚠️ BE 일부 | Gateway는 포맷을 생성하지 않음. 종료 `data: [DONE]`은 **Aura-Platform(Python)** 발행 책임 — `.cursorrules`, `docs/testdoc/AURA_PLATFORM_VERIFICATION_REQUIREMENTS.md` 등에 명시. BE는 스트림을 그대로 전달. | 중간 | Aura-Platform에 `data: [DONE]\n\n` 발행 의무를 계약으로 고정. |
| Last-Event-ID 지원: 서버가 id: 내려주는지 | ✅ 충족 | `SseReconnectionFilter.java` L97~124: 이벤트에 `id:` 라인이 없으면 `addEventIdIfNeeded`로 id 라인 추가. `id: {eventId}\n` 형식. | 없음 | - |
| Last-Event-ID: 재연결 시 전달 및 replay | ⚠️ 부분 | Gateway는 `Last-Event-ID`를 Aura-Platform으로 전달(`HeaderPropagationFilter` L77~80, `SseReconnectionFilter` L54~59). **실제 replay(중단 지점 재개)는 Aura-Platform 구현 책임.** BE는 헤더 전달만 보장. | 중간 | Aura-Platform에서 Last-Event-ID 기반 replay 구현 여부 확인 및 문서화. |
| 이벤트 타입(thought/plan_step/tool_execution/hitl/content 등) | ⚠️ BE 무관 | 이벤트 타입은 Aura-Platform이 data JSON 내 `type` 필드로 발행. Gateway는 내용을 변경하지 않음. | 낮음 | Aura-Platform 이벤트 타입 목록을 FE-BE 계약서에 명시. |
| 스키마 버전(version) 필드 제공 | ❌ 미충족 | SSE 이벤트에 `version` 필드 없음. FE/BE 독립 배포 시 호환성 관리에 유리. | 낮음 | SSE 이벤트 스키마에 `version`(예: "1.0") 필드 추가 권장(Aura-Platform 또는 Gateway에서 주입). |

**SseReconnectionFilter 버퍼링 이슈:**  
`SseReconnectionFilter.writeWith`(L67~84)에서 `Flux.from(body).map(...)`으로 **청크 단위** 처리. 한 청크가 여러 이벤트를 포함하거나, 한 이벤트가 여러 청크로 나뉘면 `split("\n\n")` 기준으로 id 부여가 한 이벤트 단위로 정확하지 않을 수 있음.  
- **리스크:** 매우 큰 청크 또는 매우 작은 청크 분할 시 이벤트 경계 깨짐 가능.  
- **개선안:** 이벤트 경계를 유지하는 버퍼링 로직(줄 단위 누적 후 `\n\n` 기준 분할) 검토. 또는 Aura-Platform이 이미 `id:`를 포함해 내려주면 Gateway는 id 미추가로 통과시키므로, Aura-Platform 측 id 정책을 우선 권장.

**확인할 파일:**  
- `dwp-gateway/.../SseResponseHeaderFilter.java`, `SseReconnectionFilter.java`, `HeaderPropagationFilter.java`  
- `dwp-gateway/src/main/resources/application.yml`  
- Aura-Platform 스트림 발행 코드(별도 레포)

---

### D. HITL 승인/거절

| 항목 | 충족 여부 | 근거(코드 경로) | 리스크 | 개선안 |
|------|-----------|------------------|--------|--------|
| approve/reject 엔드포인트 존재 및 경로 | ✅ 충족 | `dwp-main-service/.../HitlController.java` L44 `POST /aura/hitl/approve/{requestId}`, L68 `POST /aura/hitl/reject/{requestId}`. Gateway에서 `/api/aura/hitl/**` → main-service `/aura/hitl/**`(StripPrefix=1). FE 기대 경로 `POST /api/aura/hitl/approve/{requestId}` 등과 일치. | 없음 | - |
| requestId 기준 멱등성 | ❌ 미충족 | `HitlManager.approve`(L162~224), `reject`(L234~298): 이미 `approved`/`rejected`인지 확인하지 않고 매번 Redis 업데이트 및 Pub/Sub 신호 재발행. 동일 requestId로 재요청 시 중복 신호 발생. | 중간 | approve/reject 시 `status`가 이미 approved/rejected면 200 + 동일 본문 반환(멱등). 신호는 최초 1회만 발행. |
| 승인/거절 후 스트림 재개/종료 상태머신 | ⚠️ Aura 쪽 | BE는 Redis에 상태 저장 및 신호 발행만 수행. 스트림 재개/종료는 Aura-Platform이 신호 수신 후 처리. BE 상태머신은 pending→approved/rejected 일관됨. | 낮음 | Aura-Platform과 상태 전이 문서 공유. |
| pending/approved/rejected/expired 및 타임아웃 | ✅ 부분 | `HitlManager`: status는 pending/approved/rejected. TTL 30분(`HITL_REQUEST_TTL`). **expired**는 Redis 키 소멸로만 표현되며, API에서 "expired" 상태 코드를 명시적으로 반환하지는 않음. getApprovalRequest 시 없으면 NOT_FOUND. | 낮음 | 만료된 요청 조회 시 404 대신 410 Gone 또는 body에 status: "expired" 반환 검토(선택). |

**확인할 파일:**  
- `dwp-main-service/.../controller/HitlController.java`  
- `dwp-main-service/.../service/HitlManager.java`  
- `dwp-gateway/.../application.yml` (hitl-api 라우트)

---

### E. 멀티테넌시/권한

| 항목 | 충족 여부 | 근거(코드 경로) | 리스크 | 개선안 |
|------|-----------|------------------|--------|--------|
| X-Tenant-ID 강제 위치 | ⚠️ 서비스별 | Gateway `RequiredHeaderFilter`에서 필수 검증(공개 API 제외). Auth 서버는 `getAuthPolicy` 등에서 `@RequestHeader("X-Tenant-ID")` 사용. Admin API는 Guard에서 테넌트 검증. **main-service HITL**은 Controller에서 X-Tenant-ID를 명시적으로 검증하지 않음 — HitlManager는 Redis 데이터에 tenantId 저장만. | 중간 | HITL approve/reject 시 요청 헤더 X-Tenant-ID와 Redis 내 tenantId 일치 검증 추가 권장. |
| 메뉴 트리 path vs key (canonical path) | ✅ 충족 | `Menu.java` L32 `menu_key`, L38 `menu_path`. `MenuQueryService` L126~130 `MenuSummary`에 `menuPath(menu.getMenuPath())` 반환. `CreateMenuRequest`의 `routePath`가 menu_path로 매핑. path가 라우팅용 별도 필드로 제공됨. | 없음 | - |
| 메뉴/리소스 권한과 BE 권한 모델 일치 | ⚠️ 확인 필요 | RBAC/메뉴 트리/Resource는 dwp-auth-server에 있음. FE PermissionRouteGuard와 동일한 리소스 키/경로를 BE가 내려주는지는 메뉴·권한 API 응답 스키마 대조 필요. | 낮음 | FE와 메뉴·권한 API 스키마 및 리소스 키 목록 문서 공유. |

**확인할 파일:**  
- `dwp-auth-server/.../entity/Menu.java`, `.../menus/MenuQueryService.java`, `.../dto/admin/MenuSummary.java`, `CreateMenuRequest.java`  
- `dwp-gateway/.../RequiredHeaderFilter.java`  
- `dwp-main-service/.../HitlController.java` (X-Tenant-ID 검증 여부)

---

### F. Audit-by-Design

| 항목 | 충족 여부 | 근거(코드 경로) | 리스크 | 개선안 |
|------|-----------|------------------|--------|--------|
| Admin CRUD 감사 로그 | ✅ 충족 | `dwp-auth-server` 내 RoleCommandService, UserCommandService, MenuCommandService, CodeUsageCommandService, ResourceCommandService, DepartmentCommandService, UserPasswordService, RoleMemberCommandService, RolePermissionCommandService 등에서 `AuditLogService.recordAuditLog` 호출. `AuditLog` 엔티티 → `com_audit_logs` 테이블. | 없음 | - |
| HITL 요청/승인/거절 감사 로그 | ❌ 미충족 | `HitlManager.saveApprovalRequest`, `approve`, `reject`에서 `com_audit_logs` 또는 동등 감사 로그 기록 없음. | 높음 | HITL 생성·승인·거절 시 감사 로그 기록 추가. (main-service에 AuditLog 저장소 또는 auth-server 감사 API 호출) |
| 데이터 조회/케이스/시뮬레이션/Action/정책 변경 감사 | ⚠️ 서비스별 | auth-server Admin 외, main-service(AgentTask/케이스), aura-service(시뮬레이션/데이터 조회) 등에서 감사 로그 설계 여부는 서비스별 코드 확인 필요. | 중간 | 엔터프라이즈 메뉴 vFinal 요구(데이터 조회, 케이스 생성/상태변경, 시뮬레이션, Action 실행, 정책 변경)에 대해 감사 로그 대상 및 저장소 정책 수립. |

**확인할 파일:**  
- `dwp-auth-server/.../service/audit/AuditLogService.java`, `.../entity/AuditLog.java`  
- `dwp-auth-server` 내 각 CommandService (AuditLogService 주입 및 recordAuditLog 호출)  
- `dwp-main-service/.../HitlManager.java` (감사 로그 호출 없음)  
- 기타 서비스: AgentTask, 케이스, 시뮬레이션 관련 서비스

---

## 2. 운영 안정성 체크리스트

아래는 SSE/HITL/인증/헤더/CORS/멱등/감사로그 관점의 **운영 안정성 체크리스트**입니다. 배포 전·계약 변경 시 확인 권장.

### CORS / Gateway
- [ ] CORS `allowedHeaders`에 Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, Last-Event-ID 포함 여부
- [ ] OPTIONS preflight 및 allowCredentials=true
- [ ] 운영 환경에서 단일 진입점(예: 8080 또는 Nginx → 8080) 유지
- [ ] `/api/aura/hitl/**`가 main-service로, `/api/aura/**`(그 외)가 Aura-Platform으로 라우팅되는지

### Auth Policy / SSO
- [ ] GET /api/auth/policy 응답 스키마(LOCAL/SSO, allowedLoginTypes 등) FE와 일치
- [ ] 401 응답 시 FE 로그아웃 유도 가능한 구조(ApiResponse + 401 status)
- [ ] 403 응답 시 FE 전역 에러 표시 가능한 구조(ApiResponse + 403 status)
- [ ] SSO 사용 시 콜백 URL·리다이렉트 규격이 FE 라우팅과 일치

### SSE
- [ ] Content-Type: text/event-stream, X-Accel-Buffering: no, Cache-Control: no-cache
- [ ] Gateway 타임아웃(예: 300s)으로 스트림 중간 끊김 방지
- [ ] 이벤트에 id: 라인 포함(재연결 시 Last-Event-ID 사용)
- [ ] Aura-Platform이 스트림 종료 시 data: [DONE]\n\n 발행
- [ ] (권장) 이벤트 스키마에 version 필드

### HITL
- [ ] POST /api/aura/hitl/approve/{requestId}, reject/{requestId} 멱등(이미 처리된 경우 200 + 동일 본문)
- [ ] X-Tenant-ID와 Redis 내 tenantId 일치 검증
- [ ] HITL 요청 생성/승인/거절 감사 로그 기록

### 멀티테넌시/권한
- [ ] 모든 비공개 API에서 X-Tenant-ID 검증 또는 JWT tenant_id와 일치 검증
- [ ] 메뉴/리소스 API가 FE PermissionRouteGuard와 동일한 path/key 제공

### Audit-by-Design
- [ ] Admin CRUD → com_audit_logs 기록
- [ ] HITL 생성/승인/거절 → 감사 로그 기록
- [ ] (정책) 데이터 조회/케이스/시뮬레이션/Action/정책 변경 감사 대상 및 저장소 정의

---

## 3. PR 계획 (우선순위)

| PR | 제목 | 변경 파일/구현 포인트 | 우선순위 |
|----|------|------------------------|----------|
| **PR#1** | HITL approve/reject 멱등 처리 및 감사 로그 | `HitlManager.java`: approve/reject 전에 status가 approved/rejected면 200 + 기존 본문 반환, 신호 재발행 생략. 동일 파일 또는 main-service 감사 모듈: HITL 생성·승인·거절 시 감사 로그 기록(com_audit_logs 연동 또는 auth-server 감사 API 호출). | P0 |
| **PR#2** | HITL X-Tenant-ID 검증 | `HitlController.java` 또는 `HitlManager.java`: approve/reject 시 요청 헤더 X-Tenant-ID와 Redis 저장 tenantId 불일치 시 403 반환. | P0 |
| **PR#3** | SSE 이벤트 스키마 버전 필드 (권장) | Aura-Platform 또는 Gateway: SSE 이벤트 data JSON에 `version: "1.0"` 추가. 문서 갱신. | P1 |
| **PR#4** | SseReconnectionFilter 이벤트 경계 보장 (선택) | `SseReconnectionFilter.java`: 청크 경계를 넘는 이벤트에 대해 줄 단위 버퍼로 `\n\n` 기준 분할 후 id 부여. 또는 Aura-Platform이 id 포함 시 Gateway는 id 미추가 정책 명시. | P2 |
| **PR#5** | CORS exposedHeaders (필요 시) | `CorsConfig.java`: FE가 읽어야 하는 응답 헤더가 있으면 `setExposedHeaders` 추가. | P2 |
| **PR#6** | Auth Policy / SSO 콜백 문서화 | docs: GET /api/auth/policy 스키마, 401/403 처리, SSO 콜백 URL·리다이렉트 규격을 FE와 합의 문서로 정리. | P1 |
| **PR#7** | 감사 로그 확장 정책 (데이터 조회/케이스/시뮬레이션/Action) | docs + 서비스별 이슈: Audit-by-Design 대상 목록, 저장소(com_audit_logs vs 서비스별), 마스킹/내보내기 정책. 필요 시 main-service/aura-service에 감사 로그 호출 추가. | P1 |

---

## 4. FE에 전달할 계약서 업데이트 제안

### 4.1 OpenAPI/예시 요청·응답 요약

- **Base URL:** `NX_API_URL` (예: `http://localhost:8080`) — 단일 진입점.
- **공통 요청 헤더:**  
  `Authorization: Bearer <JWT>`, `X-Tenant-ID: <tenantId>`, `X-User-ID: <userId>`, `X-Agent-ID: <agentId>` (axiosInstance에서 항상 전송).

**GET /api/auth/policy**  
- 요청 헤더: `X-Tenant-ID` (필수).  
- 응답 예시:
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

**POST /api/aura/hitl/approve/{requestId}**  
- 요청 본문: `{ "userId": "<userId>" }`.  
- 응답 예시 (멱등 반영 후):
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
- 이미 승인된 requestId로 재요청 시: HTTP 200, 동일 본문(멱등).

**POST /api/aura/hitl/reject/{requestId}**  
- 요청 본문: `{ "userId": "<userId>", "reason": "<reason>" }`.  
- 응답 예시 (멱등 반영 후):
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
- 이미 거절된 requestId로 재요청 시: HTTP 200, 동일 본문(멱등).

**401/403**  
- 본문: `ApiResponse` 형식 (`code`, `message` 등).  
- 401: 인증 필요/토큰 만료 → FE 로그아웃 유도.  
- 403: 권한 없음 → FE 전역 에러 알림.

### 4.2 SSE 계약 (Aura 스트림)

- **수신:** fetch ReadableStream.  
- **이벤트 형식:** `data: {"type":"thought","content":"..."}\n\n` (JSON 한 줄).  
- **종료:** `data: [DONE]\n\n`  
- **재연결:** 클라이언트가 `Last-Event-ID: <id>` 헤더로 요청. 서버(Aura-Platform)는 가능 시 해당 id 이후부터 재전송.  
- **이벤트 타입:** thought, plan_step, tool_execution, hitl, content, plan_step_update, timeline_step_update 등 (Aura-Platform과 합의한 목록을 문서에 고정).  
- **(권장)** data 객체에 `version` 필드(예: "1.0") 포함.

### 4.3 CORS

- 허용 요청 헤더: Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, Last-Event-ID, Content-Type, Accept 등.  
- credentials: true.  
- 필요 시 응답 헤더 노출은 `exposedHeaders`로 합의.

---

**문서 위치:** `docs/essentials/BE_FE_CONTRACT_INSPECTION_REPORT.md`  
**추가 확인 권장:**  
- Aura-Platform 레포: `data: [DONE]`, `id:` 라인, Last-Event-ID 기반 replay 구현 여부.  
- dwp-auth-server: SSO 콜백 경로 및 리다이렉트 규격.  
- main-service: HITL 외 AgentTask/케이스 감사 로그 정책.
