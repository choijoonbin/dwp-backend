# Phase B — Window Detection Batch 결과

> 완료일: 2026-01-29

---

## 1. 구현 항목

### 1.1 detect_run 테이블 (V21)
- run_id, tenant_id, window_from, window_to, status, counts_json, error_message, started_at, completed_at
- 인덱스: (tenant_id, started_at DESC), (tenant_id, status)

### 1.2 agent_case.dedup_key
- tenant+rule+entity 복합키. ux_agent_case_dedup_key (tenant_id, dedup_key) WHERE dedup_key IS NOT NULL

### 1.3 DetectBatchService
- window 내 fi_doc_header 조회 (created_at between)
- dedup_key = tenantId:RULE_ID:entityKey (entityKey = bukrs-belnr-gjahr)
- 있으면 update (detected_at, updated_at), 없으면 create
- RUN_DETECT_STARTED/COMPLETED/FAILED, CASE_CREATED/UPDATED audit 기록

### 1.4 DetectBatchScheduler
- @ConditionalOnProperty(detect.batch.enabled=true)
- 기본 15분 cron: 0 */15 * * * *
- config.getTenantIds() (기본 [1])

### 1.5 수동 트리거 API
- **POST** `/api/synapse/admin/detect/run`
- X-Tenant-ID 필수
- windowFrom, windowTo (선택, ISO 8601) — 생략 시 최근 15분

---

## 2. 검증 시나리오

```bash
# 1) 더미 전표 적재 (fi_doc_header에 created_at이 현재 시간인 레코드)
# 2) 수동 트리거
curl -X POST -H "X-Tenant-ID: 1" "http://localhost:8080/api/synapse/admin/detect/run"

# 3) agent_case에서 신규 생성/업데이트 확인
# 4) audit_event_log에서 RUN_DETECT_*, CASE_* 확인
SELECT event_category, event_type, resource_type, resource_id, created_at
FROM dwp_aura.audit_event_log
WHERE event_category IN ('RUN', 'CASE')
ORDER BY created_at DESC LIMIT 20;
```

---

## 3. 설정

```yaml
# application.yml
detect:
  batch:
    enabled: false   # true 시 15분마다 스케줄 실행
    cron: "0 */15 * * * *"
```

---

## 4. 후속조치 완료 (FOLLOWUP_PROMPT)
- ✅ DB advisory lock (PostgreSQL pg_try_advisory_lock, tenant별)
- ✅ fi_open_item 윈도우 탐지 포함 (last_update_ts between)
- ✅ agent_case.last_detect_run_id (역추적)
- ✅ counts_json: created_count, updated_count, suppressed_count
- ✅ ingest_run 테이블 (V22)
