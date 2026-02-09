# Phase2 DB 설계 — back.txt vs 현재 구현 비교

> 첨부문서 `back.txt` 대비 현재 테이블 설계 검증

---

## 1. 요약

| 구분 | back.txt 권장 | 현재 BE | 일치 |
|------|---------------|---------|------|
| **테이블명** | synapse_analysis_run 등 | case_analysis_run 등 (dwp_aura 스키마) | ⚠️ 네이밍만 상이 |
| **핵심 필드** | run/result/proposal 기본 구조 | 동일 | ✅ |
| **UNIQUE(case_id, run_id, dedup_key)** | 필수 | ✅ `uk_case_action_proposal_case_run_dedup` | ✅ |
| **dedup_key 서버 계산** | sha256(lower(type)\|canonicalize(payload)\|normalize(rationale)) | ProposalDedupKeyUtil 동일 로직 | ✅ |

---

## 2. 상세 비교

### 2.1 analysis_run (case_analysis_run)

| back.txt 권장 | 현재 BE | 비고 |
|---------------|---------|------|
| run_id (UUID, PK) | ✅ run_id | |
| case_id (BIGINT, FK) | ✅ case_id | FK는 agent_case 미설정 (tenant_id로 협업) |
| status: STARTED\|RUNNING\|COMPLETED\|FAILED\|CANCELED | ✅ STARTED\|RUNNING\|COMPLETED\|FAILED | CANCELED 없음 |
| engine: LOCAL \| AURA | ❌ 없음 | mode(LIVE\|SIMULATION)로 대체 |
| policy_version (nullable) | ❌ 없음 | |
| model_version (nullable) | ❌ 없음 | |
| trigger_source: UI \| BATCH \| API | ❌ 없음 | |
| requested_by | ✅ requested_by (HUMAN\|SYSTEM) | |
| started_at | ✅ started_at | |
| completed_at | ✅ finished_at | 컬럼명만 상이 |
| error_message | ✅ error_message | |
| stream_url (nullable) | ❌ 없음 | |
| idempotency_key (nullable) | ❌ 없음 | Phase2 선택사항으로 생략 |
| **BE 추가** | tenant_id, mode, aura_trace_id, created_at | 멀티테넌시·Aura 추적용 |

**인덱스**

| back.txt | 현재 BE |
|----------|---------|
| INDEX(case_id, started_at desc) | ix_case_analysis_run_tenant_case (tenant_id, case_id) |
| UNIQUE(case_id, idempotency_key) (옵션) | 없음 (Phase2에서 미사용) |

---

### 2.2 analysis_result (case_analysis_result)

| back.txt 권장 | 현재 BE | 비고 |
|---------------|---------|------|
| run_id (PK & FK) | ✅ run_id | |
| case_id (선택, denormalize) | ❌ 없음 | run → case 연결로 조회 |
| score (NUMERIC(5,2)) | ✅ DECIMAL(5,2) | |
| severity | ✅ | |
| reason_text | ✅ | |
| confidence_json | ✅ | |
| evidence_json (evidence+similar+ragRefs 한 덩어리 가능) | evidence_json, similar_json, rag_refs_json 분리 | doc: 한 덩어리 가능 → 분리도 허용 |
| created_at | ✅ | |

---

### 2.3 action_proposal (case_action_proposal)

| back.txt 권장 | 현재 BE | 비고 |
|---------------|---------|------|
| proposal_id (UUID, PK) | ✅ | |
| case_id (BIGINT, FK) | ✅ | |
| run_id (UUID, FK) | ✅ | |
| type | ✅ | |
| status: PROPOSED\|ACCEPTED\|REJECTED\|EXPIRED\|SUPERSEDED | PROPOSED\|APPROVED\|REJECTED\|EXECUTED\|FAILED (+ DRAFT) | EXPIRED, SUPERSEDED 없음 |
| risk_level | ✅ | |
| rationale | ✅ | |
| payload_json | ✅ | |
| dedup_key (CHAR(64)) | ✅ VARCHAR(64) | |
| created_at | ✅ | |
| created_by (nullable) | ❌ 없음 | |
| superseded_by_run_id (옵션) | ❌ 없음 | Phase3용 |

**제약/인덱스**

| back.txt | 현재 BE |
|----------|---------|
| UNIQUE(case_id, run_id, dedup_key) | ✅ uk_case_action_proposal_case_run_dedup |
| INDEX(case_id, run_id, created_at desc) | ix_case_action_proposal_run (run_id) |
| INDEX(case_id, status) | 없음 |

---

## 3. dedup_key 생성 규칙

**back.txt**

```
dedup_key = sha256( lower(type) + '|' + canonicalize_json(payload_json) + '|' + normalize_text(rationale) )
canonicalize_json: key 정렬 + 공백/개행 제거 + 숫자/문자 표준화
normalize_text: trim, lower, 공백 1칸, 특수문자 최소화
```

**현재 ProposalDedupKeyUtil**

- `type`: lower + trim ✅
- `payload`: TreeMap key 정렬, `ObjectMapper.writeValueAsString` (공백 최소)
- `rationale`: trim + `\s+` → 공백 1칸 (lower 없음)

**차이**: rationale에 `lower` 미적용. 대소문자만 다른 동일 문장은 서로 다른 dedup_key가 됨.

---

## 4. 확인 질문 (작업 전)

1. **rationale normalize**: back.txt는 `normalize_text`에 `lower` 포함. 현재 BE는 rationale을 소문자로 만들지 않음. Aura/BE 양쪽에서 동일 제안을 보낼 때 대소문자만 다른 rationale이면 dedup이 안 될 수 있음. `lower` 적용要不要?

2. **engine / policy_version / model_version / trigger_source**: Phase2에서 API 응답에 포함할 예정인가? 문서 2-5 응답 예시에 `engine`, `policyVersion`, `modelVersion`가 있음. Run 테이블에 컬럼 추가할지 결정 필요.

3. **stream_url 저장**: Run 레코드에 `stream_url` 저장 여부. 현재는 202 응답 시에만 반환하고 저장하지 않음. 저장이 필요하면 마이그레이션 추가.

4. **analysis_result의 case_id**: denormalize해서 `case_id`를 넣을지. 현재는 run_id로만 조회하고, run → case_id 연결은 application에서 처리.

---

## 5. 작업 권장 (질문 답변 후)

| 항목 | 우선순위 | 작업 |
|------|----------|------|
| rationale에 lower 적용 | P2 | ProposalDedupKeyUtil.normalizeRationale에 toLowerCase() 추가 |
| engine, policy_version, model_version 컬럼 | P3 | 문서·API 스펙 확정 후 마이그레이션 |
| stream_url 컬럼 | P3 | 필요 시 추가 |
| INDEX(case_id, started_at desc) | P3 | 최신 run 조회 최적화 (현재 tenant_id, case_id로 대체) |
| case_analysis_run FK → agent_case | P3 | 데이터 무결성 강화 시 검토 |

---

*작성: BE 팀 | 참조: back.txt, V32, V34*
