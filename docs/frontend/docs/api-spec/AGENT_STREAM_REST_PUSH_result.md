# Agent Stream REST Push — BE 구현 결과 (Prompt C)

> job.txt 프론트 전달사항 반영  
> 작성: 2026-01-29

---

## 1. 구현 완료

### POST /api/synapse/agent/events

| 항목 | 구현 |
|------|------|
| 경로 | `POST /api/synapse/agent/events` |
| Content-Type | application/json |
| Request Body | `{ "events": [ {...}, ... ] }` |
| Response | 200 OK, `{ "status": "SUCCESS", "data": { "saved": N, "received": M } }` |
| 저장소 | agent_activity_log |
| Gateway | `/api/synapse/agent/**` → synapsex-service |

### agent_event 스키마 (요청)

| 필드 | 타입 | 필수 | 비고 |
|------|------|------|------|
| tenantId | string | ✅ | 숫자 문자열 권장 |
| timestamp | string | ✅ | ISO 8601 |
| stage | string | ✅ | SCAN\|DETECT\|EXECUTE\|SIMULATE\|ANALYZE\|MATCH |
| message | string | ✅ | 운영자 이해 가능 문장 |
| caseKey | string | | CS-2026-0001 등 |
| caseId | string | | 케이스 ID |
| severity | string | | INFO\|WARN\|ERROR |
| traceId | string | | 요청 추적 ID |
| actionId | string | | 액션 ID |
| payload | object | | 추가 상세 (metadata_json에 병합) |

### GET /api/synapse/dashboard/agent-stream

- **데이터 소스**: agent_activity_log 우선 → 없으면 audit_event_log fallback
- REST push로 저장된 이벤트가 agent-stream에 표시됨
- message: metadata_json.message 사용 (REST push 시)
- traceId: metadata_json.traceId 사용

---

## 2. Aura 연동 (참고)

- **Push URL**: `http://localhost:8081/api/synapse/agent/events` (Gateway 8080 → 8081 시)
- 실제: Gateway 8080 → `http://localhost:8080/api/synapse/agent/events` (클라이언트 기준)
- Backend 직접: `http://localhost:8085/synapse/agent/events`
- **Fire-and-forget**: Aura는 비동기 push, 실패 시 로그만 남김

---

## 3. 샘플 요청

```json
POST /api/synapse/agent/events
Content-Type: application/json

{
  "events": [
    {
      "tenantId": "1",
      "timestamp": "2026-02-01T12:00:00Z",
      "stage": "SCAN",
      "message": "케이스 목표 및 컨텍스트 분석을 시작합니다.",
      "caseKey": "CS-2026-0001",
      "caseId": "case-001",
      "severity": "INFO",
      "traceId": "trace-abc123"
    }
  ]
}
```

---

## 4. 관련 문서

- job.txt (프론트 전달 원본)
- AURA_SYNAPSE_HANDOFF.md
- AUDIT_EVENTS_SPEC.md
