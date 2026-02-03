# Aura → Synapse 전달 사항

> Aura 팀에서 Synapse(엔드)로 전달한 요구사항 정리  
> 최종 업데이트: 2026-01-29

---

## 1. DB 스키마

### agent_activity_log 테이블 (신규)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| activity_id | BIGSERIAL | PK |
| tenant_id | BIGINT | 테넌트 |
| stage | TEXT | SCAN, DETECT, ANALYZE, SIMULATE, EXECUTE |
| event_type | TEXT | Aura 원본 event_type |
| resource_type | TEXT | AGENT_CASE, AGENT_ACTION 등 |
| resource_id | TEXT | 리소스 식별자 |
| occurred_at | TIMESTAMPTZ | 발생 시각 |
| actor_agent_id | TEXT | 에이전트 ID |
| actor_user_id | BIGINT | 사용자 ID |
| metadata_json | JSONB | 추가 메타데이터 |

**인덱스:**
- `(tenant_id, occurred_at DESC)` — 스트림 조회용
- `(tenant_id, resource_type, resource_id)` — 리소스별 조회용

---

## 2. 동기화 규칙 (Redis audit:events:ingest 수신 시)

1. **agent_activity_log** insert
   - event_type → stage 매핑 적용
   - occurred_at = created_at (또는 수신 시각)

2. **audit_event_log** insert
   - event_category = 'AGENT'
   - event_type = stage (매핑된 값)
   - 기존 필드 유지

---

## 3. event_type → stage 매핑

| Aura event_type | stage |
|-----------------|-------|
| SCAN_STARTED, SCAN_COMPLETED | SCAN |
| DETECTION_FOUND | DETECT |
| RAG_QUERIED, REASONING_COMPOSED, DECISION_MADE | ANALYZE |
| SIMULATION_RUN | SIMULATE |
| ACTION_PROPOSED, ACTION_APPROVED, ACTION_EXECUTED, ACTION_ROLLED_BACK | EXECUTE |

---

## 4. API 구현

| API | 경로 | 비고 |
|-----|------|------|
| Team Snapshot | GET /api/synapse/dashboard/team-snapshot | ✅ 구현됨 |
| Agent Stream | GET /api/synapse/dashboard/agent-stream | ✅ 구현됨 (agent-activity 별칭) |
| Aura 별칭 | GET /api/aura/dashboard/* | ✅ Gateway 프록시 → synapsex (동일 응답) |

---

## 5. Team Snapshot 로직

**입력:**
- auth_db.com_users (display_name 등)
- dwp_aura.agent_case

**출력:**
- user_id
- display_name (com_users 연동)
- open_cases
- sla_risk
- avg_lead_time_hours

**현재 상태:** agent_case + auth-server Feign 호출로 com_users display_name 연동 완료.

---

## 6. 구현 상태 (2026-01-29)

| 항목 | 상태 | 비고 |
|------|------|------|
| agent_activity_log | ✅ | V19 마이그레이션, Entity, Repository |
| Redis 수신 시 agent_activity_log | ✅ | AuditEventIngestService 이중 저장 |
| event_type → stage | ✅ | AuraEventStageMapper (Aura 규격) |
| audit_event_log event_type | ✅ | stage로 저장 (event_category='AGENT') |
| agent-stream | ✅ | agent_activity_log 우선, 없으면 audit_event_log fallback |
| Team Snapshot display_name | ✅ | AuthServerUserClient Feign → com_users.display_name |
| /api/aura/dashboard/* | ✅ | Gateway 프록시 → synapsex (동일 API) |
