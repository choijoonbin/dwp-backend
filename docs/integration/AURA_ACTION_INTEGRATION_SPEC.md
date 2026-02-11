# Aura ↔ DWP Backend 조치 완료 연동 명세 (공유용)

> **목적**: 워크벤치 승인/거절 시 두 시스템이 겹치지 않고 연동되도록 계약을 정리합니다.  
> **공유 대상**: Aura 플랫폼 담당자

---

## 1. 역할 분리 (반드시 맞춰야 할 부분)

### 1.1 Write 주도권 — DWP Backend 단일 소유

| 항목 | 담당 | Aura 측 조치 |
|------|------|----------------|
| 조치 결과 DB 반영 | **DWP Backend만** | **동일 테이블에 직접 INSERT/UPDATE 하지 않음** |
| `agent_case_action_history` | Backend INSERT | Aura는 이 테이블에 조치 이력 쓰지 않음 |
| `fi_doc_header.status_code` | Backend UPDATE (FINALIZED/REJECTED) | Aura는 전표 상태 직접 갱신하지 않음 |
| `agent_case.status` | Backend UPDATE (RESOLVED) | Aura는 케이스 상태 직접 RESOLVED로 쓰지 않음 |

- **이유**: 이중 쓰기 방지 및 데이터 정합성. 사용자가 [승인/거절] 클릭 시 **한 번만** Backend가 DB를 갱신합니다.
- Aura는 **조치 완료 알림을 받은 뒤** 후속 분석·알림·UI 갱신만 수행하고, **동일 테이블에 조치 결과를 다시 쓰지 않습니다.**

### 1.2 조치 완료 알림 — Backend가 발행, Aura가 구독

| 항목 | 내용 |
|------|------|
| **발행 주체** | DWP Backend (Synapse) 1곳만 |
| **발행 시점** | DB 커밋 직후 (트랜잭션 성공 후) |
| **채널** | Redis Pub/Sub `workbench:case:action` (기본값, Aura 규격 통일) |
| **Aura 측** | 위 채널 구독 → 수신 시 후속 처리 (알림, 분석 트리거, 캐시 무효화 등) |

- Backend는 **같은 이벤트를 Redis와(선택) 웹훅으로만** 전달합니다. Aura가 별도로 “조치 완료”를 DB에 기록하는 API를 호출하면 **이중 기록**이 되므로 사용하지 않습니다.

---

## 2. Redis 계약 (맞춰야 할 부분)

### 2.1 채널

- **채널명**: `workbench:case:action`
- **변경 가능**: Backend 설정 `workbench.redis.action-channel`으로 변경 시, 운영에서 채널명을 Aura와 사전 합의 필요.

### 2.2 메시지 형식

- **인코딩**: UTF-8
- **형식**: 한 줄 JSON 문자열 (Redis `PUBLISH` body와 동일)

### 2.3 필드 정의

| 필드 | 타입 | 설명 |
|------|------|------|
| `type` | string | 고정값 `"case_action_completed"` |
| `case_id` | string | 케이스 식별자 (전표 케이스는 `belnr`, 없으면 numeric `caseId` 문자열) |
| `request_id` | string | HITL 요청 ID (예: `"action-123"`) |
| `executor_id` | string | 조치자 ID (예: `"USER:1"`, 없으면 `"SYSTEM"`) |
| `action_type` | string | `"APPROVE"` 또는 `"REJECT"` |
| `approved` | boolean | `true` = 승인, `false` = 거절 |
| `status_code` | string | `"APPROVED"` 또는 `"REJECTED"` (업무 결과 구분용) |
| `history_id` | number | `agent_case_action_history.id` (0이면 이력 미연결) |
| `fi_doc_updated` | number | 전표 갱신 건수 (0 또는 1) |
| `at` | string | ISO-8601 시각 (예: `"2026-02-11T12:00:00.000Z"`) |
| `tenant_id` | number | 테넌트 ID |
| `action_id` | number | `agent_action.action_id` |
| `new_kpi_summary` | object | (선택) 대시보드 KPI 요약. 있으면 FE가 별도 재조회 없이 KPI 갱신 가능 |

### 2.4 메시지 예시

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
  "action_id": 123,
  "new_kpi_summary": {
    "asOf": "2026-02-11T12:00:00Z",
    "openCases": 10,
    "resolvedToday": 2
  }
}
```

- `new_kpi_summary`는 Backend가 포함할 수 있는 선택 필드이며, 구조는 Backend 대시보드 API와 동일한 스키마를 따릅니다.

---

## 3. Aura 측에서 맞춰야 할 사항 요약

1. **DB**
   - `agent_case_action_history`, `fi_doc_header.status_code`, `agent_case.status`(RESOLVED)에 **조치 결과를 직접 쓰지 않음**.
   - 조치 완료 후 상태가 필요하면 Backend API로 조회 (예: `GET /api/synapse/workbench/cases/{id}/history`, 케이스/전표 상세 API).

2. **Redis**
   - 채널 `workbench:case:action` 구독.
   - 수신 메시지를 UTF-8 JSON으로 파싱 후 `type == "case_action_completed"` 인 것만 처리.
   - `case_id`, `tenant_id`, `approved`, `at` 등으로 후속 로직(알림, 분석, 캐시 무효화) 수행.

3. **중복 호출 금지**
   - Backend가 이미 조치 완료를 DB에 반영하고 Redis로 알리므로, Aura가 “조치 완료 기록”용으로 Backend의 동일 조치를 다시 쓰는 API를 호출하지 않음.

4. **선택: 웹훅**
   - Backend에 `aura.webhook.action-completed-url`(Aura 측 URL)을 설정하면, Redis와 **동일 payload**로 POST합니다.
   - Aura가 HTTP 엔드포인트를 제공할 경우, Redis 구독 대신 또는 보조로 웹훅 수신 가능 (계약은 Redis와 동일 payload).

---

## 4. Backend 쪽 코드/설정 참고

- 채널 기본값: `workbench.redis.action-channel` = `workbench:case:action`
- 웹훅(선택): `aura.webhook.action-completed-url` 또는 env `AURA_ACTION_COMPLETED_WEBHOOK_URL`
- 상세 설계: `ACTION_SINGLE_SOURCE_OF_TRUTH.md`, `ACTION_INTEGRITY_WORKBENCH_REFETCH.md`

---

## 5. 체크리스트 (Aura 연동 시 확인)

- [ ] Aura가 `agent_case_action_history` / `fi_doc_header` / `agent_case`에 조치 결과를 **쓰지 않음**
- [ ] Redis 채널 `workbench:case:action` 구독 (또는 운영 합의 채널명)
- [ ] 수신 JSON 파싱 시 `type`, `case_id`, `tenant_id`, `approved`, `at` 등 필드 사용
- [ ] 조치 완료를 “다시 기록”하는 Backend API 호출 없음
- [ ] (선택) 웹훅 URL 제공 시 Backend 설정에 반영 및 payload 계약 동일함 확인
