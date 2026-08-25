-- CORE-006 rollout controls. Only 000, 100, 110 and 111 are valid at the
-- composition layer; every individual flag remains off until an approved revision.

INSERT INTO prv_feature_flags (
    feature_flag_id, feature_key, display_name, description, owner_service,
    value_type, default_value, configuration_schema, risk_tier,
    lifecycle_state, created_by, updated_by)
VALUES
    ('6d63f117-cf23-4b26-961a-b15a5847a101',
     'access.product-surfaces.context-shadow.v1',
     'Product surface context shadow',
     'Computes the v2 context and difference without changing enforcement.',
     'identity-platform', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a102',
     'access.product-surfaces.capability-enforcement.v1',
     'Product surface capability enforcement',
     'Enforces approved exact capability and policy decisions.',
     'identity-platform', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L3', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a103',
     'ux.product-surfaces.communications.v1',
     'Communications separated surfaces',
     'Enables the separated Work and Management shell for Communications.',
     'shared-experience', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a104',
     'ux.product-surfaces.services.v1',
     'Services separated surfaces',
     'Enables the separated Work and Management shell for Services.',
     'shared-experience', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a105',
     'ux.product-surfaces.approvals.v1',
     'Approvals separated surfaces',
     'Enables the separated Work and Management shell for Approvals.',
     'approvals', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L2', 'ACTIVE', 0, 0),
    ('6d63f117-cf23-4b26-961a-b15a5847a106',
     'ux.product-surfaces.hcm.v1',
     'HCM separated surfaces',
     'Enables the separated Work and Management shell for HCM.',
     'hcm', 'BOOLEAN', 'false'::jsonb,
     '{"type":"boolean"}'::jsonb, 'L3', 'ACTIVE', 0, 0)
ON CONFLICT (feature_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    owner_service = EXCLUDED.owner_service,
    value_type = EXCLUDED.value_type,
    configuration_schema = EXCLUDED.configuration_schema,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

CREATE TABLE prv_feature_rollout_decision_revision (
    feature_flag_id UUID PRIMARY KEY REFERENCES prv_feature_flags(feature_flag_id),
    opaque_revision BIGINT NOT NULL DEFAULT 0 CHECK (opaque_revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO prv_feature_rollout_decision_revision (feature_flag_id, opaque_revision)
SELECT feature_flag_id, 0
  FROM prv_feature_flags
 WHERE feature_key IN (
    'access.product-surfaces.context-shadow.v1',
    'access.product-surfaces.capability-enforcement.v1',
    'ux.product-surfaces.communications.v1',
    'ux.product-surfaces.services.v1',
    'ux.product-surfaces.approvals.v1',
    'ux.product-surfaces.hcm.v1')
ON CONFLICT (feature_flag_id) DO NOTHING;

CREATE TABLE prv_feature_rollout_decision_outbox (
    event_id UUID PRIMARY KEY,
    auth_tenant_id BIGINT CHECK (auth_tenant_id IS NULL OR auth_tenant_id > 0),
    tenant_scope VARCHAR(12) NOT NULL CHECK (tenant_scope IN ('EXACT', 'ALL')),
    flag_key VARCHAR(160) NOT NULL,
    opaque_revision BIGINT NOT NULL CHECK (opaque_revision > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN (
        'ENABLED', 'DISABLED', 'PAUSED', 'RESUMED', 'ADVANCED', 'ROLLED_BACK')),
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (delivery_status IN (
        'PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(200),
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_prv_feature_rollout_decision_scope CHECK (
        (tenant_scope = 'ALL' AND auth_tenant_id IS NULL)
        OR (tenant_scope = 'EXACT' AND auth_tenant_id IS NOT NULL))
);

CREATE INDEX idx_prv_feature_rollout_decision_outbox_delivery
    ON prv_feature_rollout_decision_outbox (
        delivery_status, next_attempt_at, created_at)
    WHERE delivery_status IN ('PENDING', 'FAILED', 'SENDING');

COMMENT ON TABLE prv_feature_rollout_decision_outbox IS
    'At-least-once operational cache invalidation only; contains no UX or audit payload.';
