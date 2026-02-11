# Aura RAG 참조 로그 형식 — 백엔드 연동 정리

> Aura 쪽 명세: **aura-platform/docs/backend/RAG_REFERENCE_LOG_FORMAT_FOR_BACKEND.md** 참고.

백엔드(Synapse)는 Agent Stream 수신 시 위 명세의 payload 구조를 그대로 저장·노출합니다.

---

## 1. 전달 경로

| 경로 | 용도 | RAG 관련 필드 |
|------|------|----------------|
| **POST /api/synapse/agent/events** | Agent Stream → `agent_activity_log` 적재 | `payload.evidence.ragContributions`, `payload.evidence.policy_reference` |
| **콜백 finalResult** | 분석 완료 시 | `finalResult.ragRefs` (기존 유지), 선택 확장 시 `ragContributions` |

---

## 2. 백엔드 저장 (현재 구현)

- **AgentEventPushService**: Aura가 보낸 각 이벤트의 **`payload`** 를 그대로 `agent_activity_log.metadata_json` 에 병합 저장.
- 따라서 `payload.evidence.ragContributions`, `payload.evidence.policy_reference` 및 SAP 원천 식별자(`bukrs`, `belnr`, `gjahr`, `resource_key` 등)는 **별도 파싱 없이** metadata_json 내에 포함됨.

### 2.1 Aura payload.evidence 신규 필드 (명세 기준)

- **ragContributions** (array, 선택)  
  - `refId`, `sourceType`, `sourceKey`, `title`, `location`, `excerpt`
  - 타임라인/필터용으로 metadata에서 파싱 가능. FE에서 "규정 제3조 2항(비용 한도) 참조" 등 조합 가능.
- **policy_reference** (object, 선택)  
  - `configSource`, `profileName`  
  - 추론 시 참조한 정책/임계치 출처.

---

## 3. 노출 경로

- **Workbench 타임라인**: `WorkbenchTimelineItemDto.metadata`(WorkbenchTimelineMetadataDto)에 `evidence` 가 Object로 매핑됨.  
  - `metadata_json` 원본도 `metadataJson` 필드로 전달되므로, FE에서 `evidence.ragContributions`, `evidence.policy_reference` 를 그대로 파싱 가능.
- **콜백 finalResult**: 기존 `ragRefs` 스키마 유지. 추후 Aura가 `finalResult.ragContributions` 를 보내면 동일 구조로 저장·노출하면 됨.

---

## 4. 요약

| 구분 | 전달 경로 | 백엔드 처리 |
|------|-----------|-------------|
| 실시간 로그 | Agent Stream (agent_events) | `payload` 전체 → `agent_activity_log.metadata_json` (ragContributions, policy_reference 포함) |
| 타임라인 노출 | GET workbench/cases/{id} | `metadata.evidence` + `metadataJson` 그대로 전달 → FE에서 ragContributions/location/title 활용 |
| 분석 완료 | 콜백 finalResult | `ragRefs` 기존 유지; 선택 시 `ragContributions` 확장 |

명세 변경 시 위 문서와 **aura-platform/docs/backend/RAG_REFERENCE_LOG_FORMAT_FOR_BACKEND.md** 를 함께 참고하면 됩니다.
