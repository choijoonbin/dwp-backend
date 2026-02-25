# Level 4 Interface & Data Flow Integrity Audit (Backend)

> **대상**: 프론트엔드·Aura 에이전트 간 데이터 규격 불일치 방지  
> **검사 일자**: 2026-02-11

---

## 1. [Interface Spec] 시스템 간 규격 확인

### 1.1 To Aura — Phase2 분석 요청 시 body_evidence.doc_id / item_id

| 항목 | 상태 | 비고 |
|------|------|------|
| evidence payload 구성 | ⚠️ **부분 통과** | `buildEvidenceSnapshot()`에서 `evidence`, `ragRefs`, `document`(header+items), `openItems`, `partyIds`, `lineage`, `policies` 전달. `document.docKey` 포함. |
| body_evidence.doc_id / item_id | ✅ **구현 완료** | `AuraAnalyzeRequest.bodyEvidence`(JSON: `body_evidence`)에 `doc_id`(belnr), `item_id`(buzei) 명시 전송. `CaseAnalysisService.buildBodyEvidence(agentCase)`에서 세팅. |

### 1.2 From Aura — Redis workbench:* JSON 역직렬화

| 항목 | 상태 | 비고 |
|------|------|------|
| NotificationDto 필드 누락 없이 매핑 | ✅ **통과** | Redis 메시지는 **직렬역직렬이 아닌 매핑**으로 처리. `NotificationRedisSubscriber`에서 채널·payload에 따라 title, content, type, occurredAt 구성 후 `NotificationDto`로 전달. |
| Aura 규격 category / message / timestamp | ✅ **통과** | `workbench:*` 기타 채널 수신 시 **category → type**, **message → content**, **timestamp → occurredAt** 폴백 매핑 추가됨. Aura가 해당 필드로 발행해도 누락 없이 반영. |

---

## 2. [Data Logic] 1차 테스트용 데이터 무결성

### 2.1 Item Mapping — fi_doc_header + fi_doc_item 1:N

| 항목 | 상태 | 비고 |
|------|------|------|
| 조인 후 배열 반환 | ✅ **통과** | `DocumentQueryService.buildDocumentDetail()`: `FiDocHeader` 1건 + `FiDocItem` 목록 조인 후 `DocumentDetailDto`의 `header`(1) + `items`(List)로 반환. 1:N이 배열 형태로 유지됨. |
| API 엔드포인트 | ✅ | `GET /api/synapse/documents/detail?bukrs=&belnr=&gjahr=` 또는 path 형태. 응답: `{ header: {...}, items: [...] }`. |

### 2.2 Audit Trace — agent_case_action_history.actor_id

| 항목 | 상태 | 비고 |
|------|------|------|
| actor_id와 세션 사용자 일치 | ✅ **통과** | `ActionCommandService`에서 `actorUserId`는 Controller의 `@RequestHeader(HeaderConstants.X_USER_ID)`로 전달. `actorId = actorUserId != null ? "USER:" + actorUserId : "SYSTEM"` 로 저장. 현재 요청의 사용자 정보와 일치. |
| FE 책임 | - | 프론트는 승인/거절 요청 시 **X-User-ID**에 현재 세션 사용자 ID를 넣어 전달해야 함. |

---

## 3. [Bridge] 알림 전송 보장

### 3.1 WebSocket Pub — 스레드 세이프

| 항목 | 상태 | 비고 |
|------|------|------|
| Redis 수신 즉시 STOMP 브로드캐스트 | ✅ **통과** | `NotificationRedisSubscriber.onMessage()` → `NotificationBroadcastService.saveAndBroadcast()` → DB 저장 후 `SimpMessagingTemplate.convertAndSend("/topic/notifications", dto)`. |
| Thread-safety | ✅ **통과** | `SimpMessagingTemplate`은 스레드 세이프. 공유 가변 상태 없음. Redis 리스너는 메시지 단위로 호출되며, 각 호출이 독립적으로 save + convertAndSend 수행. |

---

## 4. 최종 API 규격 리스트 (프론트/Aura 전달용)

### 4.1 To Aura (Backend → Aura)

| Endpoint (Aura 기준) | Method | Req Body 요약 | Res Body 요약 |
|----------------------|--------|----------------|----------------|
| `/aura/cases/{caseId}/analysis-runs` | POST | caseId, runId, mode, requestedBy, **evidence**(JsonNode: evidence, ragRefs, document{header,items,docKey}, openItems, partyIds, lineage, policies), options | status, caseId, runId, streamUrl, message |
| (동일) | - | **body_evidence**: `{ "doc_id": "BELNR_VALUE", "item_id": "BUZEI_VALUE" }` — Phase2 시 명시 전송. | - |

### 4.2 From Aura (Redis workbench:*)

| 채널 패턴 | 발행 측 | 수신 후 처리 |
|-----------|---------|--------------|
| workbench:* | Aura / Backend | JSON 수신 → NotificationDto 매핑 (title, content, type, occurredAt). **category/message/timestamp** 있으면 그대로 type/content/occurredAt에 매핑 → DB 저장 + `/topic/notifications` 브로드캐스트. |

### 4.3 To Frontend (Backend → FE)

| Endpoint | Method | Req | Res Body 요약 |
|----------|--------|-----|----------------|
| `/api/synapse/cases/{id}` | GET | - | caseId, status, keys, links, **fi_doc_items**(배열: buzei, hkont, wrbtr, sgtxt 등), evidence, reasoning, action |
| `/api/synapse/documents/detail` | GET | bukrs, belnr, gjahr (query 또는 path) | header(1), **items**(배열), derived, reversalChain, integrityChecks, linkedObjects |
| `/api/synapse/actions/{actionId}/approve` | POST | (optional) { comment } | ApiResponse&lt;ActionListRowDto&gt; |
| `/api/synapse/actions/{actionId}/reject` | POST | (optional) { comment } | ApiResponse&lt;ActionListRowDto&gt; |
| `/api/synapse/workbench/cases/{caseId}/history` | GET | - | ApiResponse&lt;List&lt;CaseActionHistoryItemDto&gt;&gt; (action_at DESC) |
| `/api/synapse/notifications` | GET | X-Tenant-ID, page, size, sort | ApiResponse&lt;Page&lt;NotificationDto&gt;&gt; |
| WebSocket `/ws/notifications` | (STOMP) | 구독 `/topic/notifications` | NotificationDto (id, tenantId, title, content, **type**, channel, occurredAt, createdAt, readAt, payload) |

### 4.4 From Frontend (FE → Backend)

| Endpoint | Method | Req Body 요약 | 비고 |
|----------|--------|----------------|------|
| `/api/synapse/cases/{caseId}/analysis-runs` | POST | mode, requestedBy, (optional) evidenceSnapshot | X-Tenant-ID, X-User-ID 권장 |
| `/api/synapse/actions/{actionId}/approve` | POST | { comment?: string } | X-User-ID = 현재 세션 사용자(actor_id에 반영) |
| `/api/synapse/actions/{actionId}/reject` | POST | { comment?: string } | 동일 |

---

## 5. 통과 요약

| 구분 | 통과 | 부분/미구현 |
|------|------|-------------|
| Interface Spec (To Aura) | evidence/document 구조 전달, body_evidence.doc_id/item_id 전송 | - |
| Interface Spec (From Aura) | ✅ category/message/timestamp 매핑 | - |
| Data Logic (Item Mapping) | ✅ header + items 배열 | - |
| Data Logic (Audit actor_id) | ✅ X-User-ID 기반 actor_id | - |
| Bridge (WebSocket Thread-safe) | ✅ | - |

---

## 5. 규격 JSON 샘플 (수정 반영)

### 5.1 [To Aura] Phase2 분석 요청 — body_evidence 포함

**Endpoint**: `POST /aura/cases/{caseId}/analysis-runs` (Backend → Aura 호출 시)

```json
{
  "caseId": 12345,
  "runId": "550e8400-e29b-41d4-a716-446655440000",
  "mode": "phase2",
  "requestedBy": "USER:user-uuid",
  "evidence": {
    "evidence": { },
    "ragRefs": [ ],
    "document": {
      "type": "DOCUMENT",
      "docKey": "1000-1900000001-2024",
      "header": { "bukrs": "1000", "belnr": "1900000001", "gjahr": "2024", "budat": "2024-01-15", "xblnr": "" },
      "items": [ { "buzei": "001", "hkont": "0000100000", "wrbtr": 1000.00, "sgtxt": "Sample" } ]
    },
    "openItems": [ ],
    "partyIds": [ ],
    "lineage": { },
    "policies": [ ]
  },
  "options": { "model": "default", "policyVersion": "v1" },
  "body_evidence": {
    "doc_id": "1900000001",
    "item_id": "001"
  }
}
```

- `body_evidence.doc_id`: 전표 번호(BELNR).
- `body_evidence.item_id`: 전표 라인(BUZEI). 해당 라인만 규정 준수 판단 시 사용.

### 5.2 [To Frontend] GET 케이스 상세 — fi_doc_items 및 라인 필드

**Endpoint**: `GET /api/synapse/cases/{id}`

응답 내 **fi_doc_items** 배열은 `evidence.documentOrOpenItem.items`와 동일한 라인 목록이며, FE 규격 필드 **buzei**, **hkont**, **wrbtr**, **sgtxt**를 포함한다.

```json
{
  "caseId": 12345,
  "status": "OPEN",
  "keys": { "sourceType": "DOCUMENT", "bukrs": "1000", "belnr": "1900000001", "gjahr": "2024", "buzei": "001", "dedupKey": null },
  "links": { "openItems": "/api/synapse/open-items?caseId=12345", "lineage": "/api/synapse/lineage?caseId=12345" },
  "fi_doc_items": [
    {
      "buzei": "001",
      "hkont": "0000100000",
      "wrbtr": 1000.00,
      "sgtxt": "Sample line text",
      "lifnr": null,
      "kunnr": null,
      "bschl": "40",
      "shkzg": "H",
      "dmbtr": 1000.00,
      "waers": "KRW",
      "isTarget": true
    },
    {
      "buzei": "002",
      "hkont": "0000200000",
      "wrbtr": -500.00,
      "sgtxt": "Other line",
      "lifnr": null,
      "kunnr": null,
      "isTarget": false
    }
  ],
  "evidence": {
    "documentOrOpenItem": {
      "type": "DOCUMENT",
      "docKey": "1000-1900000001-2024",
      "headerSummary": { "bukrs": "1000", "belnr": "1900000001", "gjahr": "2024" },
      "items": [ "... 동일 구조: buzei, hkont, wrbtr, sgtxt 등 ..." ],
      "lineCount": 2,
      "amount": 500.00,
      "currency": "KRW"
    },
    "amount": 500.00,
    "currency": "KRW"
  },
  "reasoning": { "score": 0.72, "reasonText": "..." },
  "action": { "availableActionTypes": [ "PAYMENT_BLOCK", "REQUEST_INFO", "DISMISS", "RELEASE_BLOCK" ], "actions": [ ], "lineageLinkParams": { } }
}
```

- **fi_doc_items[]**: 전표/문서 라인 배열. DOCUMENT 타입일 때만 채워지며, OPEN_ITEM만 있는 경우는 빈 배열 또는 null.
- 각 요소 필드: **buzei**, **hkont**, **wrbtr**, **sgtxt** (필수 FE 규격), 그 외 lifnr, kunnr, dmbtr, waers, isTarget 등 선택.
