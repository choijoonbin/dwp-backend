# Aura → 백엔드 전달 사항 (Backend Team)

> **대상**: 백엔드 개발팀  
> **목적**: Aura와의 연동 시 백엔드에서 반드시 확인·구현할 사항 요약  
> **일자**: 2026-02-11  
> **공유**: Aura 팀 전달 내용 반영, Backend 현황 체크리스트 포함  
> **추가 전달**: Aura 측 체크리스트(§4) 추가 수신 — 동일 5항목 기준, Backend 검증 완료 반영

---

## 1. 백엔드에서 해야 할 일 (필수)

| # | 항목 | 설명 |
|---|------|------|
| 1 | **조치 이력·전표 상태 DB** | Aura는 **case_action_history**, **fi_doc_header**에 더 이상 쓰지 않습니다. 승인/거절 처리 시 **백엔드가 자체 DB**에 조치 이력·전표 상태를 반드시 기록·갱신해 주세요. |
| 2 | **/action/record 응답 사용처** | `POST /api/aura/action/record` 호출 후 **data.history_id**, **data.fi_doc_updated**는 Aura가 반환하지 않습니다. 해당 필드를 참조하는 코드가 있다면 제거하거나, **자체 DB에 저장한 결과**를 사용하도록 변경해 주세요. 응답은 `data: { ok, logged }` 만 옵니다. |
| 3 | **Redis 구독 시 필드** | `workbench:case:action` 등 workbench:* 채널 구독 시, Aura 발행분에는 **history_id**, **fi_doc_updated**가 없습니다. 이 필드에 의존하지 않도록 제거 또는 optional 처리해 주세요. |

---

## 2. 백엔드 → Aura (요청 규격)

### 2.1 Phase2 분석 요청

- **Endpoint**: `POST /aura/cases/{caseId}/analysis-runs` (Aura 기준 경로; 게이트웨이 prefix는 백엔드 라우팅에 따름)
- **Body**: caseId, runId, mode, requestedBy, **evidence**(JsonNode), options 등
- **body_evidence (Phase2 시 명시)**  
  특정 전표·라인만 규정 준수 판단하고 싶을 때 요청 body에 아래 블록을 포함해 주세요.

  ```json
  "body_evidence": {
    "doc_id": "1900000001",
    "item_id": "001"
  }
  ```

  - **doc_id**: 전표 번호(BELNR) — 문자열로 전달 (Backend: `BodyEvidenceDto.docId` → `@JsonProperty("doc_id")`).
  - **item_id**: 전표 라인(BUZEI) — 문자열로 전달 (`BodyEvidenceDto.itemId` → `@JsonProperty("item_id")`).

- **복사용 요청 JSON 전체**: [LEVEL4_FINAL_API_SPEC.md](./LEVEL4_FINAL_API_SPEC.md) **Part A.2** 참고.

---

## 3. Aura → 백엔드 (Redis 수신 규격)

### 3.1 구독 채널 (workbench:*)

Aura가 발행하는 채널명은 아래와 같습니다. **문자열 그대로** 구독해 주세요.

| 용도 | 채널명 | category | 발행 시점 |
|------|--------|----------|-----------|
| 고위험 탐지 | `workbench:alert` | AI_DETECT | Phase2에서 severity=HIGH 케이스 생성 시 |
| RAG 학습 완료 | `workbench:rag:status` | RAG_STATUS | RAG 벡터화 완료 시 |
| 조치 결과 통보 | `workbench:case:action` | CASE_ACTION | 승인/거절/타임아웃(보류) 시 |

### 3.2 메시지 형식 및 NotificationDto 매핑

- **공통 필드**: `type`(항상 `"NOTIFICATION"`), `category`, `message`, `timestamp`(ISO 8601 UTC), 기타 category별 필드.
- **백엔드 수신 후**: JSON 수신 시 **category → type**, **message → content**, **timestamp → occurredAt** 으로 NotificationDto에 매핑 후 DB 저장 및 `/topic/notifications` 브로드캐스트하면 됩니다.

**Redis 발행 메시지 최종 샘플(JSON 예시)**: [AURA_REDIS_PUBLISH_SPEC.md](./AURA_REDIS_PUBLISH_SPEC.md) 참고.

---

## 4. 체크리스트 (백엔드 확인용) — Aura 추가 전달 반영

- [x] 승인/거절 시 조치 이력·전표 상태를 **자체 DB**에 기록·갱신하는가?  
  → **예.** `ActionCommandService`: `agent_case_action_history` INSERT, `fi_doc_header` FINALIZED, `recon_result` PASS.
- [x] `/api/aura/action/record` 응답에서 `history_id`, `fi_doc_updated` 사용 코드를 제거했는가? (또는 자체 DB 결과로 대체)  
  → **해당 없음.** 백엔드는 `POST /api/aura/action/record`를 **호출하지 않음**. 조치 확정은 SynapseX DB에서만 수행.
- [x] Redis workbench:* 구독 시 `history_id`, `fi_doc_updated` 의존을 제거했는가? (optional 처리)  
  → **예.** `NotificationRedisSubscriber`는 **category**, **message**, **timestamp**만 사용. history_id, fi_doc_updated 파싱/참조 없음.
- [x] Phase2 분석 요청 시 특정 문서·라인 지정이 필요하면 `body_evidence.doc_id`, `body_evidence.item_id`를 보내는가?  
  → **예.** `CaseAnalysisService.buildBodyEvidence()` → `AuraAnalyzeRequest.bodyEvidence` 전송.
- [x] workbench:* 수신 시 `category`/`message`/`timestamp`를 NotificationDto type/content/occurredAt에 매핑하는가?  
  → **예.** 페이로드에 있으면 우선 사용, 없으면 채널별 fallback.

---

## 5. 상세 규격 문서 (참고)

| 문서 | 내용 |
|------|------|
| [LEVEL4_FINAL_API_SPEC.md](./LEVEL4_FINAL_API_SPEC.md) | Backend 검증 요청/응답 JSON (Aura 수신 샘플, body_evidence 검증, FE용 wrbtr/actionAt) |
| [AURA_REDIS_PUBLISH_SPEC.md](./AURA_REDIS_PUBLISH_SPEC.md) | Redis 채널명·공통 스키마·AI_DETECT/RAG_STATUS/CASE_ACTION별 JSON 샘플 |
| [AURA_TXT_RESPONSE_BACKEND_REVIEW.md](./AURA_TXT_RESPONSE_BACKEND_REVIEW.md) | /action/record 미호출·Redis 구독 optional 정리 |
| [FRONTEND_SPEC_ALIGNMENT.md](./FRONTEND_SPEC_ALIGNMENT.md) | FE 정합성 요약 |

문의나 규격 이슈는 위 문서를 기준으로 협의해 주시면 됩니다.
