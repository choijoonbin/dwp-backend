INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
VALUES (
    'PLATFORM.ANNOUNCEMENT_AUDIENCE', 'dwp-platform-server',
    'DATABASE_COLUMN', 'adm_announcements.audience_type', 'CHECK', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PROVIDER.SUPPORT_REQUEST.LIFECYCLE_STATE'
   AND code = 'PENDING'
   AND lifecycle_state <> 'RETIRED';

COMMENT ON COLUMN adm_announcements.audience_type IS
    'Governed publication audience bound to PLATFORM.ANNOUNCEMENT_AUDIENCE.';
