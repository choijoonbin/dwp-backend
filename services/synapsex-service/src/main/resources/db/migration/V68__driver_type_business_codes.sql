-- DRIVER_TYPE: 기술 코드(DOC_WINDOW, OPEN_ITEM_WINDOW 등) → 비즈니스 6종 + DEFAULT 마이그레이션
-- Aura AI Screening 고도화: HOLIDAY_USAGE, DUPLICATE_SUSPECT, SPLIT_PAYMENT, PRIVATE_USE_RISK, LIMIT_EXCEED, UNUSUAL_PATTERN, DEFAULT
-- 최종 확인: app_codes DRIVER_TYPE 그룹에 위 7건(6종 비즈니스 코드 + DEFAULT) INSERT/UPDATE 적용됨.

SET search_path TO dwp_aura, public;

-- 1) 기존 DRIVER_TYPE 코드 비활성화 (참조 무결성 유지 위해 삭제 대신 is_active = false)
UPDATE dwp_aura.app_codes
SET is_active = false, updated_at = now()
WHERE group_key = 'DRIVER_TYPE';

-- 2) 6가지 비즈니스 코드 + DEFAULT 삽입
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES ('DRIVER_TYPE', 'Driver Type', 'AI Screening 비즈니스 유형 (Top Risk Drivers)', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('DRIVER_TYPE', 'HOLIDAY_USAGE', '휴일/심야 사적 유용 의심', '휴일/심야 시간대 사적 유용 의심', 10, true, now(), now()),
  ('DRIVER_TYPE', 'DUPLICATE_SUSPECT', '중복 청구 및 분할 결제 의심', '중복 청구·분할 결제 의심', 20, true, now(), now()),
  ('DRIVER_TYPE', 'SPLIT_PAYMENT', '한도 우회 분할 결제 의심', '한도 우회를 위한 전표 쪼개기 의심', 30, true, now(), now()),
  ('DRIVER_TYPE', 'PRIVATE_USE_RISK', '가맹점 성격 업무 무관', '유흥·취미 등 업무와 무관한 가맹점', 40, true, now(), now()),
  ('DRIVER_TYPE', 'LIMIT_EXCEED', '지출 한도·가이드라인 초과', '사내 지출 한도 및 가이드라인 초과', 50, true, now(), now()),
  ('DRIVER_TYPE', 'UNUSUAL_PATTERN', '이상 거래 패턴', '과거 패턴과 다른 이상 거래', 60, true, now(), now()),
  ('DRIVER_TYPE', 'DEFAULT', '기타', '분류되지 않거나 Aura 미반환 시', 100, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, sort_order = EXCLUDED.sort_order, is_active = true, updated_at = now();

-- 3) agent_case.case_type: 기존 기술 코드 → DEFAULT로 치환 (새 코드에 없는 값)
UPDATE dwp_aura.agent_case c
SET case_type = 'DEFAULT', updated_at = now()
WHERE c.case_type IS NOT NULL
  AND c.case_type <> ''
  AND NOT EXISTS (
    SELECT 1 FROM dwp_aura.app_codes ac
    WHERE ac.group_key = 'DRIVER_TYPE' AND ac.code = c.case_type AND ac.is_active = true
  );
