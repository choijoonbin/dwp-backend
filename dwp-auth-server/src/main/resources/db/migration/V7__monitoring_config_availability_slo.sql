-- ========================================
-- V7: 모니터링 설정 - 가용성 SLO 목표값 추가 (코드 관리)
-- ========================================

-- 1. sys_codes에 AVAILABILITY_SLO_TARGET 코드 추가
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'AVAILABILITY_SLO_TARGET', '가용성 SLO 목표(%)', '가용성 성공률 목표 (예: 99.9)', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. sys_monitoring_configs 시드 데이터 (테넌트 1)
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'AVAILABILITY_SLO_TARGET', '99.9', '가용성 SLO 목표 성공률 99.9%', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
