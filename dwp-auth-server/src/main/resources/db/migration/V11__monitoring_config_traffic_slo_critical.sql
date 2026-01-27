-- ========================================
-- V11: 모니터링 설정 - 트래픽 SLO 목표 및 Critical 임계치 추가 (코드 관리)
-- 목적: 트래픽(부하) SLO(정상 범위 상한 RPS)와 Critical(서버 수용 한계 RPS) 설정
-- ========================================

-- 1. sys_codes에 TRAFFIC_SLO_TARGET, TRAFFIC_CRITICAL_THRESHOLD 코드 추가
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'TRAFFIC_SLO_TARGET', '트래픽 SLO 목표(RPS)', '정상 범위 상한 RPS (예: 100)', 70, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('MONITORING_CONFIG_KEY', 'TRAFFIC_CRITICAL_THRESHOLD', '트래픽 Critical 임계치(RPS)', '서버 수용 한계 RPS, 초과 시 심각(예: 200)', 80, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. sys_monitoring_configs 시드 데이터 (테넌트 1)
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'TRAFFIC_SLO_TARGET', '100', '정상 범위 상한 RPS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'TRAFFIC_CRITICAL_THRESHOLD', '200', '서버 수용 한계 RPS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
