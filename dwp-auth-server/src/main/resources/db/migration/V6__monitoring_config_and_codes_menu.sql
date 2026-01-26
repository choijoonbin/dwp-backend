-- ========================================
-- V6: 모니터링 설정 테이블 및 코드 메뉴 연동
-- 목적: sys_monitoring_configs 생성, sys_codes에 사용 메뉴 컬럼 추가, 모니터링 설정 코드 등록
-- ========================================

-- ========================================
-- 1. sys_monitoring_configs (모니터링 기준 설정)
-- config_key는 sys_codes 코드값(예: MIN_REQ_PER_MINUTE, ERROR_RATE_THRESHOLD)으로 관리
-- ========================================
CREATE TABLE sys_monitoring_configs (
    monitoring_config_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    config_value VARCHAR(500) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_sys_monitoring_configs_tenant_key UNIQUE (tenant_id, config_key)
);

CREATE INDEX idx_sys_monitoring_configs_tenant_id ON sys_monitoring_configs(tenant_id);
CREATE INDEX idx_sys_monitoring_configs_config_key ON sys_monitoring_configs(config_key);

COMMENT ON TABLE sys_monitoring_configs IS '모니터링 기준 설정 (테넌트별, config_key는 sys_codes 코드로 관리)';
COMMENT ON COLUMN sys_monitoring_configs.monitoring_config_id IS '설정 식별자 (PK)';
COMMENT ON COLUMN sys_monitoring_configs.tenant_id IS '테넌트 식별자';
COMMENT ON COLUMN sys_monitoring_configs.config_key IS '설정 키 (sys_codes 코드값: MIN_REQ_PER_MINUTE, ERROR_RATE_THRESHOLD 등)';
COMMENT ON COLUMN sys_monitoring_configs.config_value IS '설정 값 (예: 1, 5.0)';
COMMENT ON COLUMN sys_monitoring_configs.description IS '설정 항목 설명';
COMMENT ON COLUMN sys_monitoring_configs.created_at IS '생성일시';
COMMENT ON COLUMN sys_monitoring_configs.created_by IS '생성자 user_id';
COMMENT ON COLUMN sys_monitoring_configs.updated_at IS '수정일시';
COMMENT ON COLUMN sys_monitoring_configs.updated_by IS '수정자 user_id';

-- ========================================
-- 2. sys_codes에 menu_key 컬럼 추가 (어느 메뉴에서 사용하는지)
-- ========================================
ALTER TABLE sys_codes ADD COLUMN IF NOT EXISTS menu_key VARCHAR(255);
COMMENT ON COLUMN sys_codes.menu_key IS '주로 사용되는 메뉴 키 (sys_code_usages.resource_key 기준)';

-- 기존 코드에 메뉴 보정: code_group_key로 쓰이는 resource_key 하나씩 매핑
UPDATE sys_codes c
SET menu_key = sq.resource_key
FROM (
    SELECT DISTINCT ON (code_group_key) code_group_key, resource_key
    FROM sys_code_usages
    ORDER BY code_group_key, tenant_id, resource_key
) sq
WHERE c.group_key = sq.code_group_key AND (c.menu_key IS NULL OR c.menu_key = '');

-- ========================================
-- 3. 코드 그룹 / 코드: 모니터링 설정 키 (MONITORING_CONFIG_KEY)
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', '모니터링 설정 키', '가용성/다운타임 계산에 쓰이는 설정 키 (분당 최소 요청 수, 에러율 임계치 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('MONITORING_CONFIG_KEY', 'MIN_REQ_PER_MINUTE', '분당 최소 호출 수', '분당 최소 요청 건수 이상일 때만 다운타임 체크 (저트래픽 노이즈 제거)', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('MONITORING_CONFIG_KEY', 'ERROR_RATE_THRESHOLD', '에러율 임계치(%)', '해당 % 초과 시 1분 버킷을 장애(다운타임)로 간주', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 모니터링 설정 코드 사용 메뉴: 통합 모니터링
UPDATE sys_codes SET menu_key = 'menu.admin.monitoring' WHERE group_key = 'MONITORING_CONFIG_KEY';

INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.monitoring', 'MONITORING_CONFIG_KEY', 'MENU', true, 10, '통합 모니터링 설정(가용성/다운타임 기준)', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. 초기 데이터 (Seed): 테넌트 1 기준
-- ========================================
INSERT INTO sys_monitoring_configs (tenant_id, config_key, config_value, description, created_at, updated_at)
VALUES
    (1, 'MIN_REQ_PER_MINUTE', '1', '분당 최소 1건 이상일 때만 다운타임 판정', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'ERROR_RATE_THRESHOLD', '5.0', '5xx 에러율 5% 초과 시 해당 분을 장애로 집계', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, config_key) DO UPDATE SET
    config_value = EXCLUDED.config_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

SELECT setval('sys_monitoring_configs_monitoring_config_id_seq', (SELECT COALESCE(MAX(monitoring_config_id), 1) FROM sys_monitoring_configs));
