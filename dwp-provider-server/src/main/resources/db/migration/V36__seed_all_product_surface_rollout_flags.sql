-- CORE-006 W2/W3 product UX flags. These flags are deliberately default-off;
-- an approved provider rollout revision is still required for every tenant.

INSERT INTO prv_feature_flags (
    feature_flag_id, feature_key, display_name, description, owner_service,
    value_type, default_value, configuration_schema, risk_tier,
    lifecycle_state, created_by, updated_by)
VALUES
    ('6d63f117-cf23-4b26-961a-b15a5847a107',
     'ux.product-surfaces.dwaion.v1',
     'DWAI ON separated surfaces',
     'Enables the separated Work and Management shell for DWAI ON.',
     'dwaion', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a108',
     'ux.product-surfaces.notifications.v1',
     'Notifications separated surfaces',
     'Enables the separated Work and Management shell for Notifications.',
     'notifications', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a109',
     'ux.product-surfaces.calendar.v1',
     'Calendar separated surfaces',
     'Enables the separated Work and Management shell for Calendar.',
     'calendar', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a110',
     'ux.product-surfaces.workplace.v1',
     'Workplace separated surfaces',
     'Enables the separated Work and Management shell for Workplace and Rooms.',
     'workplace', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a111',
     'ux.product-surfaces.mail.v1',
     'Mail separated surfaces',
     'Enables the separated Work and Management shell for Mail.',
     'mail', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a112',
     'ux.product-surfaces.messaging.v1',
     'Messaging separated surfaces',
     'Enables the separated Work and Management shell for Messaging.',
     'messaging', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a113',
     'ux.product-surfaces.spaces.v1',
     'Spaces separated surfaces',
     'Enables the separated Work and Management shell for Spaces.',
     'spaces', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0)
ON CONFLICT (feature_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    owner_service = EXCLUDED.owner_service,
    value_type = EXCLUDED.value_type,
    default_value = EXCLUDED.default_value,
    configuration_schema = EXCLUDED.configuration_schema,
    risk_tier = EXCLUDED.risk_tier,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_feature_rollout_decision_revision (feature_flag_id, opaque_revision)
SELECT feature_flag_id, 0
  FROM prv_feature_flags
 WHERE feature_key IN (
    'ux.product-surfaces.dwaion.v1',
    'ux.product-surfaces.notifications.v1',
    'ux.product-surfaces.calendar.v1',
    'ux.product-surfaces.workplace.v1',
    'ux.product-surfaces.mail.v1',
    'ux.product-surfaces.messaging.v1',
    'ux.product-surfaces.spaces.v1')
ON CONFLICT (feature_flag_id) DO NOTHING;
