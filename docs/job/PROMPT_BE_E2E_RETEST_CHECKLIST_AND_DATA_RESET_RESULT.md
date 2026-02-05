# E2E 재테스트 체크리스트 적용 결과

참고: [PROMPT_BE_E2E_RETEST_CHECKLIST_AND_DATA_RESET.txt](PROMPT_BE_E2E_RETEST_CHECKLIST_AND_DATA_RESET.txt)

## P0 적용 완료

### 1) detect_run ↔ CASE_* 연계
- **audit_event_log**: RUN_DETECT_* 이벤트 `resource_id`=run_id, CASE_CREATED/UPDATED `tags.runId` 포함
- **검증**: `docs/e2e/seed/VERIFY_QUERIES.sql`에 RUN_DETECT_* ↔ CASE_* 연계 쿼리 추가

### 2) Upsert 핵심 필드 + Audit payload
- **agent_case**: dedup_key, last_detect_run_id, detected_at, updated_at (기존 구현 유지)
- **CASE_CREATED/UPDATED audit payload**: `afterJson`에 `dedupKey`, `ruleId`, `entityKey`, `runId` 추가
  - `DetectBatchService.java` 수정

### 3) Amount 표시 (P0-3)
- **Case list API** (`CaseListRowDto`): `amount`, `currency` 필드 추가
- **Case detail API** (`EvidencePanelDto`, `DocumentOrOpenItemDto`): `amount`, `currency` 추가
- **소스**: fi_doc_item.wrbtr 합계 또는 fi_open_item.open_amount, fi_doc_header.waers / fi_open_item.currency

### 4) HITL 승인/거절 연결성 (P0-4)
- **ACTION APPROVE/REJECT audit payload**: `afterJson`에 `case_id`, `proposal_id`, `trace_id` 포함
- **ActionController**: approve/reject 시 `X-Trace-ID` 헤더 전달
- **ActionCommandService**: `approveAction`, `rejectAction`에 traceId 파라미터 추가

### 5) detect 범위 재현성
- **seed**: `phase2-4-seed.sql`, `seed_synapse_phase.sql` — created_at/last_update_ts를 `now() - 10분`으로 설정
- **PACK_A-E_seed**: base_time 파라미터로 윈도우 제어 (기존 유지)

## 재테스트 데이터 리셋

- **스크립트**: `scripts/reset_e2e_data.sql`
- **Purge 대상**: agent_action, case_comment, agent_case, audit_event_log, detect_run, ingest_run, idempotency_key, agent_activity_log, integration_outbox
- **유지**: fi_doc_header, fi_open_item, bp_party (원천 입력)

## 검증 쿼리

```bash
psql -h localhost -U dwp_user -d dwp_db -f docs/e2e/seed/VERIFY_QUERIES.sql
```
