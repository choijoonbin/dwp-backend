-- V52 is immutable after deployment (SHA-256 99354b10b455a7dac4cccec0ab8d44c854cd80fa11e99a30e9f9b3f60ca43b19,
-- Flyway checksum 1542396037). Extend its database-owned containment boundary
-- without restoring any interactive authority to the SYSTEM principal.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp:provider:support-containment', 0));

-- Preserve the exact V52 quiescence order. This covers every authority source,
-- grant row, automatic containment source, and evidence sink changed below.
LOCK TABLE
    prv_operators,
    prv_operator_roles,
    prv_operator_role_assignments,
    prv_operator_role_permissions,
    prv_operator_permission_catalog,
    prv_support_scope_catalog,
    prv_tenants,
    prv_support_activation_control,
    prv_support_access_requests,
    prv_support_access_request_scopes,
    prv_support_sessions,
    prv_support_session_scopes,
    prv_audit_events,
    sys_audit_outbox
IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    system_operator_id BIGINT;
BEGIN
    IF (SELECT COUNT(*)
          FROM flyway_schema_history history
         WHERE history.version = '52'
           AND history.checksum = 1542396037
           AND history.success) <> 1 THEN
        RAISE EXCEPTION 'exact applied V52 support containment baseline could not be proven';
    END IF;

    SELECT operator.provider_operator_id INTO STRICT system_operator_id
      FROM prv_operators operator
     WHERE operator.auth_tenant_id = -1
       AND operator.auth_user_id = -5100001
       AND operator.display_name = 'Provider support containment system'
       AND operator.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
       AND operator.lifecycle_state = 'ACTIVE';

    IF (SELECT COUNT(*)
          FROM prv_operator_roles role
         WHERE role.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
           AND role.display_name = 'Provider support containment system'
           AND role.description =
               'Non-login database principal for policy and tenant-state support containment.'
           AND role.lifecycle_state = 'ACTIVE') <> 1
       OR EXISTS (
           SELECT 1
             FROM prv_operators operator
            WHERE operator.provider_operator_id <> system_operator_id
              AND (operator.auth_tenant_id = -1
                   OR operator.auth_user_id = -5100001
                   OR operator.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'))
       OR EXISTS (
           SELECT 1
             FROM prv_operator_role_assignments assignment
            WHERE assignment.provider_operator_id = system_operator_id
               OR assignment.role_code = 'PROVIDER_SYSTEM_CONTAINMENT')
       OR EXISTS (
           SELECT 1
             FROM prv_operator_role_permissions permission
            WHERE permission.role_code = 'PROVIDER_SYSTEM_CONTAINMENT') THEN
        RAISE EXCEPTION 'V52 support containment zero-authority principal is not canonical';
    END IF;
END;
$$;

ALTER TABLE prv_support_access_requests
    DROP CONSTRAINT ck_prv_support_request_cancellation_origin,
    ADD CONSTRAINT ck_prv_support_request_cancellation_origin
        CHECK (cancellation_origin IS NULL OR cancellation_origin IN (
            'AUTOMATIC_SCOPE_RETIREMENT',
            'AUTOMATIC_OPERATOR_CONTAINMENT',
            'AUTOMATIC_AUTHORITY_CONTAINMENT'));

ALTER TABLE prv_support_sessions
    DROP CONSTRAINT ck_prv_support_session_revocation_origin,
    ADD CONSTRAINT ck_prv_support_session_revocation_origin
        CHECK (revocation_origin IS NULL OR revocation_origin IN (
            'AUTOMATIC_SCOPE_RETIREMENT',
            'AUTOMATIC_TENANT_CONTAINMENT',
            'AUTOMATIC_OPERATOR_CONTAINMENT',
            'AUTOMATIC_AUTHORITY_CONTAINMENT'));

ALTER TABLE prv_support_activation_control
    ADD COLUMN authority_reconciled_at TIMESTAMPTZ NOT NULL
        DEFAULT statement_timestamp();

CREATE OR REPLACE FUNCTION prv_operator_has_effective_support_authority(
    resolved_operator_id BIGINT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM prv_operators operator
          JOIN prv_operator_role_assignments assignment
            ON assignment.provider_operator_id = operator.provider_operator_id
           AND assignment.lifecycle_state = 'ACTIVE'
           AND (assignment.valid_from IS NULL
                OR assignment.valid_from <= statement_timestamp())
           AND (assignment.valid_to IS NULL
                OR assignment.valid_to > statement_timestamp())
          JOIN prv_operator_roles role
            ON role.role_code = assignment.role_code
           AND role.lifecycle_state = 'ACTIVE'
          JOIN prv_operator_role_permissions permission
            ON permission.role_code = role.role_code
           AND permission.permission_code = 'SUPPORT_SESSION_WRITE'
          JOIN prv_operator_permission_catalog catalog
            ON catalog.permission_code = permission.permission_code
           AND catalog.lifecycle_state = 'ACTIVE'
           AND catalog.risk_tier = 'L3'
         WHERE operator.provider_operator_id = resolved_operator_id
           AND operator.lifecycle_state = 'ACTIVE'
           AND operator.auth_tenant_id > 0
           AND operator.auth_user_id > 0
           AND operator.role_code <> 'PROVIDER_SYSTEM_CONTAINMENT');
$$;

COMMENT ON FUNCTION prv_operator_has_effective_support_authority(BIGINT) IS
    'Database source of truth for current human SUPPORT_SESSION_WRITE authority, including the active L3 permission catalog contract.';

CREATE OR REPLACE FUNCTION prv_valid_automatic_request_containment(
    request_id UUID,
    requester_id BIGINT,
    next_state VARCHAR,
    containment_actor_id BIGINT,
    containment_reason TEXT,
    containment_origin TEXT)
RETURNS BOOLEAN
LANGUAGE sql
VOLATILE
PARALLEL UNSAFE
AS $$
    SELECT next_state = 'CANCELLED'
       AND containment_actor_id = (
           SELECT operator.provider_operator_id
             FROM prv_operators operator
            WHERE operator.auth_tenant_id = -1
              AND operator.auth_user_id = -5100001
              AND operator.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
              AND operator.lifecycle_state = 'ACTIVE')
       AND pg_trigger_depth() > 1
       AND (
           (containment_origin = 'AUTOMATIC_SCOPE_RETIREMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'scope-retirement'
               AND EXISTS (
                   SELECT 1
                     FROM prv_support_access_request_scopes request_scope
                     JOIN prv_support_scope_catalog catalog
                       ON catalog.scope_code = request_scope.scope_code
                    WHERE request_scope.support_access_request_id = request_id
                      AND catalog.lifecycle_state <> 'ACTIVE'
                      AND containment_reason =
                          'Support scope retired by policy: ' || catalog.scope_code))
           OR (containment_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'operator-unavailable'
               AND containment_reason = 'Support requester became unavailable'
               AND EXISTS (
                   SELECT 1
                     FROM prv_operators requester
                    WHERE requester.provider_operator_id = requester_id
                      AND requester.lifecycle_state <> 'ACTIVE'))
           OR (containment_origin = 'AUTOMATIC_AUTHORITY_CONTAINMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'authority-unavailable'
               AND containment_reason =
                   'Support owner authority became unavailable'
               AND NOT prv_operator_has_effective_support_authority(requester_id)));
$$;

CREATE OR REPLACE FUNCTION prv_valid_automatic_session_containment(
    session_id UUID,
    tenant_id UUID,
    owner_id BIGINT,
    next_state VARCHAR,
    containment_actor_id BIGINT,
    containment_origin TEXT)
RETURNS BOOLEAN
LANGUAGE sql
VOLATILE
PARALLEL UNSAFE
AS $$
    SELECT next_state = 'REVOKED'
       AND containment_actor_id = (
           SELECT operator.provider_operator_id
             FROM prv_operators operator
            WHERE operator.auth_tenant_id = -1
              AND operator.auth_user_id = -5100001
              AND operator.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
              AND operator.lifecycle_state = 'ACTIVE')
       AND pg_trigger_depth() > 1
       AND (
           (containment_origin = 'AUTOMATIC_SCOPE_RETIREMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'scope-retirement'
               AND EXISTS (
                   SELECT 1
                     FROM prv_support_session_scopes session_scope
                     JOIN prv_support_scope_catalog catalog
                       ON catalog.scope_code = session_scope.scope_code
                    WHERE session_scope.support_session_id = session_id
                      AND catalog.lifecycle_state <> 'ACTIVE'))
           OR (containment_origin = 'AUTOMATIC_TENANT_CONTAINMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'tenant-unavailable'
               AND EXISTS (
                   SELECT 1
                     FROM prv_tenants tenant
                    WHERE tenant.provider_tenant_id = tenant_id
                      AND (tenant.lifecycle_state <> 'ACTIVE'
                           OR tenant.onboarding_state <> 'READY'
                           OR tenant.auth_tenant_id IS NULL)))
           OR (containment_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'operator-unavailable'
               AND EXISTS (
                   SELECT 1
                     FROM prv_operators operator
                    WHERE operator.provider_operator_id = owner_id
                      AND operator.lifecycle_state <> 'ACTIVE'))
           OR (containment_origin = 'AUTOMATIC_AUTHORITY_CONTAINMENT'
               AND current_setting('dwp.support_containment_origin', TRUE) =
                   'authority-unavailable'
               AND NOT prv_operator_has_effective_support_authority(owner_id)));
$$;

-- Every new grant first holds the singleton control row. Authority retirement
-- takes that row before touching its source row, eliminating the reverse lock
-- order with the kill switch and the periodic authority pulse.
CREATE OR REPLACE FUNCTION prv_lock_support_grant_owner_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    owner_id BIGINT;
BEGIN
    PERFORM 1
      FROM prv_support_activation_control control
     WHERE control.control_key = 'STANDARD_JIT'
     FOR SHARE OF control;

    IF TG_TABLE_NAME = 'prv_support_access_requests' THEN
        owner_id := NEW.requester_operator_id;
    ELSE
        SELECT request.requester_operator_id
          INTO owner_id
          FROM prv_support_access_requests request
         WHERE request.support_access_request_id = NEW.support_access_request_id;
        IF owner_id IS NULL THEN RETURN NEW; END IF;
    END IF;

    PERFORM 1
      FROM prv_operators operator
      JOIN prv_operator_role_assignments assignment
        ON assignment.provider_operator_id = operator.provider_operator_id
       AND assignment.lifecycle_state = 'ACTIVE'
       AND (assignment.valid_from IS NULL
            OR assignment.valid_from <= statement_timestamp())
       AND (assignment.valid_to IS NULL
            OR assignment.valid_to > statement_timestamp())
      JOIN prv_operator_roles role
        ON role.role_code = assignment.role_code
       AND role.lifecycle_state = 'ACTIVE'
      JOIN prv_operator_role_permissions permission
        ON permission.role_code = role.role_code
       AND permission.permission_code = 'SUPPORT_SESSION_WRITE'
      JOIN prv_operator_permission_catalog catalog
        ON catalog.permission_code = permission.permission_code
       AND catalog.lifecycle_state = 'ACTIVE'
       AND catalog.risk_tier = 'L3'
     WHERE operator.provider_operator_id = owner_id
       AND operator.lifecycle_state = 'ACTIVE'
       AND operator.auth_tenant_id > 0
       AND operator.auth_user_id > 0
       AND operator.role_code <> 'PROVIDER_SYSTEM_CONTAINMENT'
     FOR SHARE OF operator, assignment, role, permission, catalog;
    IF NOT FOUND THEN
        IF TG_TABLE_NAME = 'prv_support_access_requests' THEN
            RAISE EXCEPTION 'support request requester lacks effective support authority';
        END IF;
        RAISE EXCEPTION 'support session owner lacks effective support authority';
    END IF;

    PERFORM 1
      FROM prv_support_scope_catalog catalog
     WHERE catalog.scope_code = 'TENANT_EXPERIENCE_PREVIEW'
       AND catalog.lifecycle_state = 'ACTIVE'
       AND catalog.risk_tier = 'L1'
       AND catalog.requires_customer_approval
     FOR SHARE OF catalog;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'support grant requires an active exact preview scope';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prv_guard_support_activation_control()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'support activation control is an immutable singleton';
    END IF;
    IF OLD.control_key IS DISTINCT FROM NEW.control_key THEN
        RAISE EXCEPTION 'support activation control identity is immutable';
    END IF;

    -- A pulse changes only the database-owned reconciliation timestamp. The
    -- caller cannot forge the observed time or smuggle control evidence edits.
    IF OLD.activation_enabled IS NOT DISTINCT FROM NEW.activation_enabled
       AND OLD.change_reason IS NOT DISTINCT FROM NEW.change_reason
       AND OLD.change_correlation_id IS NOT DISTINCT FROM NEW.change_correlation_id
       AND OLD.changed_at IS NOT DISTINCT FROM NEW.changed_at
       AND OLD.changed_by IS NOT DISTINCT FROM NEW.changed_by
       AND OLD.version IS NOT DISTINCT FROM NEW.version THEN
        NEW.authority_reconciled_at := statement_timestamp();
        RETURN NEW;
    END IF;

    IF OLD.activation_enabled IS NOT DISTINCT FROM NEW.activation_enabled
       OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'support activation control requires one exact state transition';
    END IF;
    IF NEW.change_reason IS NULL OR LENGTH(BTRIM(NEW.change_reason)) = 0 THEN
        RAISE EXCEPTION 'support activation control changes require a reason';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM prv_operators operator
          JOIN prv_operator_role_assignments assignment
            ON assignment.provider_operator_id = operator.provider_operator_id
           AND assignment.role_code = 'PROVIDER_ADMIN'
           AND assignment.lifecycle_state = 'ACTIVE'
           AND (assignment.valid_from IS NULL
                OR assignment.valid_from <= statement_timestamp())
           AND (assignment.valid_to IS NULL
                OR assignment.valid_to > statement_timestamp())
          JOIN prv_operator_roles role
            ON role.role_code = assignment.role_code
           AND role.lifecycle_state = 'ACTIVE'
          JOIN prv_operator_role_permissions permission
            ON permission.role_code = role.role_code
           AND permission.permission_code = 'SUPPORT_ACCESS_REVIEW'
         WHERE operator.provider_operator_id = NEW.changed_by
           AND operator.lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'support activation control actor is not authorized';
    END IF;
    NEW.change_reason := BTRIM(NEW.change_reason);
    NEW.changed_at := statement_timestamp();
    NEW.authority_reconciled_at := OLD.authority_reconciled_at;
    IF NEW.change_correlation_id ~ '^[0-9A-Fa-f]{32}$'
       AND LOWER(NEW.change_correlation_id) <> repeat('0', 32) THEN
        NEW.change_correlation_id := LOWER(NEW.change_correlation_id);
    ELSIF NEW.change_correlation_id ~ '^sha256:[0-9a-f]{64}$' THEN
        NEW.change_correlation_id := NEW.change_correlation_id;
    ELSE
        NEW.change_correlation_id := replace(gen_random_uuid()::text, '-', '');
    END IF;
    RETURN NEW;
END;
$$;

-- A source mutation takes the singleton row before any authority source row.
-- The constraint trigger later pulses that already-held row at commit time.
CREATE OR REPLACE FUNCTION prv_lock_support_authority_source_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
      FROM prv_support_activation_control control
     WHERE control.control_key = 'STANDARD_JIT'
     FOR UPDATE OF control;
    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp:provider:support-containment', 0));
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_prv_00_lock_support_authority_assignment_mutation
BEFORE UPDATE OR DELETE ON prv_operator_role_assignments
FOR EACH STATEMENT EXECUTE FUNCTION prv_lock_support_authority_source_mutation();

CREATE TRIGGER trg_prv_00_lock_support_authority_operator_mutation
BEFORE UPDATE OF lifecycle_state, auth_tenant_id, auth_user_id, role_code
ON prv_operators
FOR EACH STATEMENT EXECUTE FUNCTION prv_lock_support_authority_source_mutation();

CREATE TRIGGER trg_prv_00_lock_support_authority_role_mutation
BEFORE UPDATE OR DELETE ON prv_operator_roles
FOR EACH STATEMENT EXECUTE FUNCTION prv_lock_support_authority_source_mutation();

CREATE TRIGGER trg_prv_00_lock_support_authority_permission_mutation
BEFORE UPDATE OR DELETE ON prv_operator_role_permissions
FOR EACH STATEMENT EXECUTE FUNCTION prv_lock_support_authority_source_mutation();

CREATE TRIGGER trg_prv_00_lock_support_authority_catalog_mutation
BEFORE UPDATE OF lifecycle_state, risk_tier ON prv_operator_permission_catalog
FOR EACH STATEMENT EXECUTE FUNCTION prv_lock_support_authority_source_mutation();

CREATE OR REPLACE FUNCTION prv_reconcile_support_authority_pulse()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    containment_operator_id BIGINT;
    containment_auth_user_id BIGINT;
    previous_origin TEXT;
    cancelled_count INTEGER := 0;
    revoked_count INTEGER := 0;
BEGIN
    SELECT operator.provider_operator_id, operator.auth_user_id
      INTO STRICT containment_operator_id, containment_auth_user_id
      FROM prv_operators operator
     WHERE operator.auth_tenant_id = -1
       AND operator.auth_user_id = -5100001
       AND operator.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
       AND operator.lifecycle_state = 'ACTIVE';

    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp:provider:support-containment', 0));
    previous_origin := current_setting('dwp.support_containment_origin', TRUE);
    PERFORM set_config(
        'dwp.support_containment_origin', 'authority-unavailable', TRUE);

    WITH candidates AS MATERIALIZED (
        SELECT request.support_access_request_id
          FROM prv_support_access_requests request
         WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
           AND NOT prv_operator_has_effective_support_authority(
                   request.requester_operator_id)
         ORDER BY request.support_access_request_id
         FOR UPDATE OF request
    ), cancelled AS (
        UPDATE prv_support_access_requests request
           SET lifecycle_state = 'CANCELLED',
               cancelled_at = statement_timestamp(),
               cancelled_by = containment_operator_id,
               cancellation_reason =
                   'Support owner authority became unavailable',
               cancellation_origin = 'AUTOMATIC_AUTHORITY_CONTAINMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = request.version + 1
          FROM candidates candidate
         WHERE request.support_access_request_id =
               candidate.support_access_request_id
        RETURNING request.support_access_request_id,
                  request.provider_tenant_id,
                  request.requester_operator_id,
                  request.version
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-access.cancelled-for-authority-loss',
           'SUPPORT_ACCESS_REQUEST', cancelled.support_access_request_id::TEXT,
           'SUCCESS', 'automatic:authority-unavailable',
           jsonb_build_object(
               'supportAccessRequestId', cancelled.support_access_request_id,
               'requesterOperatorId', cancelled.requester_operator_id,
               'requestVersion', cancelled.version,
               'requiredPermission', 'SUPPORT_SESSION_WRITE',
               'requiredPermissionRiskTier', 'L3',
               'authorityReconciledAt', NEW.authority_reconciled_at,
               'reason', 'Support owner authority became unavailable',
               'reasonCode', 'OWNER_SUPPORT_AUTHORITY_UNAVAILABLE',
               'transitionOrigin', 'AUTOMATIC_AUTHORITY_CONTAINMENT'),
           containment_operator_id, cancelled.provider_tenant_id,
           tenant.organization_id, 'PRIVILEGED_ACCESS'
      FROM cancelled
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = cancelled.provider_tenant_id;
    GET DIAGNOSTICS cancelled_count = ROW_COUNT;

    WITH candidates AS MATERIALIZED (
        SELECT session.support_session_id
          FROM prv_support_sessions session
         WHERE session.lifecycle_state = 'ACTIVE'
           AND NOT prv_operator_has_effective_support_authority(
                   session.provider_operator_id)
         ORDER BY session.support_session_id
         FOR UPDATE OF session
    ), revoked AS (
        UPDATE prv_support_sessions session
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = containment_operator_id,
               revocation_origin = 'AUTOMATIC_AUTHORITY_CONTAINMENT',
               updated_at = statement_timestamp(),
               updated_by = containment_operator_id,
               version = session.version + 1
          FROM candidates candidate
         WHERE session.support_session_id = candidate.support_session_id
        RETURNING session.support_session_id,
                  session.support_access_request_id,
                  session.provider_tenant_id,
                  session.provider_operator_id,
                  session.version
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), containment_auth_user_id,
           'provider.support-session.revoked-for-authority-loss',
           'SUPPORT_SESSION', revoked.support_session_id::TEXT,
           'SUCCESS', 'automatic:authority-unavailable',
           jsonb_build_object(
               'supportSessionId', revoked.support_session_id,
               'supportAccessRequestId', revoked.support_access_request_id,
               'ownerOperatorId', revoked.provider_operator_id,
               'sessionVersion', revoked.version,
               'requiredPermission', 'SUPPORT_SESSION_WRITE',
               'requiredPermissionRiskTier', 'L3',
               'authorityReconciledAt', NEW.authority_reconciled_at,
               'reason', 'Support owner authority became unavailable',
               'reasonCode', 'OWNER_SUPPORT_AUTHORITY_UNAVAILABLE',
               'transitionOrigin', 'AUTOMATIC_AUTHORITY_CONTAINMENT'),
           containment_operator_id, revoked.provider_tenant_id,
           tenant.organization_id, 'PRIVILEGED_ACCESS'
      FROM revoked
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = revoked.provider_tenant_id;
    GET DIAGNOSTICS revoked_count = ROW_COUNT;

    IF cancelled_count + revoked_count > 0 THEN
        INSERT INTO prv_audit_events (
            audit_event_id, actor_id, action, target_type, target_id, outcome,
            correlation_id, redacted_snapshot, provider_operator_id, event_category)
        VALUES (
            gen_random_uuid(), containment_auth_user_id,
            'provider.support-authority.reconciliation-pulsed',
            'SUPPORT_CONTROL', NEW.control_key, 'SUCCESS',
            'automatic:authority-reconciliation-pulse',
            jsonb_build_object(
                'authorityReconciledAt', NEW.authority_reconciled_at,
                'cancelledRequestCount', cancelled_count,
                'revokedSessionCount', revoked_count,
                'requiredPermission', 'SUPPORT_SESSION_WRITE',
                'requiredPermissionRiskTier', 'L3',
                'reason', 'Support owner authority became unavailable',
                'reasonCode', 'OWNER_SUPPORT_AUTHORITY_UNAVAILABLE',
                'transitionOrigin', 'AUTOMATIC_AUTHORITY_RECONCILIATION'),
            containment_operator_id, 'PRIVILEGED_ACCESS');
    END IF;

    PERFORM set_config(
        'dwp.support_containment_origin', COALESCE(previous_origin, ''), TRUE);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_reconcile_support_authority_pulse
AFTER UPDATE OF authority_reconciled_at ON prv_support_activation_control
FOR EACH ROW EXECUTE FUNCTION prv_reconcile_support_authority_pulse();

CREATE OR REPLACE FUNCTION prv_pulse_support_authority_after_source_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM prv_support_access_requests request
         WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVATED')
           AND NOT prv_operator_has_effective_support_authority(
                   request.requester_operator_id))
       OR EXISTS (
        SELECT 1
          FROM prv_support_sessions session
         WHERE session.lifecycle_state = 'ACTIVE'
           AND NOT prv_operator_has_effective_support_authority(
                   session.provider_operator_id)) THEN
        UPDATE prv_support_activation_control control
           SET authority_reconciled_at = statement_timestamp()
         WHERE control.control_key = 'STANDARD_JIT';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_prv_reconcile_support_authority_assignment
AFTER UPDATE OR DELETE ON prv_operator_role_assignments
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_pulse_support_authority_after_source_mutation();

CREATE CONSTRAINT TRIGGER trg_prv_reconcile_support_authority_operator
AFTER UPDATE ON prv_operators
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state
      OR OLD.auth_tenant_id IS DISTINCT FROM NEW.auth_tenant_id
      OR OLD.auth_user_id IS DISTINCT FROM NEW.auth_user_id
      OR OLD.role_code IS DISTINCT FROM NEW.role_code)
EXECUTE FUNCTION prv_pulse_support_authority_after_source_mutation();

CREATE CONSTRAINT TRIGGER trg_prv_reconcile_support_authority_role
AFTER UPDATE OR DELETE ON prv_operator_roles
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_pulse_support_authority_after_source_mutation();

CREATE CONSTRAINT TRIGGER trg_prv_reconcile_support_authority_permission
AFTER UPDATE OR DELETE ON prv_operator_role_permissions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_pulse_support_authority_after_source_mutation();

CREATE CONSTRAINT TRIGGER trg_prv_reconcile_support_authority_catalog
AFTER UPDATE ON prv_operator_permission_catalog
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state
      OR OLD.risk_tier IS DISTINCT FROM NEW.risk_tier)
EXECUTE FUNCTION prv_pulse_support_authority_after_source_mutation();

-- The first DB-authored pulse closes valid_to expiries and any authority poison
-- row that appeared after V52 committed but before these source triggers exist.
UPDATE prv_support_activation_control control
   SET authority_reconciled_at = statement_timestamp()
 WHERE control.control_key = 'STANDARD_JIT';

DO $$
DECLARE
    system_operator_id BIGINT;
BEGIN
    SELECT operator.provider_operator_id INTO STRICT system_operator_id
      FROM prv_operators operator
     WHERE operator.auth_tenant_id = -1
       AND operator.auth_user_id = -5100001;

    IF EXISTS (
        SELECT 1
          FROM prv_operator_role_assignments assignment
         WHERE assignment.provider_operator_id = system_operator_id
            OR assignment.role_code = 'PROVIDER_SYSTEM_CONTAINMENT')
       OR EXISTS (
        SELECT 1
          FROM prv_operator_role_permissions permission
         WHERE permission.role_code = 'PROVIDER_SYSTEM_CONTAINMENT') THEN
        RAISE EXCEPTION 'support containment system retained interactive authority';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM prv_support_access_requests request
         WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVATED')
           AND NOT prv_operator_has_effective_support_authority(
                   request.requester_operator_id))
       OR EXISTS (
        SELECT 1
          FROM prv_support_sessions session
         WHERE session.lifecycle_state = 'ACTIVE'
           AND NOT prv_operator_has_effective_support_authority(
                   session.provider_operator_id)) THEN
        RAISE EXCEPTION 'V53 retained an invalid active support authority grant';
    END IF;
END;
$$;

COMMENT ON COLUMN prv_support_activation_control.authority_reconciled_at IS
    'Database-authored pulse time for reconciling naturally expired or retired effective support authority.';
COMMENT ON COLUMN prv_support_access_requests.cancellation_origin IS
    'Immutable machine-readable provenance for database-owned cancellation, including effective-authority loss; NULL for manual commands.';
COMMENT ON COLUMN prv_support_sessions.revocation_origin IS
    'Immutable machine-readable provenance for database-owned revocation, including effective-authority loss; NULL for manual commands.';
