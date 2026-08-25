-- V171-V173 were exercised in development before the Phase 2 release. Keep
-- those applied migrations immutable and converge both upgraded and fresh
-- databases here. No legacy preference row is changed or removed.

-- The first development schema used timestamp-without-time-zone for the new
-- entity audit columns while JDBC/JVM and PostgreSQL ran in different zones.
-- Their mixed wall clocks cannot be converted losslessly, so pre-release rows
-- receive one trustworthy migration instant. Future writes use TIMESTAMPTZ and
-- the UTC HomePersonalizationEntity clock.
DO $$
DECLARE
    target_table TEXT;
BEGIN
    FOREACH target_table IN ARRAY ARRAY[
        'usr_home_views',
        'usr_home_view_device_layouts',
        'usr_home_widget_configurations',
        'adm_home_templates',
        'usr_home_composer_proposals'
    ] LOOP
        IF EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = target_table
               AND column_name = 'created_at'
               AND data_type = 'timestamp without time zone'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I '
                || 'ALTER COLUMN created_at TYPE TIMESTAMPTZ USING CURRENT_TIMESTAMP, '
                || 'ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING CURRENT_TIMESTAMP',
                target_table);
        END IF;
    END LOOP;
END $$;

DROP INDEX IF EXISTS uk_usr_home_views_default;
CREATE UNIQUE INDEX uk_usr_home_views_default
    ON usr_home_views (tenant_id, user_id, surface_key)
    WHERE is_default AND deleted_at IS NULL;

ALTER TABLE usr_home_views
    ADD COLUMN IF NOT EXISTS integrity_state VARCHAR(24) NOT NULL DEFAULT 'VALID';
ALTER TABLE usr_home_views
    DROP CONSTRAINT IF EXISTS ck_usr_home_view_integrity_state,
    DROP CONSTRAINT IF EXISTS ck_usr_home_view_layout_size;
ALTER TABLE usr_home_views
    ADD CONSTRAINT ck_usr_home_view_integrity_state
        CHECK (integrity_state IN ('VALID', 'RECOVERY_REQUIRED')),
    ADD CONSTRAINT ck_usr_home_view_layout_size
        CHECK (octet_length(layout_payload::text) <= 98304) NOT VALID;

UPDATE usr_home_views
   SET integrity_state = 'RECOVERY_REQUIRED'
 WHERE jsonb_typeof(layout_payload) IS DISTINCT FROM 'object'
    OR octet_length(layout_payload::text) > 98304
    OR jsonb_typeof(layout_payload -> 'widgets') IS DISTINCT FROM 'array';

ALTER TABLE usr_home_view_revisions
    ADD COLUMN IF NOT EXISTS restorable BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE usr_home_view_revisions
    DROP CONSTRAINT IF EXISTS ck_usr_home_view_revision_snapshot_size;
ALTER TABLE usr_home_view_revisions
    ADD CONSTRAINT ck_usr_home_view_revision_snapshot_size
        CHECK (octet_length(snapshot::text) <= 393216) NOT VALID;

UPDATE usr_home_view_revisions
   SET restorable = FALSE
 WHERE jsonb_typeof(snapshot) IS DISTINCT FROM 'object'
    OR octet_length(snapshot::text) > 393216
    OR (NOT (snapshot ? 'snapshotVersion')
        AND (octet_length(snapshot::text) > 98304
             OR jsonb_typeof(snapshot -> 'widgets') IS DISTINCT FROM 'array'));

ALTER TABLE usr_home_view_device_layouts
    DROP CONSTRAINT IF EXISTS ck_usr_home_device_overlay_size;
ALTER TABLE usr_home_view_device_layouts
    ADD CONSTRAINT ck_usr_home_device_overlay_size
        CHECK (octet_length(overlay_payload::text) <= 16384) NOT VALID;

ALTER TABLE usr_home_widget_configurations
    DROP CONSTRAINT IF EXISTS ck_usr_home_widget_configuration_size;
ALTER TABLE usr_home_widget_configurations
    ADD CONSTRAINT ck_usr_home_widget_configuration_size
        CHECK (octet_length(configuration_payload::text) <= 4096) NOT VALID;

ALTER TABLE adm_home_templates
    DROP CONSTRAINT IF EXISTS ck_adm_home_template_layout_size;
ALTER TABLE adm_home_templates
    ADD CONSTRAINT ck_adm_home_template_layout_size
        CHECK (octet_length(layout_payload::text) <= 98304) NOT VALID;

ALTER TABLE adm_home_template_revisions
    DROP CONSTRAINT IF EXISTS ck_adm_home_template_revision_snapshot_size;
ALTER TABLE adm_home_template_revisions
    ADD CONSTRAINT ck_adm_home_template_revision_snapshot_size
        CHECK (octet_length(snapshot::text) <= 131072) NOT VALID;

ALTER TABLE usr_home_composer_proposals
    DROP CONSTRAINT IF EXISTS ck_usr_home_composer_before_size,
    DROP CONSTRAINT IF EXISTS ck_usr_home_composer_proposed_size,
    DROP CONSTRAINT IF EXISTS ck_usr_home_composer_changes_size;
ALTER TABLE usr_home_composer_proposals
    ADD CONSTRAINT ck_usr_home_composer_before_size
        CHECK (octet_length(before_layout::text) <= 98304) NOT VALID,
    ADD CONSTRAINT ck_usr_home_composer_proposed_size
        CHECK (octet_length(proposed_layout::text) <= 98304) NOT VALID,
    ADD CONSTRAINT ck_usr_home_composer_changes_size
        CHECK (octet_length(changes_payload::text) <= 65536) NOT VALID;

ALTER TABLE usr_home_command_receipts
    DROP CONSTRAINT IF EXISTS ck_usr_home_command_receipt_response_size;
ALTER TABLE usr_home_command_receipts
    ADD CONSTRAINT ck_usr_home_command_receipt_response_size
        CHECK (octet_length(response_payload::text) <= 393216) NOT VALID;
