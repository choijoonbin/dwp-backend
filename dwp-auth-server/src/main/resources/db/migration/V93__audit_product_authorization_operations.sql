-- Production/shared product-authorization lifecycle operations are explicit,
-- independently authenticated control-plane decisions. The active-pointer event
-- remains the atomic runtime lineage; this immutable ledger adds the approval,
-- change-control and caller evidence required to operate that lineage safely.

ALTER TABLE auth_product_authorization_bundle
    ADD CONSTRAINT uk_product_authorization_bundle_audit_identity
        UNIQUE (bundle_id, bundle_key, version, checksum);

CREATE TABLE auth_product_authorization_governance_event (
    governance_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bundle_key VARCHAR(80) NOT NULL,
    bundle_id UUID NOT NULL,
    version BIGINT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    operation VARCHAR(20) NOT NULL,
    expected_revision BIGINT,
    resulting_revision BIGINT,
    requester_ref VARCHAR(160) NOT NULL,
    decision_actor_ref VARCHAR(160) NOT NULL,
    change_ref VARCHAR(160) NOT NULL,
    reason VARCHAR(1000),
    caller_service_identity VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_authorization_governance_bundle
        FOREIGN KEY (bundle_id, bundle_key, version, checksum)
        REFERENCES auth_product_authorization_bundle(
            bundle_id, bundle_key, version, checksum),
    CONSTRAINT uk_product_authorization_governance_change
        UNIQUE (bundle_key, bundle_id, operation, change_ref),
    CONSTRAINT ck_product_authorization_governance_version CHECK (version > 0),
    CONSTRAINT ck_product_authorization_governance_checksum
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_product_authorization_governance_operation
        CHECK (operation IN ('APPROVE', 'ACTIVATE', 'ROLLBACK')),
    CONSTRAINT ck_product_authorization_governance_revision
        CHECK ((operation = 'APPROVE'
                    AND expected_revision IS NULL
                    AND resulting_revision IS NULL)
            OR (operation IN ('ACTIVATE', 'ROLLBACK')
                    AND expected_revision IS NOT NULL
                    AND expected_revision >= 0
                    AND resulting_revision = expected_revision + 1)),
    CONSTRAINT ck_product_authorization_governance_separation
        CHECK (lower(requester_ref) <> lower(decision_actor_ref)),
    CONSTRAINT ck_product_authorization_governance_refs
        CHECK (length(trim(requester_ref)) > 0
            AND length(trim(decision_actor_ref)) > 0
            AND length(trim(change_ref)) >= 3
            AND requester_ref ~ '^[A-Za-z0-9][A-Za-z0-9@._:/+-]{0,159}$'
            AND decision_actor_ref ~ '^[A-Za-z0-9][A-Za-z0-9@._:/+-]{0,159}$'
            AND change_ref ~ '^[A-Za-z0-9][A-Za-z0-9@._:/+-]{2,159}$'),
    CONSTRAINT ck_product_authorization_governance_reason
        CHECK ((operation = 'ROLLBACK'
                    AND reason IS NOT NULL
                    AND length(trim(reason)) >= 10)
            OR (operation <> 'ROLLBACK' AND reason IS NULL)),
    CONSTRAINT ck_product_authorization_governance_caller
        CHECK ((operation = 'APPROVE'
                    AND caller_service_identity = 'dwp-provider-server')
            OR (operation IN ('ACTIVATE', 'ROLLBACK')
                    AND caller_service_identity = 'dwp-platform-server'))
);

CREATE INDEX ix_product_authorization_governance_event
    ON auth_product_authorization_governance_event(bundle_key, occurred_at DESC);

CREATE TRIGGER trg_product_authorization_governance_event_immutable
    BEFORE UPDATE OR DELETE ON auth_product_authorization_governance_event
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_authorization_descriptor_mutation();
