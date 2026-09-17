-- MZ / AI Stage is a first-class Home mode. It owns an independent saved-view namespace and
-- does not reuse the FLOW_V1 key. Existing CLASSIC and FLOW_V1 rows remain untouched.
ALTER TABLE usr_home_views
    DROP CONSTRAINT IF EXISTS ck_usr_home_views_mode;

ALTER TABLE usr_home_views
    ADD CONSTRAINT ck_usr_home_views_mode
        CHECK (mode_key IN ('CLASSIC', 'FLOW_V1', 'MZ_V1'));

ALTER TABLE usr_home_preferences
    ADD COLUMN IF NOT EXISTS current_mode VARCHAR(16);

ALTER TABLE usr_home_preferences
    DROP CONSTRAINT IF EXISTS ck_usr_home_preferences_current_mode;

ALTER TABLE usr_home_preferences
    ADD CONSTRAINT ck_usr_home_preferences_current_mode
        CHECK (current_mode IS NULL OR current_mode IN ('CLASSIC', 'FLOW_V1', 'MZ_V1'));

ALTER TABLE plt_widget_runtime_controls
    DROP CONSTRAINT IF EXISTS ck_widget_control_mode;

ALTER TABLE plt_widget_runtime_controls
    ADD CONSTRAINT ck_widget_control_mode CHECK (
        target_type <> 'MODE' OR target_id IN ('CLASSIC', 'FLOW_V1', 'MZ_V1'));

COMMENT ON COLUMN usr_home_views.mode_key IS
    'Immutable Home experience namespace. CLASSIC, FLOW_V1, and MZ_V1 defaults and layouts are independent.';

COMMENT ON COLUMN usr_home_preferences.current_mode IS
    'Optional personal current Home mode. NULL inherits the tenant default; this never stores a theme or preset.';

COMMENT ON COLUMN adm_home_experiences.composition_policy IS
    'Tenant Home Composition policy. Canonical v4 modeLayouts cover independent CLASSIC, FLOW_V1, and MZ_V1 views; member layouts remain in usr_home_views.';
