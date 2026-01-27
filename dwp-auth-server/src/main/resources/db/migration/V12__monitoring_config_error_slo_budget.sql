-- ========================================
-- V12: 모니터링 설정 - 에러율 SLO 목표 및 에러 버짓 (코드 관리)
-- 목적: Error 카드 판정용 ERROR_RATE_SLO_TARGET(목표 에러율 %), ERROR_BUDGET_TOTAL(기준 기간 허용 에러)
-- ========================================

-- 1. sys_codes에 ERROR_RATE_SLO_TARGET, ERROR_BUDGET_TOTAL 코드 추가
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'ERROR_RATE_SLO_TARGET', '에러율 SLO 목표(%)', '목표 에러율 미만 유지 (예: 0.5 = 0.5%)', 90, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('MONITORING_CONFIG_KEY', 'ERROR_BUDGET_TOTAL', '에러 버짓 총량', '기준 기간 내 허용된 총 에러 건수 또는 비율 기준값', 100, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. sys_monitoring_configs 시드 데이터 (테넌트 1)
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'ERROR_RATE_SLO_TARGET', '0.5', '목표 에러율 0.5% 미만 유지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'ERROR_BUDGET_TOTAL', '100', '기준 기간 내 허용 에러 관련값', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
