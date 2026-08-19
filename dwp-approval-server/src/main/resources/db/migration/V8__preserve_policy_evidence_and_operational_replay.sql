CREATE TABLE apr_policy_rule_versions (
    policy_version_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    policy_id UUID NOT NULL REFERENCES apr_policy_rules(policy_id),
    version_number INTEGER NOT NULL,
    enforcement_mode VARCHAR(20) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    rule_payload JSONB NOT NULL,
    change_reason VARCHAR(1000) NOT NULL,
    submitted_by BIGINT,
    submitted_at TIMESTAMPTZ,
    published_by BIGINT,
    published_at TIMESTAMPTZ NOT NULL,
    review_comment VARCHAR(1000) NOT NULL,
    CONSTRAINT uk_apr_policy_rule_version
        UNIQUE (tenant_id, policy_id, version_number),
    CONSTRAINT ck_apr_policy_version_mode
        CHECK (enforcement_mode IN ('BLOCK', 'WARN', 'MONITOR')),
    CONSTRAINT ck_apr_policy_version_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_apr_policy_version_state
        CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED')),
    CONSTRAINT ck_apr_policy_version_payload
        CHECK (jsonb_typeof(rule_payload) = 'object')
);

CREATE INDEX idx_apr_policy_version_timeline
    ON apr_policy_rule_versions (tenant_id, policy_id, version_number DESC);

INSERT INTO apr_policy_rule_versions (
    policy_version_id, tenant_id, policy_id, version_number,
    enforcement_mode, severity, lifecycle_state, rule_payload,
    change_reason, submitted_by, submitted_at,
    published_by, published_at, review_comment)
SELECT gen_random_uuid(), policy.tenant_id, policy.policy_id, 1,
       policy.enforcement_mode, policy.severity, policy.lifecycle_state,
       policy.rule_payload, 'Initial governed policy baseline',
       policy.created_by, policy.created_at,
       policy.updated_by, policy.updated_at, 'Baseline captured during policy governance upgrade'
  FROM apr_policy_rules policy
ON CONFLICT (tenant_id, policy_id, version_number) DO NOTHING;

ALTER TABLE apr_integration_outbox
    ADD COLUMN manual_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_retried_at TIMESTAMPTZ,
    ADD COLUMN last_retried_by BIGINT,
    ADD CONSTRAINT ck_apr_integration_manual_retries
        CHECK (manual_retry_count >= 0);

COMMENT ON TABLE apr_policy_rule_versions IS
    'Immutable maker-checker publication evidence for approval policy changes.';
COMMENT ON COLUMN apr_integration_outbox.manual_retry_count IS
    'Number of explicitly authorized operator replays, separate from relay delivery attempts.';
