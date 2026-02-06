-- V31: sys_codes name_ko, name_en NULL 복구
-- 원인: V14에서 MONITORING_CONFIG_KEY 코드명 변경(AVAILABILITY_*) 후 V28의 UPDATE가 매칭되지 않음.
--       DB 리셋/재기동 시 해당 코드 및 기타 누락 코드가 NULL로 남음.
-- 참고: 수동으로 DB에 입력한 값은 COALESCE로 보존 (NULL인 경우에만 업데이트)

-- ========================================
-- 1. V14에서 변경된 MONITORING_CONFIG_KEY 코드 (V28이 매칭 실패했던 항목)
-- ========================================
UPDATE sys_codes SET
    name_ko = COALESCE(name_ko, name),
    name_en = COALESCE(name_en, 'Min Requests Per Minute')
WHERE group_key = 'MONITORING_CONFIG_KEY' AND code = 'AVAILABILITY_MIN_REQ_PER_MINUTE';

UPDATE sys_codes SET
    name_ko = COALESCE(name_ko, name),
    name_en = COALESCE(name_en, 'Error Rate Threshold (%)')
WHERE group_key = 'MONITORING_CONFIG_KEY' AND code = 'AVAILABILITY_ERROR_RATE_THRESHOLD';

-- ========================================
-- 2. MONITORING_CONFIG_KEY 기타 코드 (V7~V13 추가분)
-- ========================================
UPDATE sys_codes SET name_ko = COALESCE(name_ko, name), name_en = COALESCE(name_en, name)
WHERE group_key = 'MONITORING_CONFIG_KEY' AND (name_ko IS NULL OR name_en IS NULL);

-- ========================================
-- 3. ROLE_STATUS (V2)
-- ========================================
UPDATE sys_codes SET name_ko = COALESCE(name_ko, name), name_en = COALESCE(name_en, name)
WHERE group_key = 'ROLE_STATUS' AND (name_ko IS NULL OR name_en IS NULL);

-- ========================================
-- 4. RESOURCE_CATEGORY, RESOURCE_KIND, UI_ACTION (V1 후반)
-- ========================================
UPDATE sys_codes SET name_ko = COALESCE(name_ko, name), name_en = COALESCE(name_en, name)
WHERE group_key IN ('RESOURCE_CATEGORY', 'RESOURCE_KIND', 'UI_ACTION') AND (name_ko IS NULL OR name_en IS NULL);

-- ========================================
-- 5. 전체 sys_codes: name_ko/name_en이 NULL인 행은 name으로 fallback
--    (수동 입력값은 COALESCE로 보존)
-- ========================================
UPDATE sys_codes
SET
    name_ko = COALESCE(name_ko, name),
    name_en = COALESCE(name_en, name)
WHERE name_ko IS NULL OR name_en IS NULL;
