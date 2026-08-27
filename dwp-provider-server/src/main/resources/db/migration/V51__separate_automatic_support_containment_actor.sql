-- Automatic containment must remain executable when the request/session owner
-- is no longer an active provider operator. Attribute those database-owned
-- transitions to a non-login system principal and retain an explicit origin;
-- manual cancellation and revocation continue to use the V49 actor guards.

INSERT INTO prv_operator_roles (role_code, display_name, description, lifecycle_state)
VALUES (
    'PROVIDER_SYSTEM_CONTAINMENT',
    'Provider support containment system',
    'Non-login database principal for policy and tenant-state support containment.',
    'ACTIVE')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE';

INSERT INTO prv_operators (
    auth_tenant_id, auth_user_id, display_name, role_code, lifecycle_state)
VALUES (
    -1, -5100001, 'Provider support containment system',
    'PROVIDER_SYSTEM_CONTAINMENT', 'ACTIVE')
ON CONFLICT (auth_tenant_id, auth_user_id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    role_code = EXCLUDED.role_code,
    lifecycle_state = 'ACTIVE',
    updated_at = statement_timestamp();

ALTER TABLE prv_operators
    ADD CONSTRAINT ck_prv_support_containment_system_identity
    CHECK (
        (auth_tenant_id = -1 AND auth_user_id = -5100001
            AND display_name = 'Provider support containment system'
            AND role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
            AND lifecycle_state = 'ACTIVE')
        OR (auth_tenant_id <> -1 AND auth_user_id <> -5100001));

-- V49 intentionally accepts an active PROVIDER_ADMIN as a revocation actor.
-- This assignment is never projected into an Auth identity: the principal's
-- negative Auth coordinates are reserved and cannot be issued by Auth.
INSERT INTO prv_operator_role_assignments (
    provider_operator_id, role_code, lifecycle_state, valid_from, valid_to, created_by)
SELECT operator.provider_operator_id, 'PROVIDER_ADMIN', 'ACTIVE', NULL, NULL, -5100001
  FROM prv_operators operator
 WHERE operator.auth_tenant_id = -1
   AND operator.auth_user_id = -5100001
ON CONFLICT (provider_operator_id, role_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    valid_from = NULL,
    valid_to = NULL;

ALTER TABLE prv_support_access_requests
    ADD COLUMN cancellation_origin VARCHAR(40),
    ADD CONSTRAINT ck_prv_support_request_cancellation_origin
        CHECK (cancellation_origin IS NULL OR cancellation_origin IN (
            'AUTOMATIC_SCOPE_RETIREMENT', 'AUTOMATIC_OPERATOR_CONTAINMENT'));

ALTER TABLE prv_support_sessions
    ADD COLUMN revocation_origin VARCHAR(40),
    ADD CONSTRAINT ck_prv_support_session_revocation_origin
        CHECK (revocation_origin IS NULL OR revocation_origin IN (
            'AUTOMATIC_SCOPE_RETIREMENT', 'AUTOMATIC_TENANT_CONTAINMENT',
            'AUTOMATIC_OPERATOR_CONTAINMENT'));

CREATE OR REPLACE FUNCTION prv_guard_support_containment_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    containment_operator_id BIGINT;
    origin_setting TEXT := current_setting('dwp.support_containment_origin', TRUE);
BEGIN
    SELECT provider_operator_id INTO STRICT containment_operator_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
       AND lifecycle_state = 'ACTIVE';

    IF TG_TABLE_NAME = 'prv_support_access_requests' THEN
        IF TG_OP = 'INSERT' AND NEW.cancellation_origin IS NOT NULL THEN
            RAISE EXCEPTION 'support request containment origin cannot be supplied on insert';
        END IF;
        IF TG_OP = 'UPDATE'
           AND OLD.cancellation_origin IS DISTINCT FROM NEW.cancellation_origin THEN
            IF OLD.cancellation_origin IS NOT NULL
               OR NEW.lifecycle_state <> 'CANCELLED'
               OR NEW.cancelled_by <> containment_operator_id
               OR NOT (
                   (NEW.cancellation_origin = 'AUTOMATIC_SCOPE_RETIREMENT'
                       AND origin_setting = 'scope-retirement'
                       AND NEW.cancellation_reason LIKE 'Support scope retired by policy: %')
                   OR (NEW.cancellation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT'
                       AND origin_setting = 'operator-unavailable'
                       AND NEW.cancellation_reason = 'Support requester became unavailable')) THEN
                RAISE EXCEPTION 'support request containment provenance is invalid';
            END IF;
        END IF;
        IF TG_OP = 'UPDATE'
           AND OLD.lifecycle_state <> 'CANCELLED'
           AND NEW.lifecycle_state = 'CANCELLED'
           AND NEW.cancelled_by = containment_operator_id
           AND NEW.cancellation_origin IS NULL THEN
            RAISE EXCEPTION 'system containment actor requires request provenance';
        END IF;
    ELSIF TG_TABLE_NAME = 'prv_support_sessions' THEN
        IF TG_OP = 'INSERT' AND NEW.revocation_origin IS NOT NULL THEN
            RAISE EXCEPTION 'support session containment origin cannot be supplied on insert';
        END IF;
        IF TG_OP = 'UPDATE'
           AND OLD.revocation_origin IS DISTINCT FROM NEW.revocation_origin THEN
            IF OLD.revocation_origin IS NOT NULL
               OR NEW.lifecycle_state <> 'REVOKED'
               OR NEW.revoked_by <> containment_operator_id
               OR NOT (
                   (NEW.revocation_origin = 'AUTOMATIC_SCOPE_RETIREMENT'
                       AND origin_setting = 'scope-retirement')
                   OR (NEW.revocation_origin = 'AUTOMATIC_TENANT_CONTAINMENT'
                       AND origin_setting = 'tenant-unavailable')
                   OR (NEW.revocation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT'
                       AND origin_setting = 'operator-unavailable')) THEN
                RAISE EXCEPTION 'support session containment provenance is invalid';
            END IF;
        END IF;
        IF TG_OP = 'UPDATE'
           AND OLD.lifecycle_state <> 'REVOKED'
           AND NEW.lifecycle_state = 'REVOKED'
           AND NEW.revoked_by = containment_operator_id
           AND NEW.revocation_origin IS NULL THEN
            RAISE EXCEPTION 'system containment actor requires session provenance';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_guard_support_containment_request_provenance
BEFORE INSERT OR UPDATE ON prv_support_access_requests
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_containment_provenance();

CREATE TRIGGER trg_prv_guard_support_containment_session_provenance
BEFORE INSERT OR UPDATE ON prv_support_sessions
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_containment_provenance();

CREATE OR REPLACE FUNCTION prv_reconcile_retired_support_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    containment_operator_id BIGINT;
    containment_auth_user_id BIGINT;
BEGIN
    IF NEW.lifecycle_state = 'ACTIVE' THEN RETURN NEW; END IF;

    SELECT provider_operator_id, auth_user_id
      INTO STRICT containment_operator_id, containment_auth_user_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND lifecycle_state = 'ACTIVE';
    PERFORM set_config('dwp.support_containment_origin', 'scope-retirement', TRUE);

    WITH cancelled_requests AS (
        UPDATE prv_support_access_requests request
           SET lifecycle_state = 'CANCELLED',
               cancelled_at = statement_timestamp(),
               cancelled_by = containment_operator_id,
               cancellation_reason = 'Support scope retired by policy: ' || NEW.scope_code,
               cancellation_origin = 'AUTOMATIC_SCOPE_RETIREMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = request.version + 1
         WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
           AND EXISTS (
               SELECT 1 FROM prv_support_access_request_scopes scope
                WHERE scope.support_access_request_id = request.support_access_request_id
                  AND scope.scope_code = NEW.scope_code)
        RETURNING request.support_access_request_id, request.provider_tenant_id
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-access.cancelled-by-policy',
           'SUPPORT_ACCESS_REQUEST', cancelled.support_access_request_id::text,
           'SUCCESS', 'policy:scope-retired:' || NEW.scope_code,
           jsonb_build_object(
               'scope', NEW.scope_code,
               'reason', 'SCOPE_RETIRED',
               'transitionOrigin', 'AUTOMATIC_SCOPE_RETIREMENT'),
           containment_operator_id, cancelled.provider_tenant_id,
           tenant.organization_id, 'PRIVILEGED_ACCESS'
      FROM cancelled_requests cancelled
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = cancelled.provider_tenant_id;

    WITH revoked_sessions AS (
        UPDATE prv_support_sessions session
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = containment_operator_id,
               revocation_origin = 'AUTOMATIC_SCOPE_RETIREMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = session.version + 1
         WHERE session.lifecycle_state = 'ACTIVE'
           AND EXISTS (
               SELECT 1 FROM prv_support_session_scopes scope
                WHERE scope.support_session_id = session.support_session_id
                  AND scope.scope_code = NEW.scope_code)
        RETURNING session.support_session_id, session.provider_tenant_id
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-session.revoked-by-policy',
           'SUPPORT_SESSION', revoked.support_session_id::text,
           'SUCCESS', 'policy:scope-retired:' || NEW.scope_code,
           jsonb_build_object(
               'scope', NEW.scope_code,
               'reason', 'SCOPE_RETIRED',
               'transitionOrigin', 'AUTOMATIC_SCOPE_RETIREMENT'),
           containment_operator_id, revoked.provider_tenant_id,
           tenant.organization_id, 'PRIVILEGED_ACCESS'
      FROM revoked_sessions revoked
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = revoked.provider_tenant_id;

    PERFORM set_config('dwp.support_containment_origin', '', TRUE);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prv_revoke_support_for_unavailable_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    containment_operator_id BIGINT;
    containment_auth_user_id BIGINT;
BEGIN
    IF NEW.lifecycle_state = 'ACTIVE'
       AND NEW.onboarding_state = 'READY'
       AND NEW.auth_tenant_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT provider_operator_id, auth_user_id
      INTO STRICT containment_operator_id, containment_auth_user_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND lifecycle_state = 'ACTIVE';
    PERFORM set_config('dwp.support_containment_origin', 'tenant-unavailable', TRUE);

    WITH revoked AS (
        UPDATE prv_support_sessions session
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = containment_operator_id,
               revocation_origin = 'AUTOMATIC_TENANT_CONTAINMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = session.version + 1
         WHERE session.provider_tenant_id = NEW.provider_tenant_id
           AND session.lifecycle_state = 'ACTIVE'
        RETURNING session.support_session_id,
                  session.support_access_request_id,
                  session.version
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-session.revoked-for-tenant-state',
           'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
           'automatic:tenant-unavailable',
           jsonb_build_object(
               'supportSessionId', revoked.support_session_id,
               'supportAccessRequestId', revoked.support_access_request_id,
               'tenantLifecycleState', NEW.lifecycle_state,
               'tenantOnboardingState', NEW.onboarding_state,
               'authTenantLinked', NEW.auth_tenant_id IS NOT NULL,
               'sessionVersion', revoked.version,
               'reasonCode', 'TARGET_TENANT_UNAVAILABLE',
               'transitionOrigin', 'AUTOMATIC_TENANT_CONTAINMENT'),
           containment_operator_id, NEW.provider_tenant_id,
           NEW.organization_id, 'PRIVILEGED_ACCESS'
      FROM revoked;

    PERFORM set_config('dwp.support_containment_origin', '', TRUE);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prv_revoke_support_for_unavailable_operator()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    containment_operator_id BIGINT;
    containment_auth_user_id BIGINT;
BEGIN
    IF NEW.lifecycle_state = 'ACTIVE'
       OR NEW.provider_operator_id = (
           SELECT provider_operator_id FROM prv_operators
            WHERE auth_tenant_id = -1 AND auth_user_id = -5100001) THEN
        RETURN NEW;
    END IF;

    SELECT provider_operator_id, auth_user_id
      INTO STRICT containment_operator_id, containment_auth_user_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND lifecycle_state = 'ACTIVE';
    PERFORM set_config('dwp.support_containment_origin', 'operator-unavailable', TRUE);

    WITH cancelled AS (
        UPDATE prv_support_access_requests request
           SET lifecycle_state = 'CANCELLED',
               cancelled_at = statement_timestamp(),
               cancelled_by = containment_operator_id,
               cancellation_reason = 'Support requester became unavailable',
               cancellation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = request.version + 1
         WHERE request.requester_operator_id = NEW.provider_operator_id
           AND request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
        RETURNING request.support_access_request_id, request.provider_tenant_id
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-access.cancelled-for-operator-state',
           'SUPPORT_ACCESS_REQUEST', cancelled.support_access_request_id::text,
           'SUCCESS', 'automatic:operator-unavailable',
           jsonb_build_object(
               'operatorLifecycleState', NEW.lifecycle_state,
               'reasonCode', 'REQUESTER_UNAVAILABLE',
               'transitionOrigin', 'AUTOMATIC_OPERATOR_CONTAINMENT'),
           containment_operator_id, cancelled.provider_tenant_id,
           tenant.organization_id, 'PRIVILEGED_ACCESS'
      FROM cancelled
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = cancelled.provider_tenant_id;

    WITH revoked AS (
        UPDATE prv_support_sessions session
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = containment_operator_id,
               revocation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = session.version + 1
         WHERE session.provider_operator_id = NEW.provider_operator_id
           AND session.lifecycle_state = 'ACTIVE'
        RETURNING session.support_session_id, session.provider_tenant_id
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-session.revoked-for-operator-state',
           'SUPPORT_SESSION', revoked.support_session_id::text,
           'SUCCESS', 'automatic:operator-unavailable',
           jsonb_build_object(
               'operatorLifecycleState', NEW.lifecycle_state,
               'reasonCode', 'OPERATOR_UNAVAILABLE',
               'transitionOrigin', 'AUTOMATIC_OPERATOR_CONTAINMENT'),
           containment_operator_id, revoked.provider_tenant_id,
           tenant.organization_id, 'PRIVILEGED_ACCESS'
      FROM revoked
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = revoked.provider_tenant_id;

    PERFORM set_config('dwp.support_containment_origin', '', TRUE);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_revoke_support_for_unavailable_operator
AFTER UPDATE OF lifecycle_state ON prv_operators
FOR EACH ROW
WHEN (NEW.lifecycle_state <> 'ACTIVE')
EXECUTE FUNCTION prv_revoke_support_for_unavailable_operator();

-- Reconcile poison rows that pre-date this forward migration. A no-op update
-- intentionally invokes the idempotent containment trigger for each inactive
-- human operator without changing operator lifecycle evidence.
UPDATE prv_operators
   SET lifecycle_state = lifecycle_state
 WHERE lifecycle_state <> 'ACTIVE'
   AND NOT (auth_tenant_id = -1 AND auth_user_id = -5100001);

COMMENT ON COLUMN prv_support_access_requests.cancellation_origin IS
    'Immutable machine-readable provenance for database-owned cancellation; NULL for manual commands.';
COMMENT ON COLUMN prv_support_sessions.revocation_origin IS
    'Immutable machine-readable provenance for database-owned revocation; NULL for manual commands.';
