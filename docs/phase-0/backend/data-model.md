# Phase 0 — 데이터 모델 현황 (DDL/코드 기반)

> 작성일: 2026-01-29 (Updated: Phase B V21 반영)  
> 근거: synapsex-service Flyway 마이그레이션(V1~V21), 실제 Entity/Repository 코드

---

## 1. 스키마 개요

| 스키마 | 용도 |
|--------|------|
| `dwp_aura` | SynapseX 서비스 전용 (Self-healing Finance, Case, Action, Audit) |

---

## 2. 핵심 테이블 현황

### 2.1 Case / Case Item

| 테이블 | tenant_id | PK | FK | 인덱스 | 비고 |
|--------|-----------|-----|-----|--------|------|
| **agent_case** | ✅ NOT NULL | case_id (BIGSERIAL) | - | ix_agent_case_doc(tenant,bukrs,belnr,gjahr,buzei), ix_agent_case_status(tenant,status,detected_at), ix_agent_case_tenant_status_severity, ix_agent_case_assignee | case_item 없음. evidence_json, rag_refs_json으로 증거 저장 |
| **case_comment** | ✅ (case_id FK) | comment_id | agent_case | ix_case_comment_case | V16 |
| **agent_action** | ✅ NOT NULL | action_id | agent_case | ix_agent_action_case | HITL propose/approve/reject/execute 대상 |

**agent_case 컬럼**: case_id, tenant_id, detected_at, bukrs, belnr, gjahr, buzei, case_type, severity, score, reason_text, evidence_json, rag_refs_json, status, owner_user, assignee_user_id, saved_view_key, **dedup_key**(V21), **last_detect_run_id**(V22), created_at, updated_at

**agent_case_status enum**: OPEN, IN_REVIEW, APPROVED, REJECTED, ACTIONED, CLOSED, TRIAGED, IN_PROGRESS, RESOLVED, DISMISSED

**dedup_key**: tenant+rule+entity 복합키. ux_agent_case_dedup_key (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL

---

### 2.2 Document / Open Item

| 테이블 | tenant_id | PK | 인덱스 | 비고 |
|--------|-----------|-----|--------|------|
| **fi_doc_header** | ✅ NOT NULL | (tenant_id, bukrs, belnr, gjahr) | ix_fi_doc_header_budat, ix_fi_doc_header_xblnr, ix_fi_doc_header_user_time | FI 전표 헤더 |
| **fi_doc_item** | ✅ NOT NULL | (tenant_id, bukrs, belnr, gjahr, buzei) | ix_fi_doc_item_partner, ix_fi_doc_item_hkont, ix_fi_doc_item_amount | FK → fi_doc_header |
| **fi_open_item** | ✅ NOT NULL | (tenant_id, bukrs, belnr, gjahr, buzei) | ix_fi_open_item_due, ix_fi_open_item_partner | AP/AR 오픈아이템 |

**문서 식별자**: docKey = `bukrs-belnr-gjahr`, openItemKey = `bukrs-belnr-gjahr-buzei` (item_type 포함 시 복합)

---

### 2.3 Audit Event Log

| 테이블 | tenant_id | trace_id | PK | 인덱스 | 비고 |
|--------|-----------|----------|-----|--------|------|
| **audit_event_log** | ✅ NOT NULL | ✅ 컬럼 존재 | audit_id (BIGSERIAL) | ix_audit_event_log_tenant_created, ix_audit_event_log_tenant_category_type, ix_audit_event_log_resource, ix_audit_event_log_actor, ix_audit_event_log_outcome | **SoT** Synapse 감사 |
| **synapse_audit_event_log** | ✅ NOT NULL | ❌ | id | ix_synapse_audit_* | 레거시(?) V5. audit_event_log가 주 사용 |

**audit_event_log 컬럼**: audit_id, tenant_id, event_category, event_type, resource_type, resource_id, created_at, actor_type, actor_user_id, actor_agent_id, actor_display_name, channel, ip_address, user_agent, outcome, severity, before_json, after_json, diff_json, evidence_json, tags, gateway_request_id, **trace_id**, span_id

---

### 2.4 Lineage / Reconciliation

| 테이블 | tenant_id | PK | 비고 |
|--------|-----------|-----|------|
| **recon_run** | ✅ NOT NULL | run_id | V14. run_type: DOC_OPENITEM_MATCH, ACTION_EFFECT 등 |
| **recon_result** | ✅ NOT NULL | result_id | run_id FK. resource_key = bukrs-belnr-gjahr-buzei |
| **sap_change_log** | ✅ NOT NULL | (tenant_id, objectclas, objectid, changenr, tabname, fname) | CDHDR/CDPOS 스타일 변경 이력 |

**detect_run**: Phase B V21 구현 완료. recon_run은 Reconciliation 전용.

---

### 2.5 Raw / Ingestion (원천데이터)

| 테이블 | tenant_id | PK | 비고 |
|--------|-----------|-----|------|
| **sap_raw_events** | ✅ NOT NULL | id | 원천 이벤트. extract_date, interface_name, payload_json |
| **ingestion_errors** | ✅ NOT NULL | id | raw_event_id FK. 에러 기록 |

**갭**: `ingest_run` 테이블 없음. 배치 id, 기간, 건수, 상태를 담는 실행 단위 미정의.

---

## 3. Run 테이블

### 3.1 detect_run (구현 완료 V21)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| run_id | BIGSERIAL | PK |
| tenant_id | BIGINT NOT NULL | 테넌트 |
| window_from | TIMESTAMPTZ NOT NULL | 탐지 윈도우 시작 |
| window_to | TIMESTAMPTZ NOT NULL | 탐지 윈도우 종료 |
| status | VARCHAR(20) | STARTED, COMPLETED, FAILED |
| counts_json | JSONB | {"caseCreated":N,"caseUpdated":N} |
| error_message | TEXT | 실패 시 에러 메시지 |
| started_at | TIMESTAMPTZ NOT NULL | 시작 시각 |
| completed_at | TIMESTAMPTZ | 완료 시각 |

**인덱스**: ix_detect_run_tenant_created(tenant_id, started_at DESC), ix_detect_run_tenant_status(tenant_id, status)

### 3.2 ingest_run (구현 완료 V22)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| run_id | BIGSERIAL | PK |
| tenant_id | BIGINT NOT NULL | 테넌트 |
| batch_id | TEXT | 배치 식별자 |
| window_from | TIMESTAMPTZ | 대상 기간 시작 |
| window_to | TIMESTAMPTZ | 대상 기간 종료 |
| record_count | INT | 적재 건수 |
| status | VARCHAR(20) | STARTED, COMPLETED, FAILED |
| error_message | TEXT | 실패 시 에러 |
| started_at | TIMESTAMPTZ NOT NULL | 시작 시각 |
| completed_at | TIMESTAMPTZ | 완료 시각 |

**인덱스**: ix_ingest_run_tenant_created, ix_ingest_run_tenant_status

---

## 4. Case Upsert 규칙 (추가 요청안)

- **dedup_key**: `tenant_id + rule_id + entity_key` (예: tenant+rule+bukrs-belnr-gjahr-buzei)
- **caseId 생성**: BIGSERIAL (현행) 또는 ULID/UUID 도입 검토
- **상태머신**: NEW → TRIAGED → ACTION_REQUIRED → RESOLVED (+ CLOSED)
  - 현행 enum: OPEN, TRIAGED, IN_PROGRESS, RESOLVED, DISMISSED, CLOSED, IN_REVIEW, APPROVED, REJECTED, ACTIONED
  - Phase 0 표준과 매핑 필요

---

## 5. Tenant Scope 적용 현황

| 레이어 | 적용 방식 |
|--------|-----------|
| Repository | `findByTenantId*`, `tenant_id = ?` 조건 필수 |
| QueryDSL | `c.tenantId.eq(tenantId)` 등 predicate |
| Controller | `@RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId` |
| FiDocumentScopeRepository | Native Query에 `tenant_id = :tid` 포함 |

**검증**: Repository 메서드명/시그니처에 tenantId 포함. QueryDSL Custom에서 tenant 조건 누락 시 갭.

---

## 6. 인덱스 요약

| 테이블 | 주요 인덱스 |
|--------|-------------|
| agent_case | tenant_id+status+severity+detected_at, tenant_id+assignee_user_id, tenant_id+dedup_key |
| detect_run | tenant_id+started_at DESC, tenant_id+status |
| audit_event_log | tenant_id+created_at, tenant_id+event_category+event_type+created_at, tenant_id+resource_type+resource_id |
| fi_doc_header | tenant_id+budat, tenant_id+xblnr |
| fi_open_item | tenant_id+due_date+cleared |

---

## 7. 참조

- `services/synapsex-service/src/main/resources/db/migration/`
- `services/synapsex-service/src/main/java/com/dwp/services/synapsex/entity/`
- `services/synapsex-service/src/main/java/com/dwp/services/synapsex/repository/`
