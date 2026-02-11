# Phase3 DDL vs 현재 BE 스키마 비교

## 1. case_analysis_run

| Phase3 DDL | 현재 BE (dwp_aura.case_analysis_run) |
|------------|--------------------------------------|
| run_id PK | ✅ 동일 |
| case_id | ✅ 동일 |
| status | ✅ 동일 |
| **stream_url** TEXT | ❌ 없음 (응답에서만 반환) |
| requested_by | ✅ 동일 |
| started_at | ✅ 동일 |
| **ended_at** | ✅ **finished_at** (이름만 다름) |
| **error_json** JSONB | ✅ **error_message** TEXT (단일 메시지) |
| **meta_json** JSONB | ❌ 없음 |
| created_at, updated_at | ✅ created_at만 있음, updated_at 없음 |
| **tenant_id** | ❌ Phase3 DDL에는 없음, BE는 **있음** (멀티테넌시) |
| mode, aura_trace_id | ❌ Phase3 DDL에는 없음, BE는 있음 |

## 2. case_analysis vs case_analysis_result

| Phase3 (case_analysis) | 현재 BE (case_analysis_result) |
|------------------------|--------------------------------|
| analysis_id BIGSERIAL PK | ❌ 없음 |
| run_id UNIQUE | ✅ run_id PK |
| case_id, score, severity, reason_text | ✅ 동일 |
| confidence_json, evidence_json, rag_refs_json | ✅ 동일 |
| **similar_json** | ❌ Phase3 DDL에는 없음, BE는 **있음** |
| created_at, updated_at | ✅ created_at만, updated_at 없음 |

## 3. case_action_proposal

| Phase3 DDL | 현재 BE |
|------------|---------|
| proposal_id, run_id, case_id | ✅ 동일 |
| type, status, risk_level, rationale, payload_json | ✅ 동일 |
| **fingerprint** TEXT NOT NULL | ✅ **dedup_key** VARCHAR(64) (의미 동일) |
| **decided_by**, **decided_at**, **decision_comment** | ❌ 없음 |
| created_at, updated_at | ✅ 동일 |
| **tenant_id** | ❌ Phase3에는 없음, BE는 **있음** |
| **requires_approval** | ❌ Phase3에는 없음, BE는 **있음** |
| UNIQUE(run_id, fingerprint) | ✅ UNIQUE(case_id, run_id, dedup_key) |

## 4. case_action_execution

| Phase3 DDL | 현재 BE |
|------------|---------|
| execution_id, run_id, case_id, proposal_id | ❌ **테이블 없음** |
| mode, status, result_json, error_json | |
| executed_by, executed_at | |

---

## 요약

- **BE는** 스키마 `dwp_aura`, `tenant_id` 필수, `dedup_key`/`requires_approval` 등으로 이미 운영 중.
- **Phase3 DDL은** 스키마/tenant 미포함, `fingerprint`/`decided_by`/`decided_at`/`decision_comment`, `case_action_execution` 테이블 제안.
- **추가 검토**: `case_action_execution` 도입 여부, proposal에 `decided_by`/`decided_at`/`decision_comment` 컬럼 추가 여부.

원하시면 위 비교를 기준으로 BE에 맞춘 마이그레이션(필요 컬럼/테이블만 추가) 초안을 작성해 드리겠습니다.
