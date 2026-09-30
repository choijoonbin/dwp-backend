-- V58 established the internal source-of-record tables. This migration adds the
-- least-privilege authority and immutable review evidence needed to operate them.

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('RESOURCE_GOVERNANCE_READ', 'Read internal resource commitments', 'L2',
     'Inspect provider-owned capacity, budget commitments, and immutable internal ledger evidence'),
    ('RESOURCE_GOVERNANCE_WRITE', 'Write internal resource commitments', 'L3',
     'Set provider-owned resource commitments and append internal allocation or budget evidence'),
    ('ARTIFACT_GOVERNANCE_READ', 'Read artifact rollout governance', 'L2',
     'Inspect artifact declarations, compatibility evidence, and rollout plans'),
    ('ARTIFACT_GOVERNANCE_WRITE', 'Write artifact rollout governance', 'L3',
     'Register artifacts, record compatibility evidence, and prepare governed rollout plans'),
    ('ARTIFACT_GOVERNANCE_APPROVE', 'Approve artifact rollout governance', 'L3',
     'Independently review artifact declarations and rollout plans')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'RESOURCE_GOVERNANCE_READ'),
    ('PROVIDER_ADMIN', 'RESOURCE_GOVERNANCE_WRITE'),
    ('PROVIDER_ADMIN', 'ARTIFACT_GOVERNANCE_READ'),
    ('PROVIDER_ADMIN', 'ARTIFACT_GOVERNANCE_WRITE'),
    ('PROVIDER_ADMIN', 'ARTIFACT_GOVERNANCE_APPROVE'),
    ('PROVIDER_OPERATOR', 'RESOURCE_GOVERNANCE_READ'),
    ('PROVIDER_OPERATOR', 'RESOURCE_GOVERNANCE_WRITE'),
    ('PROVIDER_OPERATOR', 'ARTIFACT_GOVERNANCE_READ'),
    ('PROVIDER_OPERATOR', 'ARTIFACT_GOVERNANCE_WRITE'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'RESOURCE_GOVERNANCE_READ'),
    ('PROVIDER_ENTITLEMENT_ADMIN', 'RESOURCE_GOVERNANCE_WRITE'),
    ('PROVIDER_CHANGE_APPROVER', 'ARTIFACT_GOVERNANCE_READ'),
    ('PROVIDER_CHANGE_APPROVER', 'ARTIFACT_GOVERNANCE_APPROVE'),
    ('PROVIDER_RELEASE_APPROVER', 'ARTIFACT_GOVERNANCE_READ'),
    ('PROVIDER_RELEASE_APPROVER', 'ARTIFACT_GOVERNANCE_APPROVE'),
    ('PROVIDER_AUDITOR', 'RESOURCE_GOVERNANCE_READ'),
    ('PROVIDER_AUDITOR', 'ARTIFACT_GOVERNANCE_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

CREATE TABLE prv_product_artifact_reviews (
    review_id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES prv_product_artifact_manifests(artifact_id),
    decision VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    evidence JSONB NOT NULL,
    reviewed_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_product_artifact_review_decision
        CHECK (decision IN ('APPROVED', 'RETURNED'))
);

CREATE INDEX idx_prv_product_artifact_reviews_artifact
    ON prv_product_artifact_reviews(artifact_id, reviewed_at DESC);

CREATE OR REPLACE FUNCTION prv_reject_product_artifact_review_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Product artifact review evidence is immutable';
END;
$$;

CREATE TRIGGER trg_prv_product_artifact_reviews_immutable
BEFORE UPDATE OR DELETE ON prv_product_artifact_reviews
FOR EACH ROW EXECUTE FUNCTION prv_reject_product_artifact_review_mutation();

COMMENT ON TABLE prv_product_artifact_reviews IS
    'Immutable independent review evidence for provider-owned artifact declarations. '
    'Registry signing, package distribution, and deployment execution remain unavailable.';
