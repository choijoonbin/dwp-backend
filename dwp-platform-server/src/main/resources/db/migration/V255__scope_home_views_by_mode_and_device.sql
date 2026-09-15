-- Home Composition v4 is an application-level, expand-only envelope. Persisted v1-v3 tenant
-- policies remain readable. V4/mode-scoped application writes are protected by the
-- DWP_HOME_MODE_V4_ACTIVATION_ENABLED fleet-drain interlock; see
-- contracts/home-mode-v4-rollout.md. New v4 writes contain modeLayouts for CLASSIC and FLOW_V1
-- without embedding member payloads.

COMMENT ON COLUMN adm_home_experiences.composition_policy IS
    'Tenant Home Composition policy. Readers accept v1-v4; activation-gated canonical writes use v4 modeLayouts. Member layouts remain in usr_home_views.';

-- Existing rows remain the single Classic projection while pre-Wave1 binaries may still be
-- serving traffic. The activation coordinator clones eligible workspace views only after the
-- fleet interlock opens, so legacy unscoped readers continue to see the same row/default count.
ALTER TABLE usr_home_views
    ADD COLUMN IF NOT EXISTS mode_key VARCHAR(16) NOT NULL DEFAULT 'CLASSIC';

ALTER TABLE usr_home_views
    ADD COLUMN IF NOT EXISTS legacy_source_view_id UUID;

-- Old binaries omit this column, so TRUE captures every view written before the fleet is fully
-- activated. Wave1 writers explicitly persist FALSE. The activation transaction clears the
-- marker after it has resolved the legacy view into independent mode namespaces.
ALTER TABLE usr_home_views
    ADD COLUMN IF NOT EXISTS legacy_unscoped BOOLEAN NOT NULL DEFAULT TRUE;

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

CREATE UNIQUE INDEX IF NOT EXISTS uk_usr_home_views_legacy_source
    ON usr_home_views (legacy_source_view_id)
    WHERE legacy_source_view_id IS NOT NULL;

-- Keep legacy device values writable while pre-Wave1 pods are serving. The fleet-safe activation
-- transaction deduplicates and rewrites them before exposing FOUR_DEVICE_LAYOUTS; Wave1 writers
-- always persist a canonical value.
ALTER TABLE usr_home_view_device_layouts
    DROP CONSTRAINT IF EXISTS ck_usr_home_view_device_class;

ALTER TABLE usr_home_view_device_layouts
    ADD CONSTRAINT ck_usr_home_view_device_class
        CHECK (device_class IN (
            'DESKTOP', 'MOBILE',
            'DESKTOP_WIDE', 'DESKTOP_STANDARD', 'MOBILE_STANDARD', 'MOBILE_COMPACT'));

COMMENT ON COLUMN usr_home_views.mode_key IS
    'Immutable Home experience namespace. CLASSIC and FLOW_V1 defaults and layouts are independent.';
COMMENT ON COLUMN usr_home_views.legacy_source_view_id IS
    'Classic source cloned transactionally during fleet-safe Home Composition v4 activation.';
COMMENT ON COLUMN usr_home_views.legacy_unscoped IS
    'TRUE only for a view created by a mode-unaware binary before fleet-safe v4 activation.';
COMMENT ON COLUMN usr_home_view_device_layouts.device_class IS
    'Responsive overlay. DESKTOP/MOBILE remain mixed-fleet aliases until v4 activation canonicalizes stored rows.';
