# Aura → Synapse Audit 이벤트 — Synapse 확인 완료

> Aura 팀 전달: C-1/C-2/C-3 보강 이벤트 수신·저장 확인  
> 작성: 2026-01-29

---

## 1. 전달 대상

| 문서 | 대상 | 용도 |
|------|------|------|
| `docs/frontend/docs/api-spec/AUDIT_AGENT_DASHBOARD_CONFIRMATION_result.md` | **Frontend** | Synapse 확인 응답 (job.txt 문의 답변) |
| `docs/integration/AURA_AUDIT_SYNAPSE_CONFIRMATION.md` (본 문서) | **Aura** | Synapse 수신·저장 확인, 발행 형식 참고 |

---

## 2. Synapse 확인 완료 사항 (Aura 참고)

Aura에서 `audit:events:ingest` 채널로 발행하는 이벤트를 Synapse가 정상 수신·저장합니다.

| Aura 발행 항목 | Synapse 처리 |
|----------------|-------------|
| event_category | CASE, ACTION, AGENT, INTEGRATION 모두 저장 |
| event_type | SIMULATION_RUN, SCAN_STARTED 등 그대로 저장 (prefix `AGENT/` 제거 시) |
| evidence_json | traceId, gatewayRequestId, caseId, caseKey, actionId 포함 저장 |
| tags | driverType, severity 저장 (Top Risk Driver 집계용) |
| tenant_id | Long 또는 숫자 문자열(`"1"`) 지원. 비숫자(`"tenant1"`) → ingest 스킵 |

---

## 3. Aura 발행 시 참고

- **채널**: `audit:events:ingest` (기본)
- **tenant_id**: 숫자(Long) 또는 숫자 문자열 권장. 비숫자 시 ingest 스킵
- **evidence_json.message**: Agent Stream UI에 표시됨
- **상세 스키마**: `docs/guides/AUDIT_EVENTS_SPEC.md`

---

## 4. Gateway/Backend 구현 (완료)

**tenant_id 형식 제약**

| Synapse 동작 | 비고 |
|-------------|------|
| 숫자(Long) 또는 숫자 문자열(예: `"1"`) | ✅ ingest |
| `"tenant1"`, `"default"` 등 비숫자 문자열 | ❌ ingest 스킵 (tenantId required) |

**Gateway 구현 (TenantIdNormalizationFilter)**

- `/api/aura/**` 라우팅 시 X-Tenant-ID 검사
- 이미 숫자(또는 숫자 문자열) → 그대로 전달
- 비숫자 → JWT `tenant_id` claim에서 숫자 추출 후 교체
- JWT에도 숫자 없으면 → 400 (에러 메시지로 원인 안내)

→ **Aura로 전달되는 X-Tenant-ID는 숫자로 정규화됨**

---

## 5. 관련 문서

- `AURA_SYNAPSE_HANDOFF.md` — Aura-Synapse 연동 규칙
- `AURA_PLATFORM_UPDATE.md` — Redis Pub/Sub 상세
- `AUDIT_EVENTS_SPEC.md` — Audit 이벤트 명세
