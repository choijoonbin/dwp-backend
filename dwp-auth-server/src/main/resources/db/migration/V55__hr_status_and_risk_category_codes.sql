-- V55: Aura 분석 컨텍스트 코드 추가 (HR_STATUS, RISK_CATEGORY)

INSERT INTO sys_codes (
    group_key, code, name, name_ko, name_en, description,
    sort_order, is_active, tenant_id, created_at, updated_at
)
VALUES
    ('HR_STATUS', 'WORKING', '근무 중', '근무 중', 'Working', '평일 기본 근무 상태', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('HR_STATUS', 'VACATION', '휴가/연차', '휴가/연차', 'Vacation', '평일 중 개인 휴가', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('HR_STATUS', 'OFF', '휴무/비번', '휴무/비번', 'Off', '토/일/공휴일 기본 상태', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('HR_STATUS', 'BUSINESS_TRIP', '출장', '출장', 'Business Trip', '출장 근무 상태', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RISK_CATEGORY', 'PROHIBITED', '금지', '금지', 'Prohibited', '규정상 금지 업종', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RISK_CATEGORY', 'CAUTION', '주의', '주의', 'Caution', '규정상 주의 업종', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RISK_CATEGORY', 'ALLOWED', '허용', '허용', 'Allowed', '규정상 허용 업종', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE
SET name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;
