-- Internal tenant-settings control plane for authentication policy changes.
-- External IdP connectivity and population verification remain outside this owner.

CREATE TABLE com_tenant_setting_change_sets (
    change_set_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    owner_type VARCHAR(40) NOT NULL,
    owner_ref VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    before_state JSONB NOT NULL,
    proposed_state JSONB NOT NULL,
    before_hash CHAR(64) NOT NULL,
    proposed_hash CHAR(64) NOT NULL,
    impact_confidence VARCHAR(16) NOT NULL,
    impact_count BIGINT,
    impact_coverage VARCHAR(80) NOT NULL,
    impact_observed_at TIMESTAMPTZ NOT NULL,
    impact_exclusions JSONB NOT NULL DEFAULT '[]'::jsonb,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    submitted_at TIMESTAMPTZ,
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    published_by BIGINT,
    published_at TIMESTAMPTZ,
    publish_receipt_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_tenant_setting_change_owner
        CHECK (owner_type IN ('AUTH_POLICY')),
    CONSTRAINT ck_tenant_setting_change_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_tenant_setting_change_hashes
        CHECK (before_hash ~ '^[0-9a-f]{64}$' AND proposed_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_setting_change_impact
        CHECK (
            impact_confidence IN ('UNKNOWN', 'ESTIMATED', 'EXACT')
            AND (impact_count IS NULL OR impact_count >= 0)
            AND (impact_confidence <> 'UNKNOWN' OR impact_count IS NULL)),
    CONSTRAINT ck_tenant_setting_change_justification
        CHECK (length(btrim(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_tenant_setting_change_decision
        CHECK (
            (lifecycle_state IN ('APPROVED', 'REJECTED', 'PUBLISHED')
                AND decided_by IS NOT NULL AND decided_at IS NOT NULL
                AND decided_by <> requested_by)
            OR lifecycle_state NOT IN ('APPROVED', 'REJECTED', 'PUBLISHED')),
    CONSTRAINT ck_tenant_setting_change_publish
        CHECK (
            (lifecycle_state = 'PUBLISHED'
                AND published_by IS NOT NULL AND published_at IS NOT NULL
                AND publish_receipt_id IS NOT NULL)
            OR lifecycle_state <> 'PUBLISHED'),
    CONSTRAINT ck_tenant_setting_change_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_tenant_setting_change_open
    ON com_tenant_setting_change_sets (tenant_id, owner_type, owner_ref)
    WHERE lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');
CREATE INDEX idx_tenant_setting_change_queue
    ON com_tenant_setting_change_sets (tenant_id, lifecycle_state, updated_at DESC);

COMMENT ON TABLE com_tenant_setting_change_sets IS
    'Versioned tenant setting change envelope. Each owner must revalidate and publish its own state.';
COMMENT ON COLUMN com_tenant_setting_change_sets.impact_confidence IS
    'UNKNOWN, ESTIMATED, or EXACT; never infer an external population from the internal directory.';

ALTER TABLE com_emergency_access_principals
    ADD COLUMN verification_status VARCHAR(24) NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN verification_method VARCHAR(32),
    ADD COLUMN verification_reference VARCHAR(500),
    ADD COLUMN last_verified_at TIMESTAMPTZ,
    ADD COLUMN last_verified_by BIGINT,
    ADD COLUMN verification_due_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_emergency_principal_verification_status
        CHECK (verification_status IN ('NOT_VERIFIED', 'VERIFIED', 'OVERDUE')),
    ADD CONSTRAINT ck_emergency_principal_verification_method
        CHECK (verification_method IS NULL OR verification_method IN (
            'OPERATOR_ATTESTED', 'RECOVERY_DRILL_COMPLETED')),
    ADD CONSTRAINT ck_emergency_principal_verification_evidence
        CHECK (
            (verification_status = 'NOT_VERIFIED'
                AND verification_method IS NULL AND last_verified_at IS NULL
                AND last_verified_by IS NULL)
            OR (verification_status IN ('VERIFIED', 'OVERDUE')
                AND verification_method IS NOT NULL
                AND verification_reference IS NOT NULL
                AND last_verified_at IS NOT NULL
                AND last_verified_by IS NOT NULL
                AND verification_due_at IS NOT NULL
                AND verification_due_at > last_verified_at));

CREATE INDEX idx_emergency_principal_verification
    ON com_emergency_access_principals (
        tenant_id, verification_status, verification_due_at, review_due_at);

COMMENT ON COLUMN com_emergency_access_principals.verification_method IS
    'Internal operator attestation or completed recovery drill; not proof of an external IdP login.';
