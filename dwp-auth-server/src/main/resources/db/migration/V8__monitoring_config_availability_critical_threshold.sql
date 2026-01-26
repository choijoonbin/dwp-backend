-- ========================================
-- V8: 모니터링 설정 - 가용성 Critical 임계치 추가 (코드 관리)
-- 목적: 이 값 미만일 때만 Critical 배지·빨간색 UI 노출
-- ========================================

-- 1. sys_codes에 AVAILABILITY_CRITICAL_THRESHOLD 코드 추가
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'AVAILABILITY_CRITICAL_THRESHOLD', '가용성 Critical 임계치(%)', '이 값 미만일 때만 Critical 배지와 빨간색 UI 노출', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. sys_monitoring_configs 시드 데이터 (테넌트 1)
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'AVAILABILITY_CRITICAL_THRESHOLD', '99.0', '가용성 99% 미만이면 Critical 배지·빨간색 UI 노출', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
