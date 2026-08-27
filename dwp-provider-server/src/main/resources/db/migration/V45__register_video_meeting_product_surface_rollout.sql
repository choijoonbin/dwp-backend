-- Enterprise video meetings join the governed rollout inventory without enabling
-- exact Product Surface capability enforcement. The immutable v1-v3 authority
-- bundles remain unchanged until a separately approved meetings contract exists.

INSERT INTO prv_feature_flags (
    feature_flag_id, feature_key, display_name, description, owner_service,
    value_type, default_value, configuration_schema, risk_tier,
    lifecycle_state, created_by, updated_by)
VALUES
    ('6d63f117-cf23-4b26-961a-b15a5847b212',
     'access.product-surfaces.capability-enforcement.meetings.v1',
     'Meetings product capability enforcement',
     'Enforces an approved exact capability and policy contract for Meetings.',
     'identity-platform', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L3', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a114',
     'ux.product-surfaces.meetings.v1',
     'Meetings separated surfaces',
     'Enables the separated Work and Management shell for Meetings.',
     'meetings', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0)
ON CONFLICT (feature_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    owner_service = EXCLUDED.owner_service,
    value_type = EXCLUDED.value_type,
    default_value = EXCLUDED.default_value,
    configuration_schema = EXCLUDED.configuration_schema,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_feature_rollout_decision_revision (feature_flag_id, opaque_revision)
SELECT feature_flag_id, 0
  FROM prv_feature_flags
 WHERE feature_key IN (
    'access.product-surfaces.capability-enforcement.meetings.v1',
    'ux.product-surfaces.meetings.v1')
ON CONFLICT (feature_flag_id) DO NOTHING;
