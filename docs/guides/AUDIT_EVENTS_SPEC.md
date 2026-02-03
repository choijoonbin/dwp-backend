# Audit Events 명세

> Synapse 감사 이벤트 SoT: `dwp_aura.audit_event_log`  
> Aura ↔ Synapse 연동: Redis Pub/Sub

---

## 1. 개요

- **SoT**: `dwp_aura.audit_event_log` (PostgreSQL)
- **수집 경로**: (1) Synapse 내부 API 호출 시 AuditWriter, (2) Aura → Redis Pub/Sub → Synapse
- **조회 API**: `GET /api/synapse/audit/events`

---

## 2. audit_event_log 스키마

| 컬럼 | 타입 | 필수 | 설명 |
|------|------|------|------|
| audit_id | BIGSERIAL | PK | 자동 생성 |
| tenant_id | BIGINT | O | 테넌트 ID |
| event_category | TEXT | O | ADMIN, CASE, ACTION, AGENT, INTEGRATION, DASHBOARD 등 |
| event_type | TEXT | O | 이벤트 유형 |
| resource_type | TEXT | - | CASE, AGENT_ACTION 등 |
| resource_id | TEXT | - | case_id, action_id 등 |
| created_at | TIMESTAMPTZ | O | 이벤트 발생 시각 |
| actor_type | TEXT | - | HUMAN, AGENT, SYSTEM |
| actor_user_id | BIGINT | - | 행위자 사용자 ID |
| actor_agent_id | TEXT | - | 에이전트 ID |
| actor_display_name | TEXT | - | 표시명 |
| channel | TEXT | - | WEB_UI, API, AGENT, INTEGRATION 등 |
| outcome | TEXT | - | SUCCESS, FAILED, DENIED, NOOP |
| severity | TEXT | O | INFO, WARN, ERROR, CRITICAL (기본 INFO) |
| before_json, after_json, diff_json | JSONB | - | 변경 전/후/차이 |
| evidence_json | JSONB | - | 증거/메타데이터 |
| tags | JSONB | - | 태그 |
| trace_id, span_id | TEXT | - | 분산 추적 |
| gateway_request_id | TEXT | - | Gateway 요청 ID |

---

## 3. event_category / event_type 표준

| event_category | event_type 예시 |
|----------------|-----------------|
| AGENT | SCAN_STARTED, SCAN_COMPLETED, DETECTION_FOUND, RAG_QUERIED, SIMULATION_RUN, DECISION_MADE |
| INTEGRATION | INGEST_RECEIVED, INGEST_FAILED, SAP_WRITE_SUCCESS, SAP_WRITE_FAILED |
| ACTION | ACTION_PROPOSED, ACTION_APPROVED, ACTION_EXECUTED, ACTION_ROLLED_BACK |
| CASE | STATUS_CHANGE, CASE_ASSIGN, CASE_VIEW_LIST |
| DASHBOARD | DASHBOARD_VIEWED, DASHBOARD_DRILLDOWN_CLICKED |

---

## 4. Synapse 내부 기록 (AuditWriter)

Synapse API 호출 시 `AuditWriter`를 통해 직접 `audit_event_log`에 insert.

---

## 5. 조회 API

**GET** `/api/synapse/audit/events`

| Param | 설명 |
|-------|------|
| from, to | ISO 8601 datetime |
| category | event_category |
| type | event_type |
| outcome, severity | 필터 |
| actorUserId | actor_user_id |
| resourceType, resourceId | 리소스 필터 |
| q | 검색 (선택) |

---

## 6. Redis Pub/Sub 구독 (Aura → Synapse)

### 6.1 개요

Aura-Platform에서 에이전트 실행 이벤트를 Redis Pub/Sub으로 발행하고, Synapse가 구독하여 `audit_event_log`에 저장합니다.

### 6.2 채널

| 항목 | 값 |
|------|-----|
| **채널명** | `audit:events:ingest` (기본) |
| **설정** | Synapse에서 `AUDIT_REDIS_CHANNEL` 환경변수로 변경 가능 |

### 6.3 메시지 형식

- **인코딩**: UTF-8 bytes
- **형식**: JSON 문자열 (AuditEvent)

**필수 필드**:
- `tenant_id` (또는 tenantId): Long

**권장 필드** (snake_case 또는 camelCase):
- `event_category` / eventCategory
- `event_type` / eventType
- `resource_type` / resourceType
- `resource_id` / resourceId
- `created_at` / createdAt (ISO 8601)
- `actor_type` / actorType
- `actor_user_id` / actorUserId
- `channel`, `outcome`, `severity`
- `evidence_json` / evidenceJson (스트림 표시용 message 포함)
- `trace_id` / traceId

**JSON 예시** (snake_case):
```json
{
  "tenant_id": 1,
  "event_category": "AGENT",
  "event_type": "DETECTION_FOUND",
  "resource_type": "CASE",
  "resource_id": "123",
  "created_at": "2026-02-03T01:10:00Z",
  "actor_type": "AGENT",
  "channel": "AGENT",
  "outcome": "SUCCESS",
  "severity": "INFO",
  "evidence_json": {
    "message": "Critical anomaly detected: Amount variance 3x"
  },
  "trace_id": "abc-123"
}
```

### 6.4 Synapse 처리 흐름

1. Redis 채널 `audit:events:ingest` 구독
2. 메시지 수신 → UTF-8 디코딩
3. JSON 파싱 → `AuditEventIngestDto`
4. `AuditEventIngestService.ingest()` → `audit_event_log` insert

### 6.5 Redis 인스턴스

- **Aura와 Synapse가 동일 Redis 사용**
- HITL용 `hitl:channel:*`과 같은 Redis 인스턴스
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` 환경변수로 설정

---

## 7. Agent Execution Stream 연동

`GET /api/synapse/dashboard/agent-activity`는 `audit_event_log`에서 event_category IN (AGENT, ACTION, INTEGRATION) 이벤트를 조회합니다.  
Aura가 6장 규격으로 이벤트를 발행하면 통합관제센터 스트림에 표시됩니다.

---

## 8. 환경 변수 및 설정

### 8.1 Synapse (수신측)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| REDIS_HOST | localhost | Redis 호스트 |
| REDIS_PORT | 6379 | Redis 포트 |
| REDIS_PASSWORD | (빈) | Redis 비밀번호 |
| AUDIT_REDIS_CHANNEL | audit:events:ingest | 구독 채널명 |
| AUDIT_REDIS_ENABLED | true | 구독 활성화 여부 |

### 8.2 Aura (발행측)

- **채널**: `audit:events:ingest` (또는 Synapse와 협의한 값)
- **인코딩**: UTF-8 bytes로 발행
- **Redis**: Synapse와 동일 인스턴스 사용

### 8.3 application.yml (Synapse)

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

audit:
  redis:
    enabled: ${AUDIT_REDIS_ENABLED:true}
    channel: ${AUDIT_REDIS_CHANNEL:audit:events:ingest}
```

---

## 9. 관련 문서

- [AURA_PLATFORM_UPDATE.md](../integration/AURA_PLATFORM_UPDATE.md) — Aura 전달 사항
- [DASHBOARD_MOCK_REPLACEMENT_result.md](../frontend/docs/api-spec/DASHBOARD_MOCK_REPLACEMENT_result.md) — Dashboard API
