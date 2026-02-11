# Action Integrity & 워크벤치 Refetch (Aura 연동)

> **참조**: Aura 전달 문서 `aura.txt` (다른 시스템 전달: Action Integrity 및 워크벤치 Refetch)  
> **Backend (Synapse)** 구현 요약 및 Redis/API 정렬 사항.

---

## 1. Synapse 측 구현 요약

- **승인/거절 시** (`ActionCommandService.approveAction` / `rejectAction`):
  - DB: `agent_case_action_history`에 실행자 ID, 조치 유형(APPROVE/REJECT), 코멘트 기록
  - DB: `fi_doc_header.status_code` 조치 결과에 맞춰 동기화 (승인 → FINALIZED, 거절 → REJECTED)
  - Redis: 트랜잭션 **커밋 직후** **`workbench:case:action`** 채널로 조치 완료 메시지 발행 (Aura 규격과 채널 통일) (`ActionCompletionListener`에서 발행, 동일 payload에 `new_kpi_summary` 포함 가능)

---

## 2. Redis 채널 및 메시지 형식 (Aura 사양 정렬)

| 항목 | 내용 |
|------|------|
| **채널명** | `workbench:case:action` (기본값, Aura 규격 통일, `workbench.redis.action-channel`로 변경 가능) |
| **발행 시점** | DB 커밋 직후 (Synapse 백엔드가 단일 발행 주체, Backend SoT) |
| **메시지** | UTF-8 JSON 문자열 |

**메시지 예시 (Synapse 발행)**:

```json
{
  "type": "case_action_completed",
  "case_id": "DEMO00001",
  "request_id": "action-123",
  "executor_id": "USER:1",
  "action_type": "APPROVE",
  "approved": true,
  "status_code": "APPROVED",
  "history_id": 1,
  "fi_doc_updated": 1,
  "at": "2026-02-11T12:00:00.000Z",
  "tenant_id": 1,
  "action_id": 123
}
```

- `case_id`: 케이스 식별자 문자열 (전표 케이스는 `belnr`, 없으면 numeric `caseId` 문자열)
- `history_id`: `agent_case_action_history.id`
- `fi_doc_updated`: 전표 갱신 건수 (0 또는 1)

---

## 3. Backend 구독 시 Refetch 연동

동일 Redis에서 `workbench:case:action`(또는 설정 채널)을 구독한 뒤, 수신 시 해당 `case_id`에 대해 케이스 상세·전표 API를 재조회(Refetch)하거나 WebSocket/SSE로 클라이언트에 알리면 됩니다.

- 설정: `workbench.redis.action-channel` (기본: `workbench:case:action`)
- Aura 쪽에서 동일 채널을 구독하면 Synapse가 발행한 조치 완료 메시지로 후속 처리 가능. 선택적으로 `aura.webhook.action-completed-url`로 웹훅 POST도 가능.

---

## 4. (선택) Aura API 호출

사용자 승인/거절 시 Synapse만 사용하는 경우 **별도 Aura API 호출은 불필요**합니다.  
Aura 스트림에서 자동 기록을 쓰는 경우, Synapse에서 `POST /api/aura/action/record`를 추가로 호출하면 이중 기록이 될 수 있으므로 **한쪽만 사용**하는 것을 권장합니다.

---

## 5. 관련 코드

- 조치 처리: `ActionCommandService.approveAction` / `rejectAction` (DB 갱신 후 `ActionCompletedEvent` 발행)
- 커밋 후 발행: `ActionCompletionListener` (`@TransactionalEventListener(AFTER_COMMIT)`) → Redis + 선택적 Aura 웹훅
- 발행: `WorkbenchActionCompletionPublisher` (채널 기본 `workbench:case:action`, 페이로드에 `new_kpi_summary` 포함)
- 이력 테이블: `dwp_aura.agent_case_action_history` (Flyway V45)
- Backend SoT 상세: `docs/integration/ACTION_SINGLE_SOURCE_OF_TRUTH.md`
