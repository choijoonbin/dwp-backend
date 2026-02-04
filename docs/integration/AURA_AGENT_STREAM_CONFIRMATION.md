# Agent Stream REST Push — Synapse 확인 완료 (aura.txt)

> Aura 팀 전달: Prompt C Agent Stream 명세 구현 확인  
> 작성: 2026-02-04

---

## 1. 전달 대상

| 문서 | 대상 | 용도 |
|------|------|------|
| `AURA_AGENT_STREAM_CONFIRMATION.md` (본 문서) | **Aura** | aura.txt 명세 기반 Synapse 구현 확인 |
| `AGENT_STREAM_REST_PUSH_result.md` | Frontend | 상세 API 스펙 |

---

## 2. Synapse 구현 완료 (aura.txt §8 요구사항)

| aura.txt 요구사항 | Synapse 구현 |
|------------------|-------------|
| POST /api/synapse/agent/events | ✅ 구현됨 |
| Request body: `{ "events": [ {...}, ... ] }` | ✅ 지원 |
| Response: 2xx (성공 시) | ✅ 200 OK |
| agent_activity_log 적재 | ✅ 저장 |
| GET /dashboard/agent-stream?range=6h | ✅ `GET /api/synapse/dashboard/agent-stream` (또는 agent-activity) |

---

## 3. agent_event 스키마 호환 (aura.txt §1)

| aura.txt 필드 | Synapse 처리 |
|---------------|-------------|
| tenantId | Long 파싱 (숫자 문자열 지원, 비숫자 시 스킵) |
| timestamp | occurred_at (ISO 8601 파싱) |
| stage | SCAN/DETECT/EXECUTE/SIMULATE/ANALYZE/MATCH 그대로 저장 |
| message | metadata_json.message |
| caseKey, caseId | resourceType=CASE, resourceId (caseKey에서 숫자 추출 또는 caseId) |
| severity | metadata_json.severity |
| traceId | metadata_json.traceId |
| actionId | resourceType=ACTION, resourceId |
| payload | metadata_json에 병합 |

---

## 4. Aura Push URL 설정 (aura.txt §6)

```bash
# .env
AGENT_STREAM_EVENTS_ENABLED=true
AGENT_STREAM_PUSH_URL=http://localhost:8080/api/synapse/agent/events
```

- **Gateway 경유**: `http://{gateway-host}:8080/api/synapse/agent/events`
- **Backend 직접** (테스트용): `http://localhost:8085/synapse/agent/events`
- aura.txt 예시 `8081` → Gateway 기본 포트는 `8080` (실제 환경에 맞게 설정)

---

## 5. Dashboard API 연동 (aura.txt §3)

- `GET /api/synapse/dashboard/agent-stream?range=6h` — REST push 이벤트 조회
- `GET /api/synapse/dashboard/agent-activity` — 동일 API (별칭)
- agent_activity_log 우선 조회, 없으면 audit_event_log fallback

---

## 6. 관련 문서

- `aura.txt` — Aura Agent Stream 명세 (원본)
- `AURA_SYNAPSE_HANDOFF.md` — Aura-Synapse 연동 규칙
- `AGENT_STREAM_REST_PUSH_result.md` — 상세 API 스펙
