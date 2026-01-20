-- ========================================
-- DWP sys_codes 테넌트 지원 확장 V17
-- 생성일: 2026-01-20
-- 목적: sys_codes에 tenant_id 추가하여 테넌트별 커스텀 코드 지원
-- ========================================

-- ========================================
-- 1. sys_codes에 tenant_id 컬럼 추가
-- ========================================
ALTER TABLE sys_codes
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

-- ========================================
-- 2. COMMENT 추가
-- ========================================
COMMENT ON COLUMN sys_codes.tenant_id IS '테넌트 식별자 (null이면 전사 공통 코드, 값이 있으면 테넌트별 커스텀 코드)';

-- ========================================
-- 3. 인덱스 추가
-- ========================================
CREATE INDEX IF NOT EXISTS idx_sys_codes_tenant_id ON sys_codes(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sys_codes_group_tenant_active ON sys_codes(group_key, tenant_id, is_active);

-- ========================================
-- 4. 기존 데이터는 전사 공통 코드로 유지 (tenant_id = null)
-- ========================================
-- 기존 데이터는 이미 tenant_id가 null이므로 별도 업데이트 불필요

-- ========================================
-- 5. UNIQUE 제약조건 수정 (tenant_id 포함)
-- ========================================
-- 기존: uk_sys_codes_group_code (group_key, code)
-- 신규: tenant_id를 포함한 복합 UNIQUE 제약조건 필요
-- 하지만 기존 데이터와의 호환성을 위해 단계적으로 진행

-- 우선 기존 제약조건 유지하고, 향후 필요시 아래와 같이 변경 가능:
-- ALTER TABLE sys_codes DROP CONSTRAINT IF EXISTS uk_sys_codes_group_code;
-- ALTER TABLE sys_codes ADD CONSTRAINT uk_sys_codes_group_tenant_code 
--     UNIQUE (group_key, COALESCE(tenant_id, -1), code);

-- ========================================
-- 완료
-- ========================================
-- sys_codes 테이블이 테넌트별 커스텀 코드를 지원할 수 있도록 확장됨
-- - tenant_id = null: 전사 공통 코드 (기존 데이터)
-- - tenant_id = 값: 테넌트별 커스텀 코드 (향후 확장)
-- ========================================
