-- ========================================
-- DWP com_resources 확장 (화면 이벤트 추적 강화) V16
-- 생성일: 2026-01-20
-- 목적: com_resource 세분화 및 이벤트 추적 표준화
-- ========================================

-- ========================================
-- 1. com_resources 컬럼 추가
-- ========================================
ALTER TABLE com_resources
    ADD COLUMN IF NOT EXISTS resource_category VARCHAR(50) NOT NULL DEFAULT 'MENU',
    ADD COLUMN IF NOT EXISTS resource_kind VARCHAR(50) NOT NULL DEFAULT 'PAGE',
    ADD COLUMN IF NOT EXISTS event_key VARCHAR(120),
    ADD COLUMN IF NOT EXISTS event_actions JSONB,
    ADD COLUMN IF NOT EXISTS tracking_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS ui_scope VARCHAR(30);

-- ========================================
-- 2. COMMENT 추가
-- ========================================
COMMENT ON COLUMN com_resources.resource_category IS '리소스 대분류 (MENU/UI_COMPONENT) - 기존 type과 동기화';
COMMENT ON COLUMN com_resources.resource_kind IS '리소스 세부 분류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION)';
COMMENT ON COLUMN com_resources.event_key IS '이벤트 추적 표준 키 (예: menu.admin.users:view, btn.mail.send:click)';
COMMENT ON COLUMN com_resources.event_actions IS '허용되는 action 목록 JSON 배열 (예: ["VIEW","CLICK","SUBMIT","DOWNLOAD"])';
COMMENT ON COLUMN com_resources.tracking_enabled IS '이벤트 추적 활성화 여부 (false면 silent ignore)';
COMMENT ON COLUMN com_resources.ui_scope IS '적용 범위 (GLOBAL/MENU/PAGE/COMPONENT)';

-- ========================================
-- 3. 인덱스 추가
-- ========================================
CREATE INDEX IF NOT EXISTS idx_com_resources_resource_category ON com_resources(resource_category);
CREATE INDEX IF NOT EXISTS idx_com_resources_resource_kind ON com_resources(resource_kind);
CREATE INDEX IF NOT EXISTS idx_com_resources_event_key ON com_resources(event_key);
CREATE INDEX IF NOT EXISTS idx_com_resources_tracking_enabled ON com_resources(tracking_enabled);

-- ========================================
-- 4. 기존 데이터 마이그레이션 (무손실)
-- ========================================

-- 4.1 MENU 리소스 마이그레이션
-- parent_id 없는 MENU는 MENU_GROUP으로 설정
UPDATE com_resources
SET 
    resource_category = 'MENU',
    resource_kind = CASE 
        WHEN parent_resource_id IS NULL THEN 'MENU_GROUP'
        ELSE 'PAGE'
    END,
    event_key = key || ':view',
    event_actions = '["VIEW","USE"]'::jsonb,
    tracking_enabled = true,
    ui_scope = CASE 
        WHEN parent_resource_id IS NULL THEN 'MENU'
        ELSE 'PAGE'
    END
WHERE type = 'MENU';

-- 4.2 UI_COMPONENT 리소스 마이그레이션 (btn.*)
UPDATE com_resources
SET 
    resource_category = 'UI_COMPONENT',
    resource_kind = 'BUTTON',
    event_key = key || ':click',
    event_actions = '["CLICK","USE"]'::jsonb,
    tracking_enabled = true,
    ui_scope = 'COMPONENT'
WHERE type = 'UI_COMPONENT';

-- ========================================
-- 5. type과 resource_category 동기화 트리거 (선택사항)
-- ========================================
-- 향후 type 컬럼 변경 시 resource_category도 자동 동기화
-- 현재는 수동으로 관리 (하위 호환성 유지)

-- ========================================
-- 완료
-- ========================================
-- 기존 13건 데이터 모두 마이그레이션 완료
-- - MENU (11건): resource_category=MENU, resource_kind=MENU_GROUP 또는 PAGE
-- - UI_COMPONENT (2건): resource_category=UI_COMPONENT, resource_kind=BUTTON
-- ========================================
