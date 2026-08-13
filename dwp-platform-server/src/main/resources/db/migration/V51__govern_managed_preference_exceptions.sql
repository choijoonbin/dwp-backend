CREATE TABLE adm_managed_preference_policies (
    managed_preference_policy_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL UNIQUE,
    policy_key VARCHAR(100) NOT NULL DEFAULT 'TENANT_EXPERIENCE_POLICY',
    policy_source VARCHAR(40) NOT NULL DEFAULT 'TENANT_EXPERIENCE_POLICY',
    owner_type VARCHAR(20) NOT NULL DEFAULT 'ROLE',
    owner_ref VARCHAR(160) NOT NULL DEFAULT 'TENANT_ADMIN',
    owner_display_name VARCHAR(200) NOT NULL DEFAULT 'Tenant administrator',
    contact_uri VARCHAR(500) NOT NULL DEFAULT '/admin/experience/preference-exceptions',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_adm_managed_preference_policy_owner
        CHECK (owner_type IN ('ROLE', 'GROUP', 'USER', 'SERVICE_DESK')),
    CONSTRAINT ck_adm_managed_preference_policy_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_managed_preference_policy_contact
        CHECK (contact_uri LIKE '/%' OR contact_uri LIKE 'mailto:%' OR contact_uri LIKE 'https://%')
);

CREATE TABLE adm_managed_preference_rules (
    managed_preference_rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    managed_preference_policy_id UUID NOT NULL
        REFERENCES adm_managed_preference_policies(managed_preference_policy_id),
    tenant_id BIGINT NOT NULL,
    preference_path VARCHAR(180) NOT NULL,
    display_key VARCHAR(180) NOT NULL,
    managed_value JSONB NOT NULL DEFAULT 'null'::jsonb,
    exception_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_managed_preference_rule_path UNIQUE (tenant_id, preference_path),
    CONSTRAINT ck_adm_managed_preference_rule_path
        CHECK (preference_path ~ '^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)+$'),
    CONSTRAINT ck_adm_managed_preference_rule_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE usr_preference_exception_requests (
    preference_exception_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    managed_preference_policy_id UUID NOT NULL
        REFERENCES adm_managed_preference_policies(managed_preference_policy_id),
    managed_preference_rule_id UUID NOT NULL
        REFERENCES adm_managed_preference_rules(managed_preference_rule_id),
    preference_path VARCHAR(180) NOT NULL,
    requested_value JSONB NOT NULL,
    business_justification VARCHAR(1000) NOT NULL,
    business_impact VARCHAR(1000) NOT NULL,
    request_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    policy_version BIGINT NOT NULL,
    rule_version BIGINT NOT NULL,
    assigned_owner_ref VARCHAR(160) NOT NULL,
    requested_until TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    decision_evidence_ref VARCHAR(500),
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_usr_preference_exception_state
        CHECK (request_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_usr_preference_exception_value
        CHECK (jsonb_typeof(requested_value) IN ('string', 'number', 'boolean', 'object', 'array')),
    CONSTRAINT ck_usr_preference_exception_decision
        CHECK (
            (request_state IN ('APPROVED', 'REJECTED')
                AND decision_reason IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
            OR (request_state = 'CANCELLED' AND cancelled_at IS NOT NULL)
            OR request_state IN ('PENDING', 'EXPIRED')
        ),
    CONSTRAINT ck_usr_preference_exception_expiry
        CHECK (requested_until IS NULL OR requested_until > created_at)
);

CREATE UNIQUE INDEX uk_usr_preference_exception_pending
    ON usr_preference_exception_requests(tenant_id, user_id, preference_path)
    WHERE request_state = 'PENDING';

CREATE INDEX idx_usr_preference_exception_user
    ON usr_preference_exception_requests(tenant_id, user_id, created_at DESC);

CREATE INDEX idx_usr_preference_exception_queue
    ON usr_preference_exception_requests(tenant_id, request_state, created_at DESC);

CREATE TABLE usr_preference_exception_decisions (
    preference_exception_decision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    preference_exception_request_id UUID NOT NULL
        REFERENCES usr_preference_exception_requests(preference_exception_request_id),
    tenant_id BIGINT NOT NULL,
    previous_state VARCHAR(24) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    evidence_ref VARCHAR(500),
    actor_type VARCHAR(16) NOT NULL,
    actor_id BIGINT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_usr_preference_exception_decision_code
        CHECK (decision IN ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_usr_preference_exception_decision_actor
        CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM')),
    CONSTRAINT ck_usr_preference_exception_decision_identity
        CHECK ((actor_type IN ('USER', 'ADMIN') AND actor_id IS NOT NULL) OR actor_type = 'SYSTEM')
);

CREATE OR REPLACE FUNCTION sys_reject_preference_exception_decision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Preference exception decisions are append-only';
END;
$$;

CREATE TRIGGER trg_usr_preference_exception_decisions_immutable
BEFORE UPDATE OR DELETE ON usr_preference_exception_decisions
FOR EACH ROW EXECUTE FUNCTION sys_reject_preference_exception_decision_mutation();

INSERT INTO adm_managed_preference_policies (tenant_id)
SELECT tenant_id
  FROM sys_service_tenants
 WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO adm_managed_preference_rules (
    managed_preference_policy_id, tenant_id, preference_path, display_key,
    managed_value, exception_allowed)
SELECT policy.managed_preference_policy_id, policy.tenant_id, rule.preference_path,
       rule.display_key, rule.managed_value, TRUE
  FROM adm_managed_preference_policies policy
 CROSS JOIN (VALUES
    ('appearance.fontFamily', 'settings.productFont.title', 'null'::jsonb),
    ('appearance.accentColor', 'settings.brandAccent.title', 'null'::jsonb),
    ('navigation.pattern', 'settings.navigationPattern.title', '"sidebar"'::jsonb)
 ) AS rule(preference_path, display_key, managed_value)
ON CONFLICT (tenant_id, preference_path) DO NOTHING;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'dwp-platform-server',
     'Preference exception request state',
     'Lifecycle of a user request to review an organization-managed preference.',
     'SYSTEM', 'CHECK', 'usr_preference_exception_requests.request_state', 'STATE_MACHINE'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'dwp-platform-server',
     'Preference exception decision',
     'Governed terminal decision recorded for a managed preference exception request.',
     'SYSTEM', 'CHECK', 'usr_preference_exception_decisions.decision', 'PROTOCOL');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'PENDING', 'Pending',
     '{"ko":"검토 대기","en":"Pending"}', 10, '{"terminal":false}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'APPROVED', 'Approved',
     '{"ko":"승인","en":"Approved"}', 20, '{"terminal":true}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'REJECTED', 'Rejected',
     '{"ko":"반려","en":"Rejected"}', 30, '{"terminal":true}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'CANCELLED', 'Cancelled',
     '{"ko":"취소","en":"Cancelled"}', 40, '{"terminal":true}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'EXPIRED', 'Expired',
     '{"ko":"만료","en":"Expired"}', 50, '{"terminal":true}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'APPROVED', 'Approved',
     '{"ko":"승인","en":"Approved"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'REJECTED', 'Rejected',
     '{"ko":"반려","en":"Rejected"}', 20, '{}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'CANCELLED', 'Cancelled',
     '{"ko":"취소","en":"Cancelled"}', 30, '{}'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'EXPIRED', 'Expired',
     '{"ko":"만료","en":"Expired"}', 40, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'dwp-platform-server', 'DATABASE_COLUMN',
     'usr_preference_exception_requests.request_state', 'CHECK'),
    ('PLATFORM.PREFERENCE.EXCEPTION_STATE', 'dwp-frontend', 'UI_SELECTION',
     'account managed preference exception state', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'dwp-platform-server', 'API_CONTRACT',
     'ManagedPreferenceDtos.DecideExceptionRequest', 'TYPED_CONTRACT'),
    ('PLATFORM.PREFERENCE.EXCEPTION_DECISION', 'dwp-frontend', 'UI_SELECTION',
     'admin preference exception decision', 'CATALOG_LOOKUP');

COMMENT ON TABLE adm_managed_preference_policies IS
    'Tenant-owned managed preference policy and accountable review route; separate from user settings.';
COMMENT ON TABLE adm_managed_preference_rules IS
    'Path-level managed preference rules and exception eligibility.';
COMMENT ON TABLE usr_preference_exception_requests IS
    'User-owned requests to review a tenant-managed preference without mutating the preference directly.';
COMMENT ON TABLE usr_preference_exception_decisions IS
    'Append-only accountable decision evidence for managed preference exceptions.';
