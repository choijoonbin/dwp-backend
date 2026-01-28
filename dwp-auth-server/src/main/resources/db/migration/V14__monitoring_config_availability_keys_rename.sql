-- ========================================
-- V14: 가용성 관련 모니터링 설정 키 코드명칭 변경
-- 목적: MIN_REQ_PER_MINUTE -> AVAILABILITY_MIN_REQ_PER_MINUTE,
--       ERROR_RATE_THRESHOLD -> AVAILABILITY_ERROR_RATE_THRESHOLD
--       (가용성 KPI 전용임을 코드명에 반영)
-- ========================================

-- 1. sys_monitoring_configs 기존 데이터 config_key 업데이트
UPDATE sys_monitoring_configs
SET config_key = 'AVAILABILITY_MIN_REQ_PER_MINUTE', updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'MIN_REQ_PER_MINUTE';

UPDATE sys_monitoring_configs
SET config_key = 'AVAILABILITY_ERROR_RATE_THRESHOLD', updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'ERROR_RATE_THRESHOLD';

-- 2. sys_codes 코드값 업데이트 (MONITORING_CONFIG_KEY 그룹)
UPDATE sys_codes
SET code = 'AVAILABILITY_MIN_REQ_PER_MINUTE',
    name = '분당 최소 호출 수(가용성)',
    description = '분당 최소 요청 건수 이상일 때만 다운타임 체크 (저트래픽 노이즈 제거)',
    updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'MONITORING_CONFIG_KEY' AND code = 'MIN_REQ_PER_MINUTE';

UPDATE sys_codes
SET code = 'AVAILABILITY_ERROR_RATE_THRESHOLD',
    name = '에러율 임계치(가용성, %)',
    description = '해당 % 초과 시 1분 버킷을 장애(다운타임)로 간주',
    updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'MONITORING_CONFIG_KEY' AND code = 'ERROR_RATE_THRESHOLD';
