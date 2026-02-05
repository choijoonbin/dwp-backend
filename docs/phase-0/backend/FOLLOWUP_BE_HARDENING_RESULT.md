# Post-PhaseB Hardening 결과

> 완료일: 2026-01-29

---

## P0 필수

### 1.1 문서 정합성 ✅
- `standard-process.md`: detect_run, dedup_key "이미 구현됨" 반영
- `data-model.md`: detect_run 구현 완료(V21), ingest_run 구현 완료(V22), agent_case.last_detect_run_id

### 1.2 DB Advisory Lock ✅
- **구현**: DetectBatchService에서 `pg_try_advisory_lock(lockKey)` 사용
- **lockKey**: 1_000_000_000_000L + tenantId (tenant별 고유)
- **미획득 시**: null 반환, 스케줄러/컨트롤러에서 SKIPPED 처리
- **해제**: finally 블록에서 `pg_advisory_unlock(lockKey)`

**검증**: 동일 tenant에 2개 인스턴스 동시 실행 시 1개만 RUNNING, 나머지는 SKIPPED

### 1.3 ingest_run ✅
- **V22 마이그레이션**: ingest_run 테이블
- 컬럼: run_id, tenant_id, batch_id, window_from, window_to, record_count, status, error_message, started_at, completed_at
- 인덱스: ix_ingest_run_tenant_created, ix_ingest_run_tenant_status

---

## P1 강력 권장

### 2.1 fi_open_item 윈도우 포함 ✅
- FiOpenItemRepository.findByTenantIdAndLastUpdateTsBetween(tenantId, from, to)
- RULE_ID_OPEN_ITEM, entityKey = bukrs-belnr-gjahr-buzei
- dedup_key = tenantId:WINDOW_OPEN_ITEM:entityKey

### 2.2 detect_run ↔ case 연결성 ✅
- agent_case.last_detect_run_id (V22)
- counts_json: caseCreated, caseUpdated, created_count, updated_count, suppressed_count
- upsert 시 lastDetectRunId 설정

---

## 검증 시나리오

```bash
# 1) Advisory lock: 수동 트리거 2번 동시 호출
# → 1개 COMPLETED, 1개 SKIPPED

# 2) audit_event_log
SELECT event_category, event_type, resource_type, resource_id
FROM dwp_aura.audit_event_log
WHERE event_category IN ('RUN', 'CASE')
ORDER BY created_at DESC LIMIT 20;

# 3) ingest_run (적재 배치 연동 시)
SELECT * FROM dwp_aura.ingest_run ORDER BY started_at DESC LIMIT 5;
```
