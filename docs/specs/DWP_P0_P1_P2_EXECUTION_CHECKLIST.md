# DWP Backend P0/P1/P2 실행 체크리스트 + DoD + 검증용 curl/시나리오

**작성일**: 2026-01-29  
**입력 근거**: DWP_BACKEND_E2E_INSPECTION_REPORT.md, 코드베이스 분석 (back.txt 동등)  
**목적**: 정책/계약/지원 파라미터 확정, 보완 지점 변경 포인트 명세, 팀 핸드오프용 완결 문서

---

## SSE X-User-ID 비강제 — 의도된 설계 여부 및 강제 전환 리스크

### 현재 사실 (코드 근거)
- `RequiredHeaderFilter.java` L58-64: **X-Tenant-ID만** 필수 검증. X-User-ID 검증 없음.
- `HitlSecurityInterceptor.java` L82-86: HITL approve/reject 시 **X-User-ID 필수** (없으면 401).

### 의도된 설계인지 (문서 근거)
| 문서 | 내용 |
|------|------|
| BE_FE_CONTRACT_UPDATE_result.md | "X-User-ID: 필수 아님. CORS 허용 + 전파만." |
| FRONTEND_VERIFICATION_Q1_Q5_CONTRACT_AND_OPENAPI.md | "에이전트 호출 시 userId 없을 수 있음" |
| AURA_GATEWAY_SINGLE_POINT_SPEC.md | X-User-ID는 "전파 헤더"로만, 필수 헤더에 미포함 |

**결론**: SSE에서 X-User-ID 비강제는 **의도된 설계**로 확정.  
- **이유**: 에이전트가 스트림 시작 시 사용자 로그인 전/에이전트 세션만으로 호출하는 시나리오 허용. HITL 시점에만 사용자 필요.

### 강제 전환 시 리스크/영향
| 항목 | 내용 |
|------|------|
| **영향 범위** | `/api/aura/**` SSE 요청 전부. Aura-Platform, 프론트엔드 SSE 클라이언트 |
| **리스크** | 에이전트가 사용자 없이 SSE를 시작하는 플로우가 있으면 **400으로 차단**됨. Aura 연동 수정 필요 |
| **필요 작업** | RequiredHeaderFilter에 `/api/aura/**` 경로 시 X-User-ID 검증 추가. Aura/FE에서 SSE 시작 전 X-User-ID 전송 보장 |
| **권장** | 현행 유지(옵션 A). 강제 전환(옵션 B) 시 Aura/FE 영향 사전 확인 필수 |

---

## (A) P0/P1/P2 팀별 실행 체크리스트 — BE 관점

### P0 (계약/차단)

#### P0-1 SSE 표준 헤더(특히 X-User-ID) "강제 수준" 정책 결정안

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | `RequiredHeaderFilter.java` L58-64: X-Tenant-ID만 필수. X-User-ID 검증 없음. `HitlSecurityInterceptor`는 HITL 경로에서만 X-User-ID 필수. |
| **영향** | SSE: X-User-ID 없어도 통과. HITL: X-User-ID 없으면 401. |
| **결정 필요** | X-User-ID를 Gateway에서 SSE 경로에 강제할지 여부. |
| **정책 결정안** | **옵션 A (현행 유지)**: SSE에서 X-User-ID 선택. 에이전트 시나리오 허용. **옵션 B (강제)**: `/api/aura/**` 경로 시 X-User-ID 필수 → 400. |
| **변경 포인트** | (옵션 B 시) `RequiredHeaderFilter.java`: `/api/aura/` 경로 체크 후 X-User-ID null/empty면 400 + body `{"error":"X-User-ID is required","traceId":"..."}` |
| **검증** | S6, S7 시나리오. 옵션 B 시 SSE X-User-ID 누락 → 400 확인. |

#### P0-2 SSE upstream(Aura) 헤더 전달 목록/누락/마스킹 규칙 확정

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | `HeaderPropagationFilter.java` L26-31: Authorization, X-Tenant-ID, X-DWP-Source, X-DWP-Caller-Type, X-User-ID, X-Agent-ID, Last-Event-ID. Spring Cloud Gateway는 **클라이언트 헤더를 그대로 전파**. 마스킹 없음. |
| **영향** | 누락 시 null로 Aura에 전달. Aura가 null 처리 방식 결정. |
| **결정 필요** | 누락/마스킹 규칙 문서화. |
| **확정 규칙** | **전달 목록**: Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, X-DWP-Source, X-DWP-Caller-Type, Last-Event-ID. **누락**: 헤더 없으면 전달 안 함(Spring 기본). **마스킹**: 없음. 민감 정보(PII)는 Authorization 토큰 내부에 있으며 Gateway는 내용 변경 안 함. |
| **변경 포인트** | 없음. 현행 유지. |
| **검증** | curl로 헤더 포함 요청 후 Aura 로그에서 수신 확인. |

---

### P1 (기능/일관성)

#### P1-1 Documents 파라미터 스펙 확정 + FE-BE 매핑표

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | `services/synapsex-service/.../DocumentController.java`, `DocumentQueryService.java`. fromBudat, toBudat, bukrs, statusCode, belnr, gjahr, partyId, lifnr, kunnr, hasReversal, hasCase, amountMin/Max, q, page, size, sort 지원. QueryDSL 반영됨. |
| **영향** | FE가 잘못된 param 명 사용 시 필터 미동작. |
| **결정 필요** | FE-BE 매핑표 확정. |
| **변경 포인트** | 없음. (C) 계약표 준수만 필요. |
| **검증** | S1, C1. |

#### P1-2 OpenItems 지원 필터 목록 확정 + FE 매핑표

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | `services/synapsex-service/.../OpenItemController.java`, `OpenItemQueryService.java`. bukrs, type, status, fromDueDate, toDueDate, partyId, lifnr, kunnr, paymentBlock, disputeFlag, q, page, size, sort 지원. **belnr, gjahr 미지원**. |
| **영향** | FE가 belnr/gjahr로 목록 필터 요청 시 무시됨. |
| **결정 필요** | FE가 보내야 할 param 표 확정. |
| **변경 포인트** | 없음. (C) 계약표 준수. |
| **검증** | S2, C2. |

#### P1-3 OpenItems belnr/gjahr 필요 시 BE 확장 스펙

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | `FiOpenItem` 엔티티에 belnr, gjahr 컬럼 존재. `OpenItemController`/`OpenItemListQuery`에 belnr, gjahr param 없음. |
| **영향** | belnr/gjahr 필터 필요 시 FE 요구 반영 불가. |
| **결정 필요** | FE에서 OpenItems 목록 조회 시 belnr/gjahr 필터가 필요한가? |
| **변경 포인트** | (필요 시) ① `OpenItemController.java`: `@RequestParam(required = false) String belnr`, `String gjahr` 추가. ② `OpenItemListQuery`: `belnr`, `gjahr` 필드 추가. ③ `OpenItemQueryService.findOpenItems`: `predicate.and(oi.belnr.eq(...))`, `predicate.and(oi.gjahr.eq(...))` 추가. |
| **검증** | belnr, gjahr param 포함 조회 후 결과 필터링 확인. |

#### P1-4 Lineage 입력 표준 (docKey 고정 vs belnr/gjahr 병행)

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | `services/synapsex-service/.../LineageController.java`, `LineageQueryService.java`. caseId, docKey, rawEventId, partyId, asOf 지원. docKey=bukrs-belnr-gjahr. belnr/gjahr 단독 미지원. |
| **영향** | FE가 belnr/gjahr만으로 조회 불가. docKey 조합 필요. |
| **결정 필요** | docKey 고정 vs belnr/gjahr 병행. |
| **결정안 제시** | **옵션 A (docKey 고정)**: FE는 docKey=bukrs-belnr-gjahr 형식만 사용. 구현 비용 없음. **옵션 B (belnr/gjahr 병행)**: belnr, gjahr (필요 시 bukrs) 단독 param 추가. fi_doc_header 역추적 로직 추가. 구현 비용 증가. |
| **변경 포인트** | (옵션 B 시) Controller/LineageQuery 확장, 역추적 로직 추가. |
| **검증** | S3, C3. |

---

### P2 (운영/감사)

#### P2-1 HITL/Audit 저장 책임 분리 문서화

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | HITL_REQUEST/APPROVE/REJECT → `com_audit_logs` (dwp_auth). Synapse Case/Action → `audit_event_log` (dwp_aura). `HitlManager.recordHitlAudit` L126, L234, L319-320. |
| **영향** | 감사 조회 시 저장 위치 혼동 가능. |
| **결정 필요** | 없음. 문서화만. |
| **변경 포인트** | 없음. |
| **검증** | S4, S5. com_audit_logs, audit_event_log 각각 확인. |

#### P2-2 Contract Test(선택) 도입 제안

| 구분 | 내용 |
|------|------|
| **현재 상태(근거)** | 단위/통합 테스트 존재. Spring Cloud Contract / Pact 미도입. |
| **영향** | FE-BE 계약 변경 시 수동 검증에 의존. |
| **결정 필요** | 도입 여부. 우선순위 낮음. |
| **제안** | Spring Cloud Contract 또는 Pact 도입 시 헤더 필수 여부, 필터 param, 응답 스키마를 자동 검증 가능. Phase 2 이후 검토. |
| **변경 포인트** | 없음. |
| **검증** | - |

---

## (B) Definition of Done (DoD)

### P0-1: SSE X-User-ID 강제 수준

| DoD 항목 | 내용 |
|----------|------|
| **현재 구현 증거** | `dwp-gateway/.../RequiredHeaderFilter.java` L58-64. X-Tenant-ID만 검증. |
| **정책/보완안 적용 후 기대 동작** | (옵션 A) 현행 유지. (옵션 B) X-User-ID 누락 → 400 + `{"error":"X-User-ID is required","traceId":"..."}` |
| **반환 코드 정책** | X-Tenant-ID: 400. X-User-ID(강제 시): 400. |
| **운영 로그 필드** | tenantId, userId(가능 시), traceId, path |
| **장애/누락 시 대응** | 400 body에 traceId 포함. log.warn에 traceId, tenantId, path 포함. |

### P1-1: Documents 필터 스펙

| DoD 항목 | 내용 |
|----------|------|
| **현재 구현 증거** | `DocumentController.java` L35-56, `DocumentQueryService.java` L49-203 |
| **정책/보완안 적용 후 기대 동작** | (C) 계약표 준수 시 필터 정상 동작 |
| **반환 코드 정책** | X-Tenant-ID 없음: 400. 잘못된 param 타입: 400. |
| **운영 로그 필드** | tenantId, path, query params(민감 정보 제외) |
| **장애/누락 시 대응** | 400 반환. 에러 body에 param 명시. |

### P1-2 / P1-3: OpenItems 필터

| DoD 항목 | 내용 |
|----------|------|
| **현재 구현 증거** | `OpenItemController.java` L32-47, `OpenItemQueryService.java` L42-150, `OpenItemListQuery` L282-305 |
| **정책/보완안 적용 후 기대 동작** | (C) 계약표 준수. belnr/gjahr 필요 시 P1-3 스펙 적용 |
| **반환 코드 정책** | X-Tenant-ID 없음: 400. |
| **운영 로그 필드** | tenantId, path |
| **장애/누락 시 대응** | 400 반환. |

### P1-4: Lineage 파라미터

| DoD 항목 | 내용 |
|----------|------|
| **현재 구현 증거** | `LineageController.java` L28-46, `LineageQueryService.java` L40-115 |
| **정책/보완안 적용 후 기대 동작** | caseId/docKey/rawEventId/partyId 중 최소 1개 필수. 미충족 시 400 |
| **반환 코드 정책** | 파라미터 미충족: 400 (IllegalArgumentException) |
| **운영 로그 필드** | tenantId, path, caseId/docKey |
| **장애/누락 시 대응** | 400 + "최소 1개의 쿼리 파라미터가 필요합니다" 메시지. |

### P2-1: HITL/Audit 저장 책임

| DoD 항목 | 내용 |
|----------|------|
| **현재 구현 증거** | `HitlManager.java` L126, L234, L319-320. `AuthServerAuditClient` → `/internal/audit-logs` |
| **정책/보완안 적용 후 기대 동작** | HITL → com_audit_logs. Synapse Case/Action → audit_event_log. |
| **반환 코드 정책** | - |
| **운영 로그 필드** | requestId, sessionId, tenantId, userId, action |
| **장애/누락 시 대응** | recordHitlAudit 실패 시 log.warn. 메인 로직은 성공 유지. |

---

## (C) FE/BE 계약표

### 1) Documents: FE 필터 → BE query param

| FE 필터 | BE query param (정확한 이름) | 타입 | 필수 | 예시 |
|---------|------------------------------|------|------|------|
| dateFrom | **fromBudat** | LocalDate (ISO 8601) | N | 2024-01-01 |
| dateTo | **toBudat** | LocalDate (ISO 8601) | N | 2024-12-31 |
| bukrs | bukrs | String | N | 1000 |
| status | **statusCode** | String | N | POSTED |
| belnr | belnr | String | N | 1900000001 |
| gjahr | gjahr | String | N | 2024 |
| partyId | partyId | Long | N | 1 |
| vendor | **lifnr** | String | N | 0000100001 |
| customer | **kunnr** | String | N | 0000100002 |
| hasReversal | hasReversal | Boolean | N | true |
| hasCase | hasCase | Boolean | N | true |
| amountMin | amountMin | BigDecimal | N | 100.00 |
| amountMax | amountMax | BigDecimal | N | 10000.00 |
| q | q | String | N | invoice |
| page | page | int (0-based) | N | 0 |
| size | size | int | N | 20 |
| sort | sort | String (field,dir) | N | budat,desc |

**추가 지원**: usnam, tcode, xblnr, integrityStatus

---

### 2) OpenItems: FE 필터 → BE 지원 여부 + 대체키/비고 + 미지원 시 구현 제안

| FE 필터 | BE 지원 | BE query param | 대체키/비고 | 미지원 시 구현 제안 |
|---------|---------|----------------|-------------|---------------------|
| bukrs | Y | bukrs | - | - |
| type | Y | type | AP/AR | - |
| status | Y | status | OPEN, CLEARED, PARTIALLY_CLEARED | - |
| dueFrom | Y | **fromDueDate** | toDueDate와 쌍 | - |
| dueTo | Y | **toDueDate** | - | - |
| partyId | Y | partyId | - | - |
| vendor | Y | **lifnr** | vendor code | - |
| customer | Y | **kunnr** | customer code | - |
| belnr | **N** | - | - | P1-3: Controller/DTO/Repo where 조건 추가 |
| gjahr | **N** | - | - | P1-3: Controller/DTO/Repo where 조건 추가 |
| docKey | N | - | 목록 필터 미지원. 상세는 `/{bukrs}/{belnr}/{gjahr}/{buzei}` | belnr+gjahr 추가 시 docKey 파싱 가능 |
| page/size/sort | Y | page, size, sort | - | - |

---

### 3) Lineage: 입력 파라미터 표준

| 파라미터 | 타입 | 필수 | 설명 | 예시 |
|----------|------|------|------|------|
| caseId | Long | 조건부* | 케이스 ID | 1 |
| docKey | String | 조건부* | bukrs-belnr-gjahr | 1000-1900000001-2024 |
| rawEventId | Long | 조건부* | SAP raw event ID | 123 |
| partyId | Long | 조건부* | 거래처 ID | 1 |
| asOf | Instant (ISO 8601) | N | time-travel 시점 | 2024-06-15T12:00:00Z |

**규칙**: caseId, docKey, rawEventId, partyId 중 **최소 1개 필수**. 모두 없으면 400.

---

## (D) 검증용 curl 세트 (최소 5개)

**공통**: `{GATEWAY_HOST}` = `http://localhost:8080`, `{JWT}` = 발급된 토큰.

### C1 Documents 필터 조회

```bash
curl -X GET "{GATEWAY_HOST}/api/synapse/documents?fromBudat=2024-01-01&toBudat=2024-12-31&bukrs=1000&statusCode=POSTED&page=0&size=20&sort=budat,desc" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "Content-Type: application/json"
```

### C2 OpenItems 필터 조회

```bash
curl -X GET "{GATEWAY_HOST}/api/synapse/open-items?bukrs=1000&type=AR&status=OPEN&fromDueDate=2024-01-01&toDueDate=2024-12-31&lifnr=0000100001&kunnr=0000100002&page=0&size=20" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "Content-Type: application/json"
```

### C3 Lineage docKey 조회

```bash
curl -X GET "{GATEWAY_HOST}/api/synapse/lineage?docKey=1000-1900000001-2024" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "Content-Type: application/json"
```

### C4 HITL approve

```bash
curl -X POST "{GATEWAY_HOST}/api/aura/hitl/approve/{requestId}" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "Content-Type: application/json" \
  -d '{"userId":"1"}'
```

### C5 HITL reject

```bash
curl -X POST "{GATEWAY_HOST}/api/aura/hitl/reject/{requestId}" \
  -H "Authorization: Bearer {JWT}" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "Content-Type: application/json" \
  -d '{"userId":"1","reason":"사용자 거절"}'
```

**SSE 재연결 시 Last-Event-ID 예시:**
```bash
curl -X POST "{GATEWAY_HOST}/api/aura/test/stream" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "X-Tenant-ID: 1" \
  -H "X-User-ID: 1" \
  -H "X-Agent-ID: agent-session-123" \
  -H "Last-Event-ID: event_123" \
  -H "Authorization: Bearer {JWT}" \
  -d '{"prompt":"test","context":{}}'
```

---

## (E) 수동 검증 시나리오 (운영자/QA 관점)

| ID | 목적 | 절차 |
|----|------|------|
| **S1** | Documents 필터 정상 동작 확인 | C1 실행 → 200. items 배열에 fromBudat/toBudat/bukrs/statusCode 조건 반영 확인. |
| **S2** | OpenItems 필터 정상 동작 확인 | C2 실행 → 200. bukrs/type/status/dueDate 조건 반영 확인. |
| **S3** | Lineage docKey 조회 정상 동작 확인 | C3 실행 → 200. journey, evidencePanel 반환 확인. |
| **S4** | HITL approve E2E (Redis 상태 + audit 기록) 확인 | Redis에 `hitl:request:{requestId}` 생성 후 C4 실행 → 200. Redis status=approved, com_audit_logs에 HITL_APPROVE 기록 확인. |
| **S5** | HITL reject E2E 확인 | Redis에 `hitl:request:{requestId}` 생성 후 C5 실행 → 200. Redis status=rejected, com_audit_logs에 HITL_REJECT 기록 확인. |
| **S6** | X-User-ID 누락 시 HITL 401 확인 | C4에서 X-User-ID 헤더 제거 → 401 예상. |
| **S7** | X-Tenant-ID 누락 시 SSE 400 확인 (현 정책 기준) | `POST {GATEWAY_HOST}/api/aura/test/stream` 요청 시 X-Tenant-ID 헤더 제거 → 400 예상. |

---

*본 문서는 코드/문서 근거로 확정 작성. 추정 없음. 팀 핸드오프용.*
