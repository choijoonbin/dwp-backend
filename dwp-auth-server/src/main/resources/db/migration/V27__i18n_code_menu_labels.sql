-- V27: 다국어(ko/en) 라벨 컬럼 추가
-- 목적: Accept-Language 기반 코드/메뉴 라벨 다국어 지원
-- 참고: docs/job/PROMPT_BE_I18N_ACCEPT_LANGUAGE_AND_LABELS.txt

-- 1. sys_codes: name_ko, name_en 추가 (기존 name은 fallback용 유지)
ALTER TABLE sys_codes
  ADD COLUMN IF NOT EXISTS name_ko VARCHAR(255),
  ADD COLUMN IF NOT EXISTS name_en VARCHAR(255);

COMMENT ON COLUMN sys_codes.name_ko IS 'Korean label for UI (i18n)';
COMMENT ON COLUMN sys_codes.name_en IS 'English label for UI (i18n)';

-- 2. sys_menus: menu_name_ko, menu_name_en 추가 (기존 menu_name은 fallback용 유지)
ALTER TABLE sys_menus
  ADD COLUMN IF NOT EXISTS menu_name_ko VARCHAR(255),
  ADD COLUMN IF NOT EXISTS menu_name_en VARCHAR(255);

COMMENT ON COLUMN sys_menus.menu_name_ko IS 'Korean menu display name (i18n)';
COMMENT ON COLUMN sys_menus.menu_name_en IS 'English menu display name (i18n)';
