ALTER TABLE apr_policy_rules
    ADD COLUMN pending_enforcement_mode VARCHAR(20),
    ADD COLUMN pending_severity VARCHAR(20),
    ADD COLUMN pending_lifecycle_state VARCHAR(20),
    ADD COLUMN pending_rule_payload JSONB,
    ADD COLUMN pending_change_reason VARCHAR(1000),
    ADD COLUMN pending_by BIGINT,
    ADD COLUMN pending_at TIMESTAMPTZ;

ALTER TABLE apr_policy_rules
    ADD CONSTRAINT ck_apr_policy_pending_mode CHECK (
        pending_enforcement_mode IS NULL
        OR pending_enforcement_mode IN ('BLOCK', 'WARN', 'MONITOR')),
    ADD CONSTRAINT ck_apr_policy_pending_severity CHECK (
        pending_severity IS NULL
        OR pending_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    ADD CONSTRAINT ck_apr_policy_pending_state CHECK (
        pending_lifecycle_state IS NULL
        OR pending_lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED')),
    ADD CONSTRAINT ck_apr_policy_pending_payload CHECK (
        pending_rule_payload IS NULL OR jsonb_typeof(pending_rule_payload) = 'object'),
    ADD CONSTRAINT ck_apr_policy_pending_complete CHECK (
        (pending_by IS NULL AND pending_at IS NULL
            AND pending_enforcement_mode IS NULL AND pending_severity IS NULL
            AND pending_lifecycle_state IS NULL AND pending_rule_payload IS NULL
            AND pending_change_reason IS NULL)
        OR
        (pending_by IS NOT NULL AND pending_at IS NOT NULL
            AND pending_enforcement_mode IS NOT NULL AND pending_severity IS NOT NULL
            AND pending_lifecycle_state IS NOT NULL AND pending_rule_payload IS NOT NULL
            AND LENGTH(BTRIM(pending_change_reason)) >= 10));

CREATE INDEX idx_apr_policy_pending_review
    ON apr_policy_rules (tenant_id, pending_at, policy_key)
    WHERE pending_by IS NOT NULL;

COMMENT ON COLUMN apr_policy_rules.pending_by IS
    'Maker identity; the same user is prohibited from publishing this policy change.';
