# Action Single Source of Truth (Backend 확정 로직)

> 모든 Write 주도권은 백엔드가 가집니다. 사용자 승인/거절 시 DB 상태 변경과 이력 저장은 백엔드에서 원자적으로 완결합니다.

---

## 1. 원칙

- **Backend Single Source of Truth**: 워크벤치 [승인/거절] 클릭 시 `ActionCommandService`가 다음을 **한 트랜잭션**에서 처리합니다.
- Aura 또는 프론트엔드는 **DB에 조치 결과를 직접 쓰지 않습니다**. 동일 테이블에 대한 이중 쓰기를 방지합니다.

---

## 2. 원자적 처리 내용 (승인/거절 공통)

| 순서 | 대상 | 동작 |
|------|------|------|
| 1 | `dwp_aura.agent_case_action_history` | INSERT (actor_id, comment, action_type, action_at, metadata_json) |
| 2 | `dwp_aura.fi_doc_header` | 해당 전표가 있으면 `status_code` 업데이트 (승인 → `FINALIZED`, 거절 → `REJECTED`) |
| 3 | `dwp_aura.agent_case` | `status` → `RESOLVED` (승인/거절 모두 조치 완료로 확정) |

- **승인 시 추가**: `agent_action.status` → `APPROVED`, `recon_result` → `PASS` (해당 리소스 키 prefix)
- **거절 시 추가**: `agent_action.status` → `CANCELED`
- **fi_doc_header**: 코드값은 `FINALIZED`(승인 결과), `REJECTED`(거절 결과) 사용. (app_codes와 일치)

---

## 3. DB 커밋 직후 발행

- **Redis**: 트랜잭션 **AFTER_COMMIT** 후 `workbench:case:action` 채널에 최종 상태 발행 (Aura 규격 통일) (FE UI 갱신, `new_kpi_summary` 포함 가능).
- **Aura 웹훅 (선택)**: `aura.webhook.action-completed-url`이 설정된 경우, 동일 payload를 POST하여 Aura가 후속 분석/알림을 수행할 수 있음.

---

## 4. 관련 코드

- 조치 처리: `ActionCommandService.approveAction` / `rejectAction` (이벤트 발행만 수행, 실제 Redis/웹훅은 리스너에서)
- 이벤트: `ActionCompletedEvent` (커밋 직후 리스너 트리거)
- 리스너: `ActionCompletionListener` (`@TransactionalEventListener(AFTER_COMMIT)` → Redis 발행 + Aura 웹훅)
- 발행: `WorkbenchActionCompletionPublisher` (채널 기본값: `workbench:case:action`)
- 웹훅: `AuraActionCompletedWebhookNotifier` (URL 설정 시에만 POST)

---

## 5. 설정

| 설정 | 기본값 | 설명 |
|------|--------|------|
| `workbench.redis.action-channel` | `workbench:case:action` | Redis Pub/Sub 채널 (Aura 규격 통일) |
| `aura.webhook.action-completed-url` | (비설정) | 설정 시 조치 완료 payload POST |
