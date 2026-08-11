ALTER TABLE sys_builtin_role_catalog
    ADD COLUMN assignment_class VARCHAR(24) NOT NULL DEFAULT 'GOVERNED';

UPDATE sys_builtin_role_catalog
   SET assignment_class = CASE role_code
       WHEN 'WORKSPACE_MEMBER' THEN 'BASELINE'
       WHEN 'HR_ADMIN' THEN 'DELEGATED'
       WHEN 'PEOPLE_ADMIN' THEN 'DELEGATED'
       WHEN 'AUDITOR' THEN 'DELEGATED'
       WHEN 'TENANT_ADMIN' THEN 'GOVERNED'
       WHEN 'AUDIT_ADMIN' THEN 'GOVERNED'
       ELSE 'CONTROL_PLANE'
   END;

ALTER TABLE sys_builtin_role_catalog
    ADD CONSTRAINT ck_sys_builtin_role_assignment_class
        CHECK (assignment_class IN (
            'BASELINE', 'DELEGATED', 'GOVERNED', 'CONTROL_PLANE'));

CREATE TABLE sys_role_assignment_policies (
    assignment_policy_id BIGSERIAL PRIMARY KEY,
    grantor_role_code VARCHAR(50) NOT NULL
        REFERENCES sys_builtin_role_catalog(role_code),
    target_role_code VARCHAR(50) NOT NULL
        REFERENCES sys_builtin_role_catalog(role_code),
    assignment_mode VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_assignment_policy
        UNIQUE (grantor_role_code, target_role_code, assignment_mode),
    CONSTRAINT ck_sys_role_assignment_policy_mode
        CHECK (assignment_mode IN ('DIRECT', 'APPROVAL', 'PROVISIONING')),
    CONSTRAINT ck_sys_role_assignment_policy_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_sys_role_assignment_policy_not_self
        CHECK (grantor_role_code <> target_role_code)
);

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, target.role_code, 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES
    ('WORKSPACE_MEMBER'), ('HR_ADMIN'), ('PEOPLE_ADMIN'), ('AUDITOR')
 ) target(role_code);

CREATE INDEX idx_sys_role_assignment_policy_resolution
    ON sys_role_assignment_policies(
        grantor_role_code, assignment_mode, lifecycle_state, target_role_code);

CREATE TABLE sys_role_conflict_policies (
    role_conflict_policy_id BIGSERIAL PRIMARY KEY,
    left_role_code VARCHAR(50) NOT NULL
        REFERENCES sys_builtin_role_catalog(role_code),
    right_role_code VARCHAR(50) NOT NULL
        REFERENCES sys_builtin_role_catalog(role_code),
    reason_code VARCHAR(80) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_conflict_policy
        UNIQUE (left_role_code, right_role_code),
    CONSTRAINT ck_sys_role_conflict_policy_order
        CHECK (left_role_code < right_role_code),
    CONSTRAINT ck_sys_role_conflict_policy_reason
        CHECK (reason_code = UPPER(BTRIM(reason_code))
            AND reason_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_sys_role_conflict_policy_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
VALUES
    ('AUDITOR', 'HR_ADMIN', 'AUDIT_INDEPENDENCE', 'ACTIVE'),
    ('AUDITOR', 'PEOPLE_ADMIN', 'AUDIT_INDEPENDENCE', 'ACTIVE');

CREATE INDEX idx_sys_role_conflict_policy_active
    ON sys_role_conflict_policies(
        lifecycle_state, left_role_code, right_role_code);

COMMENT ON TABLE sys_role_assignment_policies IS
    'Deny-by-default authority matrix for direct, approved, and provisioning role assignments.';
COMMENT ON TABLE sys_role_conflict_policies IS
    'Separation-of-duties constraints evaluated against direct and inherited effective roles.';
COMMENT ON COLUMN sys_builtin_role_catalog.assignment_class IS
    'Assignment governance tier: baseline, delegated, governed, or control-plane only.';

ALTER TABLE sys_identity_audit_events
    ADD COLUMN outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN reason VARCHAR(500),
    ADD CONSTRAINT ck_sys_identity_audit_outcome
        CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'));

CREATE OR REPLACE FUNCTION sys_identity_audit_to_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), NEW.audit_event_id, NEW.tenant_id,
        jsonb_build_object(
            'eventId', NEW.audit_event_id,
            'eventVersion', '1.0',
            'occurredAt', NEW.occurred_at,
            'tenantId', NEW.tenant_id,
            'category', CASE
                WHEN NEW.action LIKE 'provisioning.%' THEN 'PROVISIONING'
                WHEN NEW.action LIKE 'access.%' OR NEW.action LIKE 'identity.%' THEN 'AUTHORIZATION'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', NEW.outcome,
            'severity', CASE WHEN NEW.outcome = 'DENIED' THEN 'HIGH' ELSE 'INFO' END,
            'riskScore', CASE WHEN NEW.outcome = 'DENIED' THEN 70 ELSE 15 END,
            'actorType', 'USER',
            'actorId', NEW.actor_id::TEXT,
            'actorRoles', '[]'::jsonb,
            'sourceService', 'dwp-auth-server',
            'sourceModule', 'identity-governance',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', NEW.target_type,
            'targetId', NEW.target_id,
            'targetDisplayName', NEW.target_id,
            'correlationId', NEW.correlation_id,
            'reason', NEW.reason,
            'beforeState', CASE
                WHEN NEW.before_snapshot IS NULL THEN '{}'::jsonb
                ELSE NEW.before_snapshot::jsonb
            END,
            'afterState', CASE
                WHEN NEW.after_snapshot IS NULL THEN '{}'::jsonb
                ELSE NEW.after_snapshot::jsonb
            END,
            'metadata', jsonb_build_object('legacyAuditEventId', NEW.audit_event_id),
            'retentionClass', CASE
                WHEN NEW.outcome = 'DENIED' THEN 'EXTENDED'
                ELSE 'STANDARD'
            END),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;
