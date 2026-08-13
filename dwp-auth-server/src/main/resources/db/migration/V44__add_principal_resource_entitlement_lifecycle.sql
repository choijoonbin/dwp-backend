-- Runtime resource entitlements remain owned by Auth even when the business
-- approval and fulfilment workflow is orchestrated by another service.
ALTER TABLE sys_identity_audit_events
    ALTER COLUMN actor_id DROP NOT NULL,
    ADD COLUMN actor_type VARCHAR(20) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_sys_identity_audit_actor_type
        CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    ADD CONSTRAINT ck_sys_identity_audit_actor_identity
        CHECK ((actor_type = 'USER' AND actor_id IS NOT NULL)
            OR (actor_type IN ('SERVICE', 'SYSTEM') AND actor_id IS NULL));

CREATE TABLE com_principal_resource_grants (
    principal_resource_grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(160) NOT NULL,
    resource_id BIGINT NOT NULL REFERENCES com_resources(resource_id),
    permission_id BIGINT NOT NULL REFERENCES com_permissions(permission_id),
    effect VARCHAR(10) NOT NULL DEFAULT 'ALLOW',
    source_type VARCHAR(40) NOT NULL,
    source_ref VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to TIMESTAMPTZ,
    justification VARCHAR(1000) NOT NULL,
    granted_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT,
    revocation_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_principal_resource_grant_source
        UNIQUE (tenant_id, source_type, source_ref),
    CONSTRAINT fk_principal_resource_grant_granted_by
        FOREIGN KEY (tenant_id, granted_by) REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT fk_principal_resource_grant_revoked_by
        FOREIGN KEY (tenant_id, revoked_by) REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT ck_principal_resource_grant_principal
        CHECK (principal_type IN ('USER', 'GROUP') AND length(btrim(principal_ref)) > 0),
    CONSTRAINT ck_principal_resource_grant_effect CHECK (effect = 'ALLOW'),
    CONSTRAINT ck_principal_resource_grant_source
        CHECK (source_type IN ('APP_ACCESS_REQUEST', 'ADMIN_DIRECT', 'ACCESS_PACKAGE')),
    CONSTRAINT ck_principal_resource_grant_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_principal_resource_grant_window
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_principal_resource_grant_justification
        CHECK (length(btrim(justification)) >= 10),
    CONSTRAINT ck_principal_resource_grant_revocation
        CHECK (
            (lifecycle_state = 'REVOKED'
                AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
            OR (lifecycle_state IN ('ACTIVE', 'EXPIRED') AND revoked_at IS NULL)
        )
);

CREATE UNIQUE INDEX uk_principal_resource_grant_active
    ON com_principal_resource_grants (
        tenant_id, principal_type, principal_ref, resource_id, permission_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_principal_resource_grant_effective_user
    ON com_principal_resource_grants (
        tenant_id, principal_type, principal_ref, lifecycle_state, valid_to);

CREATE INDEX idx_principal_resource_grant_expiry
    ON com_principal_resource_grants (valid_to, principal_resource_grant_id)
    WHERE lifecycle_state = 'ACTIVE' AND valid_to IS NOT NULL;

COMMENT ON TABLE com_principal_resource_grants IS
    'Auth-owned runtime entitlements issued from independently governed workflows.';
COMMENT ON COLUMN com_principal_resource_grants.source_ref IS
    'Idempotency and evidence reference supplied by the authoritative workflow.';
