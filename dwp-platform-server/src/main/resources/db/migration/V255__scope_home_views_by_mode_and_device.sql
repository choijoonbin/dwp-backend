-- Home Composition v4 is an application-level, expand-only envelope. Persisted v1-v3 tenant
-- policies remain readable during rolling deployment and are normalized to v4 by the registry.
-- New v4 writes contain modeLayouts for CLASSIC and FLOW_V1 without embedding member payloads.

COMMENT ON COLUMN adm_home_experiences.composition_policy IS
    'Tenant Home Composition policy. Readers accept v1-v4; canonical writes use v4 modeLayouts. Member layouts remain in usr_home_views.';

-- Existing view rows are the Classic projection. A mode column lets Classic and Flow keep
-- independent active/default views while all child state stays scoped by immutable view_id.
ALTER TABLE usr_home_views
    ADD COLUMN IF NOT EXISTS mode_key VARCHAR(16) NOT NULL DEFAULT 'CLASSIC';

ALTER TABLE usr_home_views
    DROP CONSTRAINT IF EXISTS ck_usr_home_views_mode;
ALTER TABLE usr_home_views
    ADD CONSTRAINT ck_usr_home_views_mode
        CHECK (mode_key IN ('CLASSIC', 'FLOW_V1'));

DROP INDEX IF EXISTS uk_usr_home_views_scope_key_active;
CREATE UNIQUE INDEX uk_usr_home_views_scope_key_active
    ON usr_home_views (tenant_id, user_id, surface_key, mode_key, view_key)
    WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS uk_usr_home_views_default;
CREATE UNIQUE INDEX uk_usr_home_views_default
    ON usr_home_views (tenant_id, user_id, surface_key, mode_key)
    WHERE is_default AND deleted_at IS NULL;

DROP INDEX IF EXISTS idx_usr_home_views_owner;
CREATE INDEX idx_usr_home_views_owner
    ON usr_home_views (tenant_id, user_id, surface_key, mode_key, updated_at DESC);

-- Legacy device names have one deterministic projection. Writers accept the aliases but only
-- these four canonical values are persisted after this migration.
ALTER TABLE usr_home_view_device_layouts
    DROP CONSTRAINT IF EXISTS ck_usr_home_view_device_class;

UPDATE usr_home_view_device_layouts
   SET device_class = CASE device_class
       WHEN 'DESKTOP' THEN 'DESKTOP_STANDARD'
       WHEN 'MOBILE' THEN 'MOBILE_STANDARD'
       ELSE device_class
   END,
       updated_at = CURRENT_TIMESTAMP
 WHERE device_class IN ('DESKTOP', 'MOBILE');

ALTER TABLE usr_home_view_device_layouts
    ADD CONSTRAINT ck_usr_home_view_device_class
        CHECK (device_class IN (
            'DESKTOP_WIDE', 'DESKTOP_STANDARD', 'MOBILE_STANDARD', 'MOBILE_COMPACT'));

COMMENT ON COLUMN usr_home_views.mode_key IS
    'Immutable Home experience namespace. CLASSIC and FLOW_V1 defaults and layouts are independent.';
COMMENT ON COLUMN usr_home_view_device_layouts.device_class IS
    'Canonical responsive overlay: DESKTOP_WIDE, DESKTOP_STANDARD, MOBILE_STANDARD, or MOBILE_COMPACT.';
