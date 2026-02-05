# Phase 0 — 표준 프로세스 스펙

> 작성일: 2026-01-29  
> 목표: Ingest Run → Detect Run → Case Upsert → Agentic Trigger → HITL → Audit 흐름 정형화

---

## 1. 프로세스 흐름 개요

```
[원천데이터] → Ingest Run → [Canonical Tables]
                                ↓
[Detect Run] ← window_from/to ← [fi_doc_*, fi_open_item, sap_raw_events]
     ↓
Case Upsert (dedup_key 기준 create/update)
     ↓
Agentic Trigger (Aura/에이전트) → HITL (propose → approve/reject/resume)
     ↓
Audit (audit_event_log)
```

---

## 2. 단계별 정의

### 2.1 Ingest Run (원천데이터 적재)

| 항목 | 내용 |
|------|------|
| **개념** | 원천데이터(SAP/파일 등)를 Canonical 테이블(fi_doc_header, fi_doc_item, fi_open_item 등)로 적재하는 실행 단위 |
| **현행** | `sap_raw_events`, `ingestion_errors` 존재. `ingest_run` 테이블 없음 |
| **기록 위치** | (추가 시) audit_event_log event_type: RUN_INGEST_STARTED, RUN_INGEST_COMPLETED, RUN_INGEST_FAILED |
| **payload** | tenant_id, run_id, window_from, window_to, record_count, status |

### 2.2 Detect Run (탐지 실행)

| 항목 | 내용 |
|------|------|
| **개념** | 윈도우(window_from/to) 내 신규/변경 데이터에 룰을 적용하여 케이스를 생성/업데이트하는 실행 단위 |
| **현행** | **이미 구현됨(V21)**. detect_run 테이블, DetectBatchService, 15분 스케줄러, 수동 트리거 API |
| **기록 위치** | audit_event_log: RUN_DETECT_STARTED, RUN_DETECT_COMPLETED, RUN_DETECT_FAILED |
| **payload** | tenant_id, run_id, window_from, window_to, counts_json |

### 2.3 Case Upsert

| 항목 | 내용 |
|------|------|
| **규칙** | dedup_key(tenant+rule+entity) 기준: 있으면 update, 없으면 create |
| **현행** | **이미 도입됨(V21)**. agent_case.dedup_key, ux_agent_case_dedup_key |
| **기록 위치** | audit_event_log: CASE_CREATED, CASE_UPDATED, CASE_STATUS_CHANGED |
| **payload** | tenant_id, case_id, before_json, after_json, diff_json |

### 2.4 Agentic Trigger (에이전트 호출)

| 항목 | 내용 |
|------|------|
| **개념** | 케이스 기반으로 Aura/에이전트가 조치 제안(propose) |
| **현행** | AgentToolController: propose, simulate. ActionController: approve, reject, execute |
| **HITL** | 승인 기반만. approve/reject/resume. 무승인 자동 실행 금지 |

### 2.5 HITL (Human-In-The-Loop)

| 항목 | 내용 |
|------|------|
| **상태** | propose → (approve | reject) → resume(승인된 것만) |
| **기록 위치** | audit_event_log: ACTION_PROPOSED, ACTION_APPROVED, ACTION_REJECTED, ACTION_RESUMED, ACTION_EXECUTED, ACTION_FAILED |
| **payload** | trace_id, tenant_id, user_id, case_id, proposal_id, action_type, evidence_refs, before_after_status |

---

## 3. Audit 이벤트 표준 (audit_event_log)

### 3.1 Run 이벤트

| event_category | event_type | 설명 |
|----------------|------------|------|
| RUN | RUN_INGEST_STARTED | Ingest 배치 시작 |
| RUN | RUN_INGEST_COMPLETED | Ingest 배치 완료 |
| RUN | RUN_INGEST_FAILED | Ingest 배치 실패 |
| RUN | RUN_DETECT_STARTED | Detect 배치 시작 |
| RUN | RUN_DETECT_COMPLETED | Detect 배치 완료 |
| RUN | RUN_DETECT_FAILED | Detect 배치 실패 |

### 3.2 Case 이벤트

| event_category | event_type | 설명 |
|----------------|------------|------|
| CASE | CASE_CREATED | 케이스 생성 |
| CASE | CASE_UPDATED | 케이스 수정 |
| CASE | STATUS_CHANGE | 케이스 상태 변경 |
| CASE | CASE_ASSIGN | 담당자 할당 |
| CASE | CASE_VIEW_LIST | 목록 조회 |
| CASE | CASE_VIEW_DETAIL | 상세 조회 |

### 3.3 Action 이벤트 (HITL)

| event_category | event_type | 설명 |
|----------------|------------|------|
| ACTION | PROPOSE | 조치 제안 |
| ACTION | APPROVE | 승인 |
| ACTION | REJECT | 거절 |
| ACTION | EXECUTE | 실행 |
| ACTION | SIMULATE | 시뮬레이션 |
| ACTION | FAILED | 실행 실패 |

### 3.4 최소 payload

| 필드 | 필수 | 설명 |
|------|------|------|
| tenant_id | O | tenant_id |
| trace_id | 권장 | X-Trace-ID |
| user_id | (HITL) | actor_user_id |
| case_id | (Case/Action) | case_id |
| run_id | (Run) | ingest_run_id 또는 detect_run_id |
| proposal_id | (Action) | action_id |

---

## 4. 헤더/스코프 표준

| 헤더 | 용도 |
|------|------|
| X-Tenant-ID | 멀티테넌시 식별. 모든 API/쿼리 WHERE tenant_id = ? |
| X-User-ID | 사용자 식별. audit actor_user_id |
| X-Agent-ID | 에이전트 세션 식별 |
| X-Trace-ID | 분산 추적. audit_event_log.trace_id |

---

## 5. tenant scope 강제 위치

| 레이어 | 파일/예시 |
|--------|-----------|
| Controller | `@RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId` |
| Repository | `findByTenantIdAndCaseId(Long tenantId, Long caseId)` |
| QueryDSL | `c.tenantId.eq(tenantId)` |
| AuditWriter | `log(tenantId, ...)` |

---

## 6. 갭 및 추가 요청안

| 항목 | 현행 | 제안 |
|------|------|------|
| ingest_run | ✅ 구현됨(V22) | - |
| detect_run | ✅ 구현됨(V21) | - |
| dedup_key | ✅ 구현됨(V21) | - |
| RUN_* 이벤트 | ✅ 구현됨 | audit_event_log event_category=RUN 사용 중 |

---

## 7. 참조

- [data-model.md](./data-model.md)
- [AUDIT_EVENTS_SPEC.md](../../guides/AUDIT_EVENTS_SPEC.md)
- AuditEventConstants.ACTION_*, CASE_*, RUN_* (추가 시)
