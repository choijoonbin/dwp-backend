-- ========================================
-- V13: 모니터링 설정 - 트래픽 Peak RPS 집계 윈도우 (코드 관리)
-- 목적: Peak RPS 계산 시 사용할 시간 윈도우(초)를 설정. 60=1분, 10=10초 단위 등
-- ========================================

-- 1. sys_codes에 TRAFFIC_PEAK_WINDOW_SECONDS 코드 추가
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'TRAFFIC_PEAK_WINDOW_SECONDS', '트래픽 Peak 집계 윈도우(초)', 'Peak RPS 산출 시 사용하는 시간 버킷(초). 기본 60=1분, 10이면 10초당 최대 RPS', 75, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 2. sys_monitoring_configs 시드 데이터 (테넌트 1)
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'TRAFFIC_PEAK_WINDOW_SECONDS', '60', 'Peak RPS 집계 윈도우(초). 1분=60, 10초=10 등', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
