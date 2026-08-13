CREATE TABLE com_privileged_access_policies (
    privileged_access_policy_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    role_id BIGINT NOT NULL,
    activation_mode VARCHAR(24) NOT NULL DEFAULT 'APPROVAL',
    maximum_duration_minutes INTEGER NOT NULL DEFAULT 120,
    assurance_level VARCHAR(20) NOT NULL DEFAULT 'MFA',
    approval_quorum SMALLINT NOT NULL DEFAULT 1,
    emergency_mode VARCHAR(24) NOT NULL DEFAULT 'DISABLED',
    ticket_required BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_privileged_access_policy_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT uk_privileged_access_policy_role UNIQUE (tenant_id, role_id),
    CONSTRAINT ck_privileged_access_policy_mode
        CHECK (activation_mode IN ('SELF_SERVICE', 'APPROVAL', 'DISABLED')),
    CONSTRAINT ck_privileged_access_policy_duration
        CHECK (maximum_duration_minutes BETWEEN 15 AND 480),
    CONSTRAINT ck_privileged_access_policy_assurance
        CHECK (assurance_level IN ('SESSION', 'MFA', 'PHISHING_RESISTANT')),
    CONSTRAINT ck_privileged_access_policy_quorum
        CHECK (approval_quorum BETWEEN 1 AND 2),
    CONSTRAINT ck_privileged_access_policy_emergency
        CHECK (emergency_mode IN ('DISABLED', 'REGISTERED_PRINCIPAL', 'DUAL_APPROVAL')),
    CONSTRAINT ck_privileged_access_policy_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE com_privileged_role_eligibilities (
    privileged_role_eligibility_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    principal_type VARCHAR(20) NOT NULL,
    principal_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_ref VARCHAR(160),
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    justification VARCHAR(1000) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_privileged_role_eligibility_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT ck_privileged_role_eligibility_principal
        CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT ck_privileged_role_eligibility_scope
        CHECK (scope_type IN ('TENANT', 'ORG_UNIT', 'RESOURCE')),
    CONSTRAINT ck_privileged_role_eligibility_scope_ref
        CHECK ((scope_type = 'TENANT' AND scope_ref IS NULL)
            OR (scope_type <> 'TENANT' AND scope_ref IS NOT NULL)),
    CONSTRAINT ck_privileged_role_eligibility_window
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_privileged_role_eligibility_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uk_privileged_role_eligibility_active
    ON com_privileged_role_eligibilities (
        tenant_id, principal_type, principal_id, role_id,
        scope_type, COALESCE(scope_ref, ''))
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE com_emergency_access_principals (
    emergency_access_principal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    review_due_at TIMESTAMPTZ NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_emergency_access_principal_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT uk_emergency_access_principal_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_emergency_access_principal_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE com_privileged_access_requests (
    privileged_access_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    requester_user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    eligibility_id UUID,
    request_type VARCHAR(20) NOT NULL DEFAULT 'JIT',
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_ref VARCHAR(160),
    duration_minutes INTEGER NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    ticket_reference VARCHAR(160),
    assurance_level VARCHAR(20) NOT NULL,
    approval_quorum SMALLINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_privileged_access_request_user
        FOREIGN KEY (tenant_id, requester_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT fk_privileged_access_request_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT fk_privileged_access_request_eligibility
        FOREIGN KEY (eligibility_id)
        REFERENCES com_privileged_role_eligibilities(privileged_role_eligibility_id),
    CONSTRAINT ck_privileged_access_request_type
        CHECK (request_type IN ('JIT', 'EMERGENCY')),
    CONSTRAINT ck_privileged_access_request_scope
        CHECK (scope_type IN ('TENANT', 'ORG_UNIT', 'RESOURCE')),
    CONSTRAINT ck_privileged_access_request_scope_ref
        CHECK ((scope_type = 'TENANT' AND scope_ref IS NULL)
            OR (scope_type <> 'TENANT' AND scope_ref IS NOT NULL)),
    CONSTRAINT ck_privileged_access_request_duration
        CHECK (duration_minutes BETWEEN 15 AND 480),
    CONSTRAINT ck_privileged_access_request_assurance
        CHECK (assurance_level IN ('SESSION', 'MFA', 'PHISHING_RESISTANT')),
    CONSTRAINT ck_privileged_access_request_quorum
        CHECK (approval_quorum BETWEEN 0 AND 2),
    CONSTRAINT ck_privileged_access_request_state
        CHECK (lifecycle_state IN (
            'PENDING_APPROVAL', 'ACTIVE', 'DENIED', 'CANCELLED',
            'REVOKED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uk_privileged_access_request_open
    ON com_privileged_access_requests (
        tenant_id, requester_user_id, role_id, scope_type,
        COALESCE(scope_ref, ''))
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');

CREATE TABLE com_privileged_access_approvals (
    privileged_access_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    privileged_access_request_id UUID NOT NULL
        REFERENCES com_privileged_access_requests(privileged_access_request_id),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    approver_user_id BIGINT NOT NULL,
    decision VARCHAR(20) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_privileged_access_approval_user
        FOREIGN KEY (tenant_id, approver_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT uk_privileged_access_approval_actor
        UNIQUE (privileged_access_request_id, approver_user_id),
    CONSTRAINT ck_privileged_access_approval_decision
        CHECK (decision IN ('APPROVE', 'DENY'))
);

CREATE TABLE com_active_privileged_grants (
    active_privileged_grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    privileged_access_request_id UUID NOT NULL UNIQUE
        REFERENCES com_privileged_access_requests(privileged_access_request_id),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'TENANT',
    scope_ref VARCHAR(160),
    activated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT,
    revoke_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_active_privileged_grant_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT fk_active_privileged_grant_role
        FOREIGN KEY (tenant_id, role_id)
        REFERENCES com_roles(tenant_id, role_id),
    CONSTRAINT ck_active_privileged_grant_scope
        CHECK (scope_type IN ('TENANT', 'ORG_UNIT', 'RESOURCE')),
    CONSTRAINT ck_active_privileged_grant_window
        CHECK (expires_at > activated_at)
);

CREATE UNIQUE INDEX uk_active_privileged_grant_live
    ON com_active_privileged_grants (
        tenant_id, user_id, role_id, scope_type, COALESCE(scope_ref, ''))
    WHERE revoked_at IS NULL;

CREATE TABLE com_delegated_admin_scopes (
    delegated_admin_scope_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    administrator_user_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_ref VARCHAR(160),
    action_code VARCHAR(80) NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    justification VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_delegated_admin_scope_user
        FOREIGN KEY (tenant_id, administrator_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT ck_delegated_admin_scope_type
        CHECK (scope_type IN ('TENANT', 'ORG_UNIT', 'RESOURCE')),
    CONSTRAINT ck_delegated_admin_scope_ref
        CHECK ((scope_type = 'TENANT' AND scope_ref IS NULL)
            OR (scope_type <> 'TENANT' AND scope_ref IS NOT NULL)),
    CONSTRAINT ck_delegated_admin_scope_action
        CHECK (action_code = UPPER(BTRIM(action_code))
            AND action_code ~ '^[A-Z][A-Z0-9_.-]{2,79}$'),
    CONSTRAINT ck_delegated_admin_scope_window
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_delegated_admin_scope_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uk_delegated_admin_scope_active
    ON com_delegated_admin_scopes (
        tenant_id, administrator_user_id, scope_type,
        COALESCE(scope_ref, ''), action_code)
    WHERE lifecycle_state = 'ACTIVE';

ALTER TABLE sys_role_conflict_policies
    ADD COLUMN enforcement VARCHAR(24) NOT NULL DEFAULT 'DENY',
    ADD COLUMN risk_level VARCHAR(20) NOT NULL DEFAULT 'HIGH',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_sys_role_conflict_policy_enforcement
        CHECK (enforcement IN ('DENY', 'REQUIRE_APPROVAL')),
    ADD CONSTRAINT ck_sys_role_conflict_policy_risk
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
VALUES
    ('ADMIN', 'PLATFORM_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('ADMIN', 'TENANT_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('ADMIN', 'HR_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('ADMIN', 'PEOPLE_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('ADMIN', 'AUDIT_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('ADMIN', 'AUDITOR', 'APPROVAL', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'TENANT_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'HR_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'PEOPLE_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'AUDIT_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('PLATFORM_ADMIN', 'AUDITOR', 'APPROVAL', 'ACTIVE'),
    ('TENANT_ADMIN', 'HR_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('TENANT_ADMIN', 'PEOPLE_ADMIN', 'APPROVAL', 'ACTIVE'),
    ('TENANT_ADMIN', 'AUDITOR', 'APPROVAL', 'ACTIVE')
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode)
DO UPDATE SET lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_privileged_access_policies (
    tenant_id, role_id, activation_mode, maximum_duration_minutes,
    assurance_level, approval_quorum, emergency_mode, ticket_required,
    lifecycle_state, created_by, updated_by)
SELECT role.tenant_id,
       role.role_id,
       'APPROVAL',
       120,
       'MFA',
       1,
       'DISABLED',
       TRUE,
       'ACTIVE',
       1,
       1
  FROM com_roles role
 WHERE role.privileged = TRUE
ON CONFLICT (tenant_id, role_id) DO NOTHING;

CREATE INDEX idx_privileged_eligibility_principal
    ON com_privileged_role_eligibilities (
        tenant_id, principal_type, principal_id, lifecycle_state, valid_to);
CREATE INDEX idx_privileged_request_queue
    ON com_privileged_access_requests (
        tenant_id, lifecycle_state, requested_at DESC);
CREATE INDEX idx_privileged_grant_resolution
    ON com_active_privileged_grants (
        tenant_id, user_id, expires_at, revoked_at);
CREATE INDEX idx_delegated_admin_scope_resolution
    ON com_delegated_admin_scopes (
        tenant_id, administrator_user_id, action_code, lifecycle_state, valid_to);

COMMENT ON TABLE com_privileged_access_policies IS
    'Tenant role activation policy for time-bound eligible and emergency access.';
COMMENT ON TABLE com_privileged_role_eligibilities IS
    'Time-bound user or group eligibility; it never grants runtime access by itself.';
COMMENT ON TABLE com_active_privileged_grants IS
    'Materialized, expiring runtime grants issued only by the governed activation workflow.';
COMMENT ON TABLE com_delegated_admin_scopes IS
    'Server-enforced administrator action and population boundary.';
