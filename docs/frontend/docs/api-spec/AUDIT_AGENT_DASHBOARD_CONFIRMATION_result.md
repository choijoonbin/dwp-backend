# 감사로그/에이전트 이벤트 대시보드 — Synapse(백엔드) 확인 응답

> job.txt 프론트 확인 요청사항에 대한 Synapse 백엔드 응답  
> 작성: 2026-01-29

---

## 전달 대상

| 대상 | 문서 | 비고 |
|------|------|------|
| **Frontend** | 본 문서 | Synapse 확인 응답 (job.txt 문의 답변) |
| **Aura** | `docs/integration/AURA_AUDIT_SYNAPSE_CONFIRMATION.md` | Synapse 수신·저장 확인, 발행 형식 참고 |

---

## 1. Redis Pub/Sub 구독

| 항목 | 상태 | 비고 |
|------|------|------|
| 채널 | ✅ | `audit:events:ingest` (기본) |
| 환경변수 | ✅ | `AUDIT_REDIS_CHANNEL`로 변경 가능 |
| 수신 → 저장 | ✅ | `AuditEventRedisSubscriber` → `AuditEventIngestService` → `audit_event_log` |

**설정**: `application.yml` — `audit.redis.channel`, `audit.redis.enabled`

---

## 2. audit_event_log 스키마 호환

| Aura 발행 필드 | Synapse 처리 | 비고 |
|----------------|-------------|------|
| event_category | ✅ | AGENT, ACTION, INTEGRATION, **CASE** 모두 저장 (기존 AGENT 고정 버그 수정) |
| event_type | ✅ | prefix 제거 (AGENT/SCAN_STARTED → SCAN_STARTED), SIMULATION_RUN 그대로 저장 |
| evidence_json | ✅ | traceId, gatewayRequestId, caseId, caseKey, actionId 포함 저장 |
| tags | ✅ | driverType, severity 그대로 저장 |
| tenant_id | ✅ | Long 또는 숫자 문자열 지원. "tenant1" 등 비숫자 → null (ingest 스킵) |

**수정 사항** (이번 반영):
- `event_category`: DTO 값 그대로 저장 (기존 AGENT 하드코딩 제거)
- `event_type`: prefix 제거 후 저장
- `traceId`/`gatewayRequestId`: root 없으면 evidence_json에서 추출
- `tenant_id`: 문자열 숫자(예: "1") 파싱 지원

---

## 3. agent_activity_log / 대시보드 API

| 항목 | 상태 | 비고 |
|------|------|------|
| agent_activity_log | ✅ | V19 migration, Redis 수신 시 event_type→stage 매핑 후 저장 |
| team-snapshot | ✅ | `GET /api/synapse/dashboard/team-snapshot` (기본 range=7d) |
| agent-stream | ✅ | `GET /api/synapse/dashboard/agent-activity` 또는 `/agent-stream` |
| drill-down | ✅ | traceId, gatewayRequestId, caseId, caseKey로 links.auditPath 생성 |

**agent-stream 필터**: event_category IN (AGENT, ACTION, INTEGRATION, **CASE**) — CASE 추가 반영

**message 표시**: `evidence_json.message` 우선, 없으면 `after_json.message`, 없으면 event_type 기반

---

## 4. Synapse 팀 문의 — 응답

```
1. Redis 구독 및 audit_event_log 저장이 정상 동작하는지
   → ✅ 정상. audit:events:ingest 구독 → ingest → audit_event_log insert

2. event_category CASE, event_type SIMULATION_RUN(ACTION 카테고리) 수신 처리
   → ✅ 처리. event_category CASE/ACTION 등 그대로 저장. SIMULATION_RUN event_type 저장.

3. evidence_json 내 traceId, gatewayRequestId, caseId, caseKey, actionId 저장
   → ✅ 저장. evidence_json 전체 저장. traceId/gatewayRequestId는 root 없으면 evidence에서 추출하여 컬럼에도 저장.

4. tags.driverType, tags.severity 저장 (Top Risk Driver 집계용)
   → ✅ 저장. tags JSONB 그대로 저장.

5. tenant_id 문자열→BIGINT 변환 정책 (필요 시)
   → ✅ 숫자 문자열("1") 파싱 지원. "tenant1" 등 비숫자 → null, ingest 스킵(tenantId required).
   → tenant_id 매핑 테이블 필요 시 별도 협의.
```

---

## 5. 참고 문서

- `docs/guides/AUDIT_EVENTS_SPEC.md` — Audit 이벤트 명세
- `docs/integration/AURA_SYNAPSE_HANDOFF.md` — Aura-Synapse 연동
- `docs/integration/AURA_PLATFORM_UPDATE.md` — Redis Pub/Sub 상세
