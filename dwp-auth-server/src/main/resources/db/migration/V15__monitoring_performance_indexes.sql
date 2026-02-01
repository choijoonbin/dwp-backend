-- 모니터링 API 속도 개선: 기간+테넌트 조건 쿼리용 복합 인덱스
-- (docs/guides/MONITORING_API_PERFORMANCE_IMPROVEMENT.md)

-- sys_api_call_histories: 기간 조회 시 tenant_id + created_at 동시 사용
CREATE INDEX IF NOT EXISTS idx_sys_api_call_histories_tenant_created
ON sys_api_call_histories(tenant_id, created_at);

-- sys_page_view_events: 동일
CREATE INDEX IF NOT EXISTS idx_sys_page_view_events_tenant_created
ON sys_page_view_events(tenant_id, created_at);

-- sys_page_view_daily_stats: 일별 구간 조회
CREATE INDEX IF NOT EXISTS idx_sys_page_view_daily_stats_tenant_stat_date
ON sys_page_view_daily_stats(tenant_id, stat_date);
