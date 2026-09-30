-- Typed tenant-setting owners and their versioned change workflow.  Publishing is
-- authoritative only after the registered owner adapter has applied the value.

CREATE TABLE sys_tenant_setting_owner_registry (
    owner_key VARCHAR(120) NOT NULL,
    owner_version BIGINT NOT NULL,
    setting_key VARCHAR(160) NOT NULL,
    owner_service VARCHAR(40) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    resolution_strategy VARCHAR(40) NOT NULL,
    override_policy VARCHAR(24) NOT NULL,
    activation_mode VARCHAR(20) NOT NULL,
    default_value JSONB NOT NULL,
    localized_label_key VARCHAR(200) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_key, owner_version),
    CONSTRAINT uk_tenant_setting_owner_version UNIQUE (setting_key, owner_version),
    CONSTRAINT ck_tenant_setting_owner_value_type CHECK (
        value_type IN ('STRING', 'BOOLEAN', 'INTEGER', 'OBJECT')),
    CONSTRAINT ck_tenant_setting_owner_resolution CHECK (
        resolution_strategy = 'TENANT_OVERRIDE_OR_OWNER_DEFAULT'),
    CONSTRAINT ck_tenant_setting_owner_override CHECK (
        override_policy IN ('TENANT_ALLOWED', 'OWNER_LOCKED')),
    CONSTRAINT ck_tenant_setting_owner_activation CHECK (activation_mode = 'PUBLISH'),
    CONSTRAINT ck_tenant_setting_owner_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_tenant_setting_owner_default CHECK (jsonb_typeof(default_value) IS NOT NULL)
);

CREATE UNIQUE INDEX uk_tenant_setting_owner_active
    ON sys_tenant_setting_owner_registry(setting_key)
    WHERE lifecycle_state = 'ACTIVE';

INSERT INTO sys_tenant_setting_owner_registry (
    owner_key, owner_version, setting_key, owner_service, value_type,
    resolution_strategy, override_policy, activation_mode, default_value,
    localized_label_key)
VALUES
    ('AUTH_TENANT_DIRECTORY', 1, 'identity.defaultLocale', 'auth', 'STRING',
     'TENANT_OVERRIDE_OR_OWNER_DEFAULT', 'TENANT_ALLOWED', 'PUBLISH',
     '"en"'::jsonb, 'managed.effective.settings.identity.defaultLocale.title'),
    ('AUTH_POLICY', 1, 'authentication.requireMfa', 'auth', 'BOOLEAN',
     'TENANT_OVERRIDE_OR_OWNER_DEFAULT', 'OWNER_LOCKED', 'PUBLISH',
     'false'::jsonb, 'managed.effective.settings.authentication.requireMfa.title'),
    ('AUTH_POLICY', 2, 'authentication.defaultLoginType', 'auth', 'STRING',
     'TENANT_OVERRIDE_OR_OWNER_DEFAULT', 'OWNER_LOCKED', 'PUBLISH',
     '"LOCAL"'::jsonb, 'managed.effective.settings.authentication.defaultLoginType.title'),
    ('AUTH_POLICY', 3, 'authentication.tokenTtlSec', 'auth', 'INTEGER',
     'TENANT_OVERRIDE_OR_OWNER_DEFAULT', 'OWNER_LOCKED', 'PUBLISH',
     '28800'::jsonb, 'managed.effective.settings.authentication.tokenTtlSec.title');

CREATE OR REPLACE FUNCTION dwp_reject_tenant_setting_owner_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'tenant setting owner descriptors are immutable; publish a new version';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_setting_owner_immutable
    BEFORE UPDATE OR DELETE ON sys_tenant_setting_owner_registry
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_setting_owner_mutation();

CREATE TABLE com_tenant_setting_override_changes (
    change_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    setting_key VARCHAR(160) NOT NULL,
    owner_key VARCHAR(120) NOT NULL,
    owner_version BIGINT NOT NULL,
    desired_state VARCHAR(16) NOT NULL,
    before_value JSONB NOT NULL,
    proposed_value JSONB,
    before_hash CHAR(64) NOT NULL,
    proposed_hash CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    impact_count BIGINT NOT NULL DEFAULT 0,
    impact_coverage VARCHAR(120) NOT NULL,
    impact_observed_at TIMESTAMPTZ NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    published_by BIGINT,
    published_at TIMESTAMPTZ,
    publish_receipt_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_tenant_setting_override_owner
        FOREIGN KEY (owner_key, owner_version)
        REFERENCES sys_tenant_setting_owner_registry(owner_key, owner_version),
    CONSTRAINT ck_tenant_setting_override_desired CHECK (desired_state IN ('VALUE', 'INHERIT')),
    CONSTRAINT ck_tenant_setting_override_value CHECK (
        (desired_state = 'VALUE' AND proposed_value IS NOT NULL)
        OR (desired_state = 'INHERIT' AND proposed_value IS NULL)),
    CONSTRAINT ck_tenant_setting_override_state CHECK (
        lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_tenant_setting_override_justification CHECK (
        length(BTRIM(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_tenant_setting_override_review CHECK (
        (lifecycle_state IN ('APPROVED', 'PUBLISHED')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by)
        OR lifecycle_state NOT IN ('APPROVED', 'PUBLISHED')),
    CONSTRAINT ck_tenant_setting_override_publish CHECK (
        (lifecycle_state = 'PUBLISHED'
            AND published_by IS NOT NULL AND published_at IS NOT NULL
            AND publish_receipt_id IS NOT NULL
            AND published_by <> requested_by AND published_by <> approved_by)
        OR lifecycle_state <> 'PUBLISHED'),
    CONSTRAINT ck_tenant_setting_override_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_tenant_setting_override_open
    ON com_tenant_setting_override_changes(tenant_id, setting_key)
    WHERE lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');
CREATE INDEX idx_tenant_setting_override_history
    ON com_tenant_setting_override_changes(tenant_id, setting_key, updated_at DESC);

CREATE TABLE com_tenant_setting_override_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    change_id UUID NOT NULL REFERENCES com_tenant_setting_override_changes(change_id),
    event_type VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    resulting_version BIGINT NOT NULL,
    receipt_id UUID,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (change_id, resulting_version, event_type),
    CONSTRAINT ck_tenant_setting_override_event_evidence CHECK (
        jsonb_typeof(evidence) = 'object')
);

CREATE OR REPLACE FUNCTION dwp_reject_tenant_setting_override_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'tenant setting override events are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_setting_override_event_immutable
    BEFORE UPDATE OR DELETE ON com_tenant_setting_override_events
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_setting_override_event_mutation();

COMMENT ON TABLE sys_tenant_setting_owner_registry IS
    'Immutable owner contract; application adapters must exist before an ACTIVE owner is publishable.';
COMMENT ON TABLE com_tenant_setting_override_changes IS
    'Versioned preview, independent approval, publication, and INHERIT restoration evidence.';
