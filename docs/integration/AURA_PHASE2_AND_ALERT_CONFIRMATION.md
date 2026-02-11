# Phase2 body_evidence · workbench:alert 최종 확인 (백엔드 → Aura 응답)

> **요청**: Aura 팀 — doc_id/item_id 기반 정밀 추론 및 분석 완료 시 workbench:alert 수신·프론트 중계 확인  
> **일자**: 2026-02-11

---

## 1. Phase2 분석 요청 — body_evidence (doc_id, item_id)

**요청 사항**: Phase2 분석 요청 시 `body_evidence` 안에 `doc_id`, `item_id`를 정확히 포함하여 전송하는지 최종 확인.

**백엔드 확인 결과**: ✅ **구현 완료·전송 중**

| 항목 | 구현 위치 | 내용 |
|------|-----------|------|
| 요청 DTO | `AuraAnalyzeRequest` | `bodyEvidence` 필드 (JSON 직렬화 키: **`body_evidence`**) |
| 내부 구조 | `BodyEvidenceDto` | **`doc_id`** (전표 번호 BELNR), **`item_id`** (라인 BUZEI) — `@JsonProperty("doc_id")`, `@JsonProperty("item_id")` |
| 세팅 시점 | `CaseAnalysisService.buildBodyEvidence(agentCase)` | `agentCase.getBelnr()` → doc_id, `agentCase.getBuzei()` → item_id |
| 전송 시점 | Phase2 트리거 시 | `AuraAnalyzeRequest.builder().bodyEvidence(bodyEvidence).build()` 후 Aura로 POST |

**전송 예시 (Aura가 수신하는 body)**  
```json
{
  "caseId": 12345,
  "runId": "...",
  "mode": "phase2",
  "evidence": { ... },
  "body_evidence": {
    "doc_id": "1900000001",
    "item_id": "001"
  }
}
```

→ **doc_id, item_id 기반 정밀 추론을 위해 요청 body에 포함되어 전송되고 있습니다.**

---

## 2. 분석 완료 시 workbench:alert 수신 · 프론트 중계

**요청 사항**: 분석 완료 시 Aura가 `workbench:alert` 채널로 메시지를 보내면, 백엔드가 수신하여 프론트로 중계해 주세요.

**백엔드 확인 결과**: ✅ **구독·수신·중계 구현 완료**

| 항목 | 구현 |
|------|------|
| 구독 | `NotificationRedisConfig`: 패턴 **`workbench:*`** (PSUBSCRIBE) → **workbench:alert** 포함 |
| 수신 처리 | `NotificationRedisSubscriber.onMessage()`: 채널별 구분 없이 **category** → type, **message** → content, **timestamp** → occurredAt 매핑 |
| workbench:alert | Aura 발행 시 category=**AI_DETECT**, message, timestamp 포함 → 그대로 NotificationDto(type, content, occurredAt)로 매핑 |
| 프론트 중계 | `NotificationBroadcastService.saveAndBroadcast()` → DB 저장 후 **`/topic/notifications`** STOMP 브로드캐스트 |

**플로우**  
1. Aura가 분석 완료(고위험 등) 시 **workbench:alert** 로 JSON 발행 (category: AI_DETECT, message, timestamp 등)  
2. 백엔드가 해당 메시지 수신 → NotificationDto 변환 → DB 저장  
3. **WebSocket `/topic/notifications`** 로 브로드캐스트 → 프론트 구독 시 실시간 수신

→ **workbench:alert 메시지를 수신하여 프론트로 중계하고 있습니다.**

---

## 3. 요약 (Aura 팀 전달용)

| 확인 항목 | 상태 |
|-----------|------|
| Phase2 요청 시 **body_evidence.doc_id, body_evidence.item_id** 포함 전송 | ✅ 구현·전송 중 |
| **workbench:alert** 채널 수신 후 프론트 중계 | ✅ workbench:* 구독, NotificationDto 매핑, `/topic/notifications` 브로드캐스트 |

**백엔드 담당자 확인**: 위 두 가지 모두 반영되어 있으며, 추가 수정 없이 현재 구현으로 요청 사항을 충족합니다.
