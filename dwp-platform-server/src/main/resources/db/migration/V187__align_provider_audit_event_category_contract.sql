-- Align the global code registry with the closed Provider audit category CHECK
-- that was expanded by Provider V40. This is a forward-only correction: the
-- historical Platform V25 and Provider V40 migrations remain immutable.

UPDATE sys_code_sets
   SET lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PROVIDER.AUDIT_EVENT_CATEGORY'
   AND lifecycle_state <> 'ACTIVE';

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, predefined, lifecycle_state)
VALUES
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'FEATURE_ROLLOUT',
     'Feature rollout', '{"ko":"기능 롤아웃","en":"Feature rollout"}',
     70, '{}', TRUE, 'ACTIVE'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'COMMERCIAL_GOVERNANCE',
     'Commercial governance', '{"ko":"상업 거버넌스","en":"Commercial governance"}',
     80, '{}', TRUE, 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    predefined = TRUE,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- The provider database CHECK is a closed eight-value contract. Retire any
-- stale registry-only value instead of advertising a category the provider
-- database would reject.
UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PROVIDER.AUDIT_EVENT_CATEGORY'
   AND code NOT IN (
       'ADMINISTRATION',
       'PRIVILEGED_ACCESS',
       'SERVICE_HEALTH',
       'CHANGE_MANAGEMENT',
       'TENANT_LIFECYCLE',
       'DATA_GOVERNANCE',
       'FEATURE_ROLLOUT',
       'COMMERCIAL_GOVERNANCE')
   AND lifecycle_state <> 'RETIRED';
