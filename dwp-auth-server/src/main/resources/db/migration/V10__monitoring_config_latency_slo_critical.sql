-- ========================================
-- V10: 모니터링 설정 - 지연 시간 SLO 목표 및 Critical 임계치 추가 (코드 관리)
-- 목적: 지연 시간 SLO(목표 ms)와 Critical(장애) 임계치(ms) 설정
-- ========================================

-- 1. sys_codes에 LATENCY_SLO_TARGET, LATENCY_CRITICAL_THRESHOLD 코드 추가
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'LATENCY_SLO_TARGET', '지연 시간 SLO 목표(ms)', '목표: 해당 ms 이내 응답 (예: 500 = 0.5초 이내)', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('MONITORING_CONFIG_KEY', 'LATENCY_CRITICAL_THRESHOLD', '지연 시간 Critical 임계치(ms)', '장애: 해당 ms 초과 시 심각(예: 1500 = 1.5초 초과)', 60, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. sys_monitoring_configs 시드 데이터 (테넌트 1)
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'LATENCY_SLO_TARGET', '500', '목표: 0.5초 이내 응답', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'LATENCY_CRITICAL_THRESHOLD', '1500', '장애: 1.5초 초과 시 심각', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
