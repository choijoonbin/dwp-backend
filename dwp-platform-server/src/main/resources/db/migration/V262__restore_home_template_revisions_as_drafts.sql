-- Wave 5 Home Studio restores historical template snapshots as a new draft.
-- Publication remains an explicit, separately authorized transition.
ALTER TABLE adm_home_template_revisions
    DROP CONSTRAINT IF EXISTS ck_adm_home_template_revision_source;

ALTER TABLE adm_home_template_revisions
    ADD CONSTRAINT ck_adm_home_template_revision_source
        CHECK (source IN ('CREATE', 'UPDATE', 'PUBLISH', 'REVOKE', 'RESTORE'));

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT 'PLATFORM.ADM_HOME_TEMPLATE_REVISIONS.SOURCE',
       'RESTORE', 'RESTORE', '{"ko":"RESTORE","en":"RESTORE"}'::jsonb,
       '{}'::jsonb, 50, TRUE, 'ACTIVE'
 WHERE EXISTS (
       SELECT 1
         FROM sys_code_sets
        WHERE code_set_key = 'PLATFORM.ADM_HOME_TEMPLATE_REVISIONS.SOURCE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';
