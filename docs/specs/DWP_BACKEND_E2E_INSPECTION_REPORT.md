# DWP Backend E2E 점검 보고서

**작성일**: 2026-01-29  
**목적**: SSE 표준 헤더 계약, Documents/OpenItems/Lineage 필터 파라미터, HITL E2E 사이클 점검 및 보완안 제시

---

## A. 표준 헤더 계약 강제 수준 점검

### A.1 Gateway에서 upstream(Aura)로 SSE 요청 프록시 시 전달 헤더

**코드 근거:**

| 파일 | 라인 | 내용 |
|------|------|------|
| `dwp-gateway/.../HeaderPropagationFilter.java` | 26-31 | 전파 대상 헤더 상수: `Authorization`, `X-Tenant-ID`, `X-DWP-Source`, `X-DWP-Caller-Type`, `X-User-ID`, `X-Agent-ID`, `Last-Event-ID` |
| `HeaderPropagationFilter.java` | 38-80 | 각 헤더가 **있으면** 로깅. Spring Cloud Gateway는 **기본적으로 모든 요청 헤더를 그대로 전파**함. 별도 `requestBuilder.header()` 호출 없음 → **클라이언트가 보낸 헤더가 그대로 downstream(Aura-Platform)으로 전달됨** |
| `dwp-gateway/.../CorsConfig.java` | 35, 71-76 | CORS 허용 헤더: `Authorization,X-Tenant-ID,X-User-ID,X-Agent-ID,X-DWP-Source,X-DWP-Caller-Type,Content-Type,Accept,Last-Event-ID` |

**SSE 관련 필터 순서 (application.yml L198-211):**
- `RequiredHeaderFilter` (order -200): X-Tenant-ID 필수 검증
- `TenantIdNormalizationFilter` (order -199): X-Tenant-ID 숫자 정규화 (Aura 경로만)
- `HeaderPropagationFilter` (order -100): 헤더 전파 확인/로깅
- `SseResponseHeaderFilter`: SSE 응답 헤더 보장
- `SseReconnectionFilter`: Last-Event-ID 전파, Event ID 생성

**결론:** Gateway는 클라이언트가 보낸 `Authorization`, `X-Tenant-ID`, `X-User-ID`, `X-Agent-ID`, `X-DWP-Source`, `X-DWP-Caller-Type`, `Last-Event-ID`를 **그대로** Aura-Platform으로 전달한다. 별도 제거/변경 없음.

---

### A.2 X-User-ID 누락 시 BE/GW 거절 여부

| 구간 | 검증 여부 | 코드 근거 |
|------|----------|-----------|
| **Gateway** | ❌ **거절 안 함** | `RequiredHeaderFilter.java` L58-64: **X-Tenant-ID만** 필수 검증. X-User-ID 검증 없음. |
| **SSE 요청 (/api/aura/test/stream)** | ❌ **통과** | Gateway는 X-User-ID 없어도 400 반환하지 않음. |
| **HITL approve/reject** | ✅ **거절** | `dwp-main-service/.../HitlSecurityInterceptor.java` L82-86: `X-User-ID` 없으면 `BaseException(ErrorCode.UNAUTHORIZED, "X-User-ID header is required")` |

**HitlSecurityInterceptor 적용 경로:**
- `WebConfig.java`: `/aura/hitl/**` 경로에 `HitlSecurityInterceptor` 등록
- `HitlController` 경로: `/aura/hitl` (StripPrefix=1 적용 시 Gateway `/api/aura/hitl/**` → Main `/aura/hitl/**`)

**정리:**
- **SSE 스트림 요청**: X-User-ID 누락 시 **통과** (Gateway/Main/Aura 모두 검증 없음)
- **HITL approve/reject**: X-User-ID 누락 시 **401 Unauthorized** (Main Service HitlSecurityInterceptor)

---

### A.3 권장: 누락 시 400/401 + 로그(trace_id) 정책

| 헤더 | 현재 | 권장 |
|------|------|------|
| X-Tenant-ID | Gateway 400 (RequiredHeaderFilter) | 유지. `trace_id`(X-Trace-Id) 로그 포함 권장 |
| X-User-ID (SSE) | 검증 없음 | **선택**: SSE 요청 시 X-User-ID 필수화 시 Gateway 또는 Aura 진입 전 검증. 단, 에이전트/비로그인 시나리오 고려 필요 |
| X-User-ID (HITL) | Main 401 (HitlSecurityInterceptor) | 유지. 로그에 `traceId`, `requestId`, `tenantId`, `userId` 포함 확인 |

**로그 필드 점검:**
- `SseResponseHeaderFilter.java` L71-77: `log.info("SSE stream started: method={}, path={}, correlationId={}, agentId={}, tenantId={}, userId={}", ...)` → userId는 **있으면** 출력, 없으면 null
- `ApiCallHistoryFilter.java` L67-68: `tenantIdStr`, `userIdStr`, `agentId` 수집 → `sys_api_call_histories` 적재 (null 허용)

**권장 정책:**
1. X-User-ID 누락 시 400 반환을 **SSE 경로에 적용할지** 팀 합의 필요 (에이전트 호출 시 userId 없을 수 있음)
2. HITL approve/reject 실패 시 응답 body에 `traceId` 포함 (GlobalExceptionHandler 확인)
3. `log.warn` 시 `traceId`, `tenantId`, `path` 필수 포함

---

## B. Documents/OpenItems/Lineage 필터 파라미터 서버 반영 여부

### B.1 Documents API

**엔드포인트:** `GET /api/synapse/documents`  
**Controller:** `DocumentController.java` L35-56  
**Query DTO:** `DocumentQueryService.DocumentListQuery` (L536-560)

| FE 요구 필터 | 파라미터 명 | 타입 | Controller 지원 | QueryDSL 반영 | 비고 |
|-------------|-------------|------|-----------------|---------------|------|
| dateFrom | fromBudat | LocalDate (ISO) | ✅ L36 | ✅ L53-54 `h.budat.goe` | |
| dateTo | toBudat | LocalDate (ISO) | ✅ L37 | ✅ L55-56 `h.budat.loe` | |
| bukrs | bukrs | String | ✅ L38 | ✅ L59-68 (TenantScopeResolver 포함) | |
| status | statusCode | String | ✅ L45 | ✅ L84-86 `h.statusCode.eq` | |
| belnr | belnr | String | ✅ L39 | ✅ L69-71 | |
| gjahr | gjahr | String | ✅ L40 | ✅ L72-74 | |
| partyId | partyId | Long | ✅ L41 | ✅ L107-133 (lifnr/kunnr 서브쿼리) | |
| lifnr | lifnr | String | ✅ L47 | ✅ L156-165 | |
| kunnr | kunnr | String | ✅ L48 | ✅ L166-175 | |
| hasReversal | hasReversal | Boolean | ✅ L49 | ✅ L87-89 | |
| hasCase | hasCase | Boolean | ✅ L50 | ✅ L98-105 | |
| amountMin/Max | amountMin, amountMax | BigDecimal | ✅ L51-52 | ✅ L190-203 (서브쿼리 having) | |
| q (검색) | q | String | ✅ L53 | ✅ L90-96 (xblnr, bktxt, belnr, usnam, tcode) | |
| page/size/sort | page, size, sort | int, int, String | ✅ L54-56 | ✅ 페이징, 정렬(budat/belnr/updatedAt) | |

**추가 지원:** usnam, tcode, xblnr, integrityStatus

**예시 요청:**
```
GET /api/synapse/documents?fromBudat=2024-01-01&toBudat=2024-12-31&bukrs=1000&statusCode=POSTED&page=0&size=20&sort=budat,desc
X-Tenant-ID: 1
```

---

### B.2 OpenItems API

**엔드포인트:** `GET /api/synapse/open-items`  
**Controller:** `OpenItemController.java` L32-47  
**Query DTO:** `OpenItemQueryService.OpenItemListQuery` (L282-305)

| FE 요구 필터 | 파라미터 명 | 타입 | Controller 지원 | QueryDSL 반영 | 비고 |
|-------------|-------------|------|-----------------|---------------|------|
| bukrs | bukrs | String | ✅ L35 | ✅ L46-55 | |
| belnr | - | - | ❌ **미지원** | ❌ | **추가 스펙 필요** |
| gjahr | - | - | ❌ **미지원** | ❌ | **추가 스펙 필요** |
| vendor | lifnr | String | ✅ (lifnr) | ✅ L103-106 | lifnr = vendor code |
| status | status | String | ✅ L41 | ✅ L85-89 (CLEARED/OPEN/PARTIALLY_CLEARED) | |
| type | type | String | ✅ L34 | ✅ L80-84 (itemType) | |
| fromDueDate | fromDueDate | LocalDate | ✅ L37 | ✅ L59 | |
| toDueDate | toDueDate | LocalDate | ✅ L38 | ✅ L60 | |
| partyId | partyId | Long | ✅ L36 | ✅ L90-102 | |
| paymentBlock | paymentBlock | Boolean | ✅ L42 | ✅ L74-76 | |
| disputeFlag | disputeFlag | Boolean | ✅ L43 | ✅ L77-79 | |
| q | q | String | ✅ L44 | ✅ L108-122 | |
| page/size/sort | page, size, sort | int, int, String | ✅ L45-47 | ✅ | |

**미지원 필터 추가 스펙 (belnr, gjahr):**

1. **Controller 변경:** `OpenItemController.java`  
   - `@RequestParam(required = false) String belnr`  
   - `@RequestParam(required = false) String gjahr`  
   - `OpenItemListQuery.builder().belnr(belnr).gjahr(gjahr).build()`

2. **OpenItemListQuery 확장:** `OpenItemQueryService.OpenItemListQuery`  
   - `private String belnr;`  
   - `private String gjahr;`

3. **OpenItemQueryService 변경:** `findOpenItems` 내 predicate  
   - `if (query.getBelnr() != null && !query.getBelnr().isBlank()) predicate.and(oi.belnr.eq(query.getBelnr()));`  
   - `if (query.getGjahr() != null && !query.getGjahr().isBlank()) predicate.and(oi.gjahr.eq(query.getGjahr()));`

4. **FiOpenItem 엔티티:** `belnr`, `gjahr` 컬럼 존재 확인 (fi_open_item 테이블)

---

### B.3 Lineage API

**엔드포인트:** `GET /api/synapse/lineage`  
**Controller:** `LineageController.java` L28-46  
**Query DTO:** `LineageQueryService.LineageQuery` (L250-260)

| FE 요구 필터 | 파라미터 명 | 타입 | Controller 지원 | QueryDSL/로직 반영 | 비고 |
|-------------|-------------|------|-----------------|---------------------|------|
| belnr | docKey (복합) | String | docKey | docKey 파싱 시 bukrs-belnr-gjahr | docKey=`1000-1900000001-2024` 형식 |
| gjahr | docKey (복합) | String | docKey | 동일 | 별도 belnr/gjahr 미지원 |
| caseId | caseId | Long | ✅ L30 | ✅ L51-58 | |
| docKey | docKey | String | ✅ L31 | ✅ L60-62 | bukrs-belnr-gjahr |
| rawEventId | rawEventId | Long | ✅ L32 | ✅ L63-65 | |
| partyId | partyId | Long | ✅ L33 | ✅ L66-72 | |
| asOf | asOf | Instant | ✅ L34 | ✅ L103-115 (time-travel) | |

**제약:** `caseId`, `docKey`, `rawEventId`, `partyId` 중 **최소 1개 필수** (L41-44 검증)

**belnr/gjahr 단독 요청:**  
- 현재 **docKey**로만 가능. 예: `docKey=1000-1900000001-2024`  
- belnr, gjahr 단독 파라미터 추가 시: Controller/LineageQuery 확장 후, docKey 없이 belnr+gjahr(+bukrs) 조합으로 fi_doc_header 조회 후 rawEventId/caseId 역추적 로직 필요.

**예시 요청:**
```
GET /api/synapse/lineage?caseId=1
GET /api/synapse/lineage?docKey=1000-1900000001-2024
GET /api/synapse/lineage?partyId=1&asOf=2024-06-15T12:00:00Z
X-Tenant-ID: 1
```

---

## C. HITL E2E 사이클 (Backend 측)

### C.1 HITL 요청 생성

| 항목 | 내용 |
|------|------|
| **시점** | Aura-Platform이 에이전트 실행 중 승인 필요 판단 시 |
| **저장 위치** | Redis `hitl:request:{requestId}` (TTL 30분) |
| **코드** | `HitlManager.saveApprovalRequest()` L54-134 |
| **REST 노출** | ❌ **없음** | `HitlController`에 saveApprovalRequest 호출 엔드포인트 없음. Aura-Platform은 **별도 통합 경로**(다른 레포/서비스)로 호출하거나, main-service 내부 API가 있을 수 있음. |

**저장 데이터 (requestDataMap):**  
requestId, sessionId, userId, tenantId, actionType, context, status=pending, createdAt, taskId(선택)

---

### C.2 approve/reject 처리

| 항목 | 내용 |
|------|------|
| **엔드포인트** | `POST /api/aura/hitl/approve/{requestId}`, `POST /api/aura/hitl/reject/{requestId}` |
| **Controller** | `HitlController.java` L46-98 |
| **상태 업데이트** | Redis `hitl:request:{requestId}` JSON에 status=approved/rejected, approvedBy/rejectedBy, approvedAt/rejectedAt, reason 저장 |
| **Resume 트리거** | **Redis Pub/Sub** `redisTemplate.convertAndSend("hitl:channel:" + sessionId, signalJson)` (L228-229, L314-315) |
| **직접 호출/이벤트/outbox** | Pub/Sub만 사용. Aura-Platform이 `hitl:channel:{sessionId}` 구독하여 신호 수신 후 실행 재개 |

**신호 형식 (approve):**
```json
{"type":"approval","requestId":"...","status":"approved","timestamp":...}
```

**신호 형식 (reject):**
```json
{"type":"rejection","requestId":"...","status":"rejected","reason":"...","timestamp":...}
```

**멱등:** 이미 approved/rejected면 Redis·신호 없이 기존 sessionId/status 반환, 409 Conflict (L203-207, L281-288)

---

### C.3 결과 반영 및 감사(audit) 저장

| 이벤트 | 저장 위치 | 코드 근거 |
|--------|----------|-----------|
| HITL_REQUEST | `com_audit_logs` (dwp_auth) | `HitlManager.recordHitlAudit(..., "HITL_REQUEST", ...)` L126 |
| HITL_APPROVE | `com_audit_logs` | `HitlManager.recordHitlAudit(..., "HITL_APPROVE", ...)` L234 |
| HITL_REJECT | `com_audit_logs` | `HitlManager.recordHitlAudit(..., "HITL_REJECT", ...)` L319-320 |

**호출 경로:**  
`HitlManager.recordHitlAudit` → `AuthServerAuditClient.recordAuditLog` → `POST /internal/audit-logs` → `AuditLogService.recordAuditLog` → `com_audit_logs` INSERT

**metadata 저장:** requestId, sessionId, actionType, timestamp, (reject 시) reason

**Synapse audit_event_log와의 관계:**  
- HITL 감사는 **com_audit_logs** (auth-server)에 저장됨  
- **audit_event_log** (dwp_aura)는 Synapse Case/Action 이벤트용. HITL approve/reject는 com_audit_logs에만 기록됨.

**최소 저장 규칙 (현재 충족):**
- tenantId, actorUserId, action(HITL_REQUEST/HITL_APPROVE/HITL_REJECT), resourceType=HITL, metadata(requestId, sessionId, actionType, timestamp, reason)

**보완 제안:**
- metadata에 `caseId`(연관 케이스), `taskId`(AgentTask) 포함 시 추적성 향상
- traceId를 metadata에 포함하면 로그 연계 용이

---

## D. 운영 진단 포인트

### D.1 로그 필드

| 구간 | 로그 필드 | 파일/라인 |
|------|----------|-----------|
| SSE 시작 | method, path, correlationId, agentId, tenantId, userId | `SseResponseHeaderFilter.java` L71-77 |
| API 호출 이력 | tenantId, userId, agentId, path, statusCode, latencyMs, traceId | `ApiCallHistoryFilter.java` L67-68, L87-90 |
| HITL approve/reject | requestId, sessionId, approvedBy/rejectedBy, reason | `HitlManager.java` L231-232, L318-319 |
| HITL 보안 실패 | path, tenantId, userId (JWT vs Header) | `HitlSecurityInterceptor.java` L85-92 |

**권장:** 모든 HITL/SSE 관련 로그에 `traceId`(X-Trace-Id), `tenantId`, `userId`(가능 시) 포함

---

### D.2 curl 예시 3개

#### 1) SSE stream 요청 (헤더 포함)

```bash
curl -X POST "http://localhost:8080/api/aura/test/stream" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "X-DWP-Source: AURA" \
  -H "X-DWP-Caller-Type: AGENT" \
  -H "Authorization: Bearer <JWT>" \
  -d '{"prompt":"test","context":{}}'
```

#### 2) HITL approve 요청

```bash
curl -X POST "http://localhost:8080/api/aura/hitl/approve/req-12345" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "Authorization: Bearer <JWT>" \
  -d '{"userId":"1"}'
```

#### 3) documents / open-items / lineage 필터 조회 요청

```bash
# Documents
curl -X GET "http://localhost:8080/api/synapse/documents?fromBudat=2024-01-01&toBudat=2024-12-31&bukrs=1000&statusCode=POSTED&page=0&size=20" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <JWT>"

# OpenItems
curl -X GET "http://localhost:8080/api/synapse/open-items?bukrs=1000&status=OPEN&type=AR&page=0&size=20" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <JWT>"

# Lineage
curl -X GET "http://localhost:8080/api/synapse/lineage?caseId=1" \
  -H "X-Tenant-ID: 1" \
  -H "Authorization: Bearer <JWT>"
```

---

## 요약 및 보완 권장사항

| 영역 | 현재 상태 | 보완 권장 |
|------|----------|-----------|
| **SSE X-Tenant-ID** | Gateway 400 필수 | 유지 |
| **SSE X-User-ID** | 검증 없음, 통과 | 에이전트 시나리오 고려 후 선택적 필수화 |
| **HITL X-User-ID** | Main 401 필수 | 유지 |
| **Documents 필터** | dateFrom/dateTo/bukrs/status/belnr/gjahr 등 전부 지원 | - |
| **OpenItems belnr/gjahr** | 미지원 | Controller/Query/Repo 확장 스펙 적용 |
| **Lineage belnr/gjahr** | docKey(bukrs-belnr-gjahr)로만 지원 | 단독 belnr/gjahr 필요 시 스펙 확장 |
| **HITL 요청 생성** | REST 없음, Redis 저장 | Aura 연동 방식 문서화 또는 POST /aura/hitl/requests 검토 |
| **HITL 감사** | com_audit_logs에 HITL_REQUEST/APPROVE/REJECT 기록 | metadata에 caseId, taskId, traceId 추가 권장 |

---

*본 문서는 현재 코드 기준으로 작성되었으며, 추측 없이 파일/라인/엔드포인트/테이블명을 근거로 기술함.*
