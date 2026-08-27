-- V51 is immutable after deployment. Converge its database-owned containment
-- principal to a zero-permission, non-interactive authority without rewriting
-- the applied migration or mutating already emitted audit/outbox evidence.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp:provider:support-containment', 0));

-- Quiesce every authority source, grant ledger, automatic trigger source, and
-- evidence sink in one deterministic order while the V51 privilege is retired.
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
          FROM prv_operator_roles
         WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
           AND display_name = 'Provider support containment system'
           AND description =
               'Non-login database principal for policy and tenant-state support containment.'
           AND lifecycle_state = 'ACTIVE') <> 1 THEN
        RAISE EXCEPTION 'V51 support containment system role is not canonical';
    END IF;

    SELECT provider_operator_id INTO STRICT system_operator_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND display_name = 'Provider support containment system'
       AND role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
       AND lifecycle_state = 'ACTIVE';

    IF EXISTS (
        SELECT 1
          FROM prv_operators
         WHERE provider_operator_id <> system_operator_id
           AND (auth_tenant_id = -1
                OR auth_user_id = -5100001
                OR role_code = 'PROVIDER_SYSTEM_CONTAINMENT')) THEN
        RAISE EXCEPTION 'reserved support containment identity is used by a human operator';
    END IF;

    IF (SELECT COUNT(*)
          FROM prv_operator_role_assignments
         WHERE provider_operator_id = system_operator_id) <> 1
       OR (SELECT COUNT(*)
             FROM prv_operator_role_assignments
            WHERE provider_operator_id = system_operator_id
              AND role_code = 'PROVIDER_ADMIN'
              AND lifecycle_state = 'ACTIVE'
              AND valid_from IS NULL
              AND valid_to IS NULL
              AND created_by = -5100001) <> 1
       OR EXISTS (
            SELECT 1 FROM prv_operator_role_assignments
             WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT')
       OR EXISTS (
            SELECT 1 FROM prv_operator_role_permissions
             WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT') THEN
        RAISE EXCEPTION 'V51 support containment authority edges are not canonical';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM prv_support_access_requests request
         WHERE request.cancellation_origin IS NOT NULL
           AND NOT (
               request.lifecycle_state = 'CANCELLED'
               AND request.cancelled_at IS NOT NULL
               AND request.cancelled_by = system_operator_id
               AND (
                   (request.cancellation_origin = 'AUTOMATIC_SCOPE_RETIREMENT'
                    AND EXISTS (
                        SELECT 1
                          FROM prv_support_access_request_scopes request_scope
                         WHERE request_scope.support_access_request_id =
                               request.support_access_request_id
                           AND request.cancellation_reason =
                               'Support scope retired by policy: ' || request_scope.scope_code))
                   OR (request.cancellation_origin = 'AUTOMATIC_OPERATOR_CONTAINMENT'
                       AND request.cancellation_reason =
                           'Support requester became unavailable'))
               AND (SELECT COUNT(*)
                      FROM prv_audit_events event
                     WHERE event.provider_operator_id = system_operator_id
                       AND event.actor_id = -5100001
                       AND event.target_type = 'SUPPORT_ACCESS_REQUEST'
                       AND event.target_id = request.support_access_request_id::TEXT
                       AND event.outcome = 'SUCCESS'
                       AND event.redacted_snapshot ->> 'transitionOrigin' =
                           request.cancellation_origin
                       AND event.action = CASE request.cancellation_origin
                           WHEN 'AUTOMATIC_SCOPE_RETIREMENT'
                               THEN 'provider.support-access.cancelled-by-policy'
                           ELSE 'provider.support-access.cancelled-for-operator-state'
                       END) = 1
               AND NOT EXISTS (
                   SELECT 1
                     FROM prv_audit_events event
                     JOIN sys_audit_outbox outbox
                       ON outbox.event_id = event.audit_event_id
                    WHERE event.provider_operator_id = system_operator_id
                      AND event.actor_id = -5100001
                      AND event.target_type = 'SUPPORT_ACCESS_REQUEST'
                      AND event.target_id = request.support_access_request_id::TEXT
                      AND event.redacted_snapshot ->> 'transitionOrigin' =
                          request.cancellation_origin
                      AND (outbox.payload ->> 'eventId' IS DISTINCT FROM
                               event.audit_event_id::TEXT
                           OR outbox.payload ->> 'action' IS DISTINCT FROM event.action
                           OR outbox.payload ->> 'outcome' IS DISTINCT FROM event.outcome
                           OR outbox.payload ->> 'targetType' IS DISTINCT FROM event.target_type
                           OR outbox.payload ->> 'targetId' IS DISTINCT FROM event.target_id
                           OR outbox.payload ->> 'actorId' IS DISTINCT FROM '-5100001'
                           OR ((
                               (outbox.payload ->> 'actorType' = 'USER'
                                AND outbox.payload -> 'actorRoles' =
                                    '["PROVIDER_SYSTEM_CONTAINMENT"]'::jsonb)
                               OR (outbox.payload ->> 'actorType' = 'SYSTEM'
                                   AND outbox.payload -> 'actorRoles' = '[]'::jsonb))
                               IS NOT TRUE)
                           OR outbox.payload -> 'afterState' IS DISTINCT FROM
                               COALESCE(event.redacted_snapshot, '{}'::jsonb))))) THEN
        RAISE EXCEPTION 'V51 support request containment evidence is incomplete or forged';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM prv_support_sessions session
         WHERE session.revocation_origin IS NOT NULL
           AND NOT (
               session.lifecycle_state = 'REVOKED'
               AND session.revoked_at IS NOT NULL
               AND session.revoked_by = system_operator_id
               AND (SELECT COUNT(*)
                      FROM prv_audit_events event
                     WHERE event.provider_operator_id = system_operator_id
                       AND event.actor_id = -5100001
                       AND event.target_type = 'SUPPORT_SESSION'
                       AND event.target_id = session.support_session_id::TEXT
                       AND event.outcome = 'SUCCESS'
                       AND event.redacted_snapshot ->> 'transitionOrigin' =
                           session.revocation_origin
                       AND event.action = CASE session.revocation_origin
                           WHEN 'AUTOMATIC_SCOPE_RETIREMENT'
                               THEN 'provider.support-session.revoked-by-policy'
                           WHEN 'AUTOMATIC_TENANT_CONTAINMENT'
                               THEN 'provider.support-session.revoked-for-tenant-state'
                           ELSE 'provider.support-session.revoked-for-operator-state'
                       END) = 1
               AND NOT EXISTS (
                   SELECT 1
                     FROM prv_audit_events event
                     JOIN sys_audit_outbox outbox
                       ON outbox.event_id = event.audit_event_id
                    WHERE event.provider_operator_id = system_operator_id
                      AND event.actor_id = -5100001
                      AND event.target_type = 'SUPPORT_SESSION'
                      AND event.target_id = session.support_session_id::TEXT
                      AND event.redacted_snapshot ->> 'transitionOrigin' =
                          session.revocation_origin
                      AND (outbox.payload ->> 'eventId' IS DISTINCT FROM
                               event.audit_event_id::TEXT
                           OR outbox.payload ->> 'action' IS DISTINCT FROM event.action
                           OR outbox.payload ->> 'outcome' IS DISTINCT FROM event.outcome
                           OR outbox.payload ->> 'targetType' IS DISTINCT FROM event.target_type
                           OR outbox.payload ->> 'targetId' IS DISTINCT FROM event.target_id
                           OR outbox.payload ->> 'actorId' IS DISTINCT FROM '-5100001'
                           OR ((
                               (outbox.payload ->> 'actorType' = 'USER'
                                AND outbox.payload -> 'actorRoles' =
                                    '["PROVIDER_SYSTEM_CONTAINMENT"]'::jsonb)
                               OR (outbox.payload ->> 'actorType' = 'SYSTEM'
                                   AND outbox.payload -> 'actorRoles' = '[]'::jsonb))
                               IS NOT TRUE)
                           OR outbox.payload -> 'afterState' IS DISTINCT FROM
                               COALESCE(event.redacted_snapshot, '{}'::jsonb))))) THEN
        RAISE EXCEPTION 'V51 support session containment evidence is incomplete or forged';
    END IF;
END;
$$;

CREATE TEMP TABLE tmp_v52_containment_baseline
ON COMMIT DROP
AS
SELECT operator.provider_operator_id AS system_operator_id,
       assignment.operator_role_assignment_id AS admin_assignment_id,
       history.installed_on AS v51_installed_on,
       statement_timestamp() AS v52_started_at,
       (SELECT COUNT(*)::INTEGER
          FROM prv_audit_events event
         WHERE event.provider_operator_id = operator.provider_operator_id
           AND event.redacted_snapshot ->> 'transitionOrigin' IN (
               'AUTOMATIC_SCOPE_RETIREMENT',
               'AUTOMATIC_TENANT_CONTAINMENT',
               'AUTOMATIC_OPERATOR_CONTAINMENT')) AS affected_system_event_count,
       (SELECT COUNT(*)::INTEGER
          FROM prv_audit_events event
         WHERE event.provider_operator_id = operator.provider_operator_id
           AND event.redacted_snapshot ->> 'transitionOrigin' IN (
               'AUTOMATIC_SCOPE_RETIREMENT',
               'AUTOMATIC_TENANT_CONTAINMENT',
               'AUTOMATIC_OPERATOR_CONTAINMENT')
           AND NOT EXISTS (
               SELECT 1 FROM sys_audit_outbox outbox
                WHERE outbox.event_id = event.audit_event_id))
           AS missing_or_pruned_local_outbox_count,
       (SELECT COUNT(*)::INTEGER
          FROM prv_audit_events event
          JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
         WHERE event.provider_operator_id = operator.provider_operator_id
           AND event.redacted_snapshot ->> 'transitionOrigin' IN (
               'AUTOMATIC_SCOPE_RETIREMENT',
               'AUTOMATIC_TENANT_CONTAINMENT',
               'AUTOMATIC_OPERATOR_CONTAINMENT')
           AND (outbox.payload ->> 'actorType' IS DISTINCT FROM 'SYSTEM'
                OR outbox.tenant_id <= 0)) AS misclassified_system_event_count
  FROM prv_operators operator
  JOIN prv_operator_role_assignments assignment
    ON assignment.provider_operator_id = operator.provider_operator_id
   AND assignment.role_code = 'PROVIDER_ADMIN'
   AND assignment.lifecycle_state = 'ACTIVE'
   AND assignment.valid_from IS NULL
   AND assignment.valid_to IS NULL
   AND assignment.created_by = -5100001
  JOIN flyway_schema_history history
    ON history.version = '51'
   AND history.checksum = -1480096505
   AND history.success = TRUE
 WHERE operator.auth_tenant_id = -1
   AND operator.auth_user_id = -5100001;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM tmp_v52_containment_baseline) <> 1 THEN
        RAISE EXCEPTION 'V51 applied baseline could not be proven';
    END IF;
END;
$$;

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
                      AND requester.lifecycle_state <> 'ACTIVE')));
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
                      AND operator.lifecycle_state <> 'ACTIVE')));
$$;

CREATE OR REPLACE FUNCTION prv_guard_support_containment_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    containment_operator_id BIGINT;
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
               OR NEW.cancelled_by <> containment_operator_id
               OR NOT prv_valid_automatic_request_containment(
                   NEW.support_access_request_id,
                   NEW.requester_operator_id,
                   NEW.lifecycle_state,
                   NEW.cancelled_by,
                   NEW.cancellation_reason,
                   NEW.cancellation_origin) THEN
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
               OR NEW.revoked_by <> containment_operator_id
               OR NOT prv_valid_automatic_session_containment(
                   NEW.support_session_id,
                   NEW.provider_tenant_id,
                   NEW.provider_operator_id,
                   NEW.lifecycle_state,
                   NEW.revoked_by,
                   NEW.revocation_origin) THEN
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

CREATE OR REPLACE FUNCTION prv_lock_support_grant_owner_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    owner_id BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'prv_support_access_requests' THEN
        owner_id := NEW.requester_operator_id;
    ELSE
        SELECT request.requester_operator_id
          INTO owner_id
          FROM prv_support_access_requests request
         WHERE request.support_access_request_id = NEW.support_access_request_id;
        IF owner_id IS NULL THEN
            RETURN NEW;
        END IF;
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
     WHERE operator.provider_operator_id = owner_id
       AND operator.lifecycle_state = 'ACTIVE'
     FOR SHARE OF operator, assignment, role, permission;
    IF NOT FOUND THEN
        IF TG_TABLE_NAME = 'prv_support_access_requests' THEN
            RAISE EXCEPTION 'support request requester lacks active support authority';
        END IF;
        RAISE EXCEPTION 'support session owner lacks active support authority';
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

CREATE TRIGGER trg_prv_00_lock_support_request_authority
BEFORE INSERT ON prv_support_access_requests
FOR EACH ROW EXECUTE FUNCTION prv_lock_support_grant_owner_authority();

CREATE TRIGGER trg_prv_00_lock_support_session_authority
BEFORE INSERT ON prv_support_sessions
FOR EACH ROW EXECUTE FUNCTION prv_lock_support_grant_owner_authority();

-- Preserve every V49 manual transition rule while granting the zero-permission
-- system identity one narrowly validated automatic cancellation/revocation path.
CREATE OR REPLACE FUNCTION prv_guard_support_request_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    decision_changed BOOLEAN;
    activation_changed BOOLEAN;
    completion_changed BOOLEAN;
    cancellation_changed BOOLEAN;
    review_changed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'support access request history is immutable';
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.decision_due_at := statement_timestamp() + INTERVAL '24 hours';
        NEW.created_at := statement_timestamp();
        NEW.updated_at := statement_timestamp();
        NEW.created_by := NEW.requester_operator_id;
        NEW.updated_by := NEW.requester_operator_id;
        IF NEW.lifecycle_state <> 'PENDING_APPROVAL'
           OR NEW.access_mode <> 'STANDARD'
           OR NEW.requester_auth_session_id IS NULL
           OR NEW.risk_tier <> 'L1'
           OR NOT NEW.customer_approval_required
           OR NEW.approval_reference IS NULL
           OR LENGTH(BTRIM(NEW.approval_reference)) = 0
           OR LENGTH(BTRIM(NEW.justification)) = 0
           OR NEW.duration_minutes NOT BETWEEN 5 AND 60
           OR NEW.version <> 0
           OR NEW.decided_at IS NOT NULL
           OR NEW.decided_by IS NOT NULL
           OR NEW.decision_reason IS NOT NULL
           OR NEW.activated_at IS NOT NULL
           OR NEW.completed_at IS NOT NULL
           OR NEW.cancelled_at IS NOT NULL
           OR NEW.cancelled_by IS NOT NULL
           OR NEW.cancellation_reason IS NOT NULL
           OR NEW.post_review_state <> 'NOT_REQUIRED'
           OR NEW.post_reviewed_at IS NOT NULL
           OR NEW.post_reviewed_by IS NOT NULL
           OR NEW.post_review_summary IS NOT NULL THEN
            RAISE EXCEPTION 'new support requests must start as an exact pending standard grant';
        END IF;
        IF NOT EXISTS (
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
             WHERE operator.provider_operator_id = NEW.requester_operator_id
               AND operator.lifecycle_state = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'support request requester lacks active support authority';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.support_access_request_id IS DISTINCT FROM NEW.support_access_request_id
       OR OLD.provider_tenant_id IS DISTINCT FROM NEW.provider_tenant_id
       OR OLD.requester_operator_id IS DISTINCT FROM NEW.requester_operator_id
       OR OLD.requester_auth_session_id IS DISTINCT FROM NEW.requester_auth_session_id
       OR OLD.access_mode IS DISTINCT FROM NEW.access_mode
       OR OLD.justification IS DISTINCT FROM NEW.justification
       OR OLD.duration_minutes IS DISTINCT FROM NEW.duration_minutes
       OR OLD.approval_reference IS DISTINCT FROM NEW.approval_reference
       OR OLD.customer_approval_required IS DISTINCT FROM NEW.customer_approval_required
       OR OLD.risk_tier IS DISTINCT FROM NEW.risk_tier
       OR OLD.request_key IS DISTINCT FROM NEW.request_key
       OR OLD.request_fingerprint IS DISTINCT FROM NEW.request_fingerprint
       OR OLD.decision_due_at IS DISTINCT FROM NEW.decision_due_at
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.created_by IS DISTINCT FROM NEW.created_by THEN
        RAISE EXCEPTION 'support request security metadata is immutable';
    END IF;

    IF OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state THEN
        IF NOT (
            (OLD.lifecycle_state = 'PENDING_APPROVAL'
                AND NEW.lifecycle_state IN ('APPROVED', 'DENIED', 'CANCELLED', 'EXPIRED'))
            OR (OLD.lifecycle_state = 'APPROVED'
                AND NEW.lifecycle_state IN ('ACTIVATED', 'CANCELLED', 'EXPIRED'))
            OR (OLD.lifecycle_state = 'ACTIVATED' AND NEW.lifecycle_state = 'COMPLETED')
            OR (OLD.lifecycle_state = 'COMPLETED' AND NEW.lifecycle_state = 'REVIEWED')
        ) THEN
            RAISE EXCEPTION 'invalid support request lifecycle transition: % -> %',
                OLD.lifecycle_state, NEW.lifecycle_state;
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'support request lifecycle transition must advance version exactly once';
        END IF;
    ELSE
        RAISE EXCEPTION 'support request updates require a lifecycle transition';
    END IF;

    IF OLD.lifecycle_state = 'PENDING_APPROVAL'
       AND NEW.lifecycle_state IN ('APPROVED', 'DENIED') THEN
        NEW.decided_at := statement_timestamp();
        NEW.updated_by := NEW.decided_by;
    END IF;
    IF OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'ACTIVATED' THEN
        NEW.activated_at := statement_timestamp();
        NEW.updated_by := OLD.requester_operator_id;
    END IF;
    IF OLD.lifecycle_state = 'ACTIVATED' AND NEW.lifecycle_state = 'COMPLETED' THEN
        NEW.completed_at := statement_timestamp();
    END IF;
    IF OLD.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
       AND NEW.lifecycle_state = 'CANCELLED' THEN
        NEW.cancelled_at := statement_timestamp();
        NEW.updated_by := NEW.cancelled_by;
    END IF;
    IF OLD.lifecycle_state = 'COMPLETED' AND NEW.lifecycle_state = 'REVIEWED' THEN
        NEW.post_reviewed_at := statement_timestamp();
        NEW.updated_by := NEW.post_reviewed_by;
    END IF;
    IF NEW.lifecycle_state = 'EXPIRED' THEN
        NEW.updated_by := OLD.requester_operator_id;
    END IF;
    NEW.updated_at := statement_timestamp();

    decision_changed := OLD.decided_at IS DISTINCT FROM NEW.decided_at
        OR OLD.decided_by IS DISTINCT FROM NEW.decided_by
        OR OLD.decision_reason IS DISTINCT FROM NEW.decision_reason;
    activation_changed := OLD.activated_at IS DISTINCT FROM NEW.activated_at;
    completion_changed := OLD.completed_at IS DISTINCT FROM NEW.completed_at;
    cancellation_changed := OLD.cancelled_at IS DISTINCT FROM NEW.cancelled_at
        OR OLD.cancelled_by IS DISTINCT FROM NEW.cancelled_by
        OR OLD.cancellation_reason IS DISTINCT FROM NEW.cancellation_reason;
    review_changed := OLD.post_review_state IS DISTINCT FROM NEW.post_review_state
        OR OLD.post_reviewed_at IS DISTINCT FROM NEW.post_reviewed_at
        OR OLD.post_reviewed_by IS DISTINCT FROM NEW.post_reviewed_by
        OR OLD.post_review_summary IS DISTINCT FROM NEW.post_review_summary;

    IF decision_changed AND NOT (
        OLD.lifecycle_state = 'PENDING_APPROVAL'
        AND NEW.lifecycle_state IN ('APPROVED', 'DENIED')
        AND statement_timestamp() < OLD.decision_due_at
        AND OLD.decided_at IS NULL AND OLD.decided_by IS NULL
        AND OLD.decision_reason IS NULL
        AND NEW.decided_at IS NOT NULL AND NEW.decided_by IS NOT NULL
        AND NEW.decided_by <> OLD.requester_operator_id
        AND EXISTS (
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
               AND permission.permission_code = 'SUPPORT_ACCESS_REVIEW'
             WHERE operator.provider_operator_id = NEW.decided_by
               AND operator.lifecycle_state = 'ACTIVE')
        AND NEW.decision_reason IS NOT NULL
        AND LENGTH(BTRIM(NEW.decision_reason)) > 0
    ) THEN
        RAISE EXCEPTION 'support request decision evidence is immutable';
    END IF;
    IF activation_changed AND NOT (
        OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'ACTIVATED'
        AND statement_timestamp() < OLD.decision_due_at
        AND OLD.activated_at IS NULL AND NEW.activated_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'support request activation evidence is immutable';
    END IF;
    IF completion_changed AND NOT (
        OLD.lifecycle_state = 'ACTIVATED' AND NEW.lifecycle_state = 'COMPLETED'
        AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'support request completion evidence is immutable';
    END IF;
    IF cancellation_changed AND NOT (
        OLD.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
        AND NEW.lifecycle_state = 'CANCELLED'
        AND OLD.cancelled_at IS NULL AND OLD.cancelled_by IS NULL
        AND OLD.cancellation_reason IS NULL
        AND NEW.cancelled_at IS NOT NULL AND NEW.cancelled_by IS NOT NULL
        AND (
            prv_valid_automatic_request_containment(
                NEW.support_access_request_id,
                NEW.requester_operator_id,
                NEW.lifecycle_state,
                NEW.cancelled_by,
                NEW.cancellation_reason,
                NEW.cancellation_origin)
            OR (
                EXISTS (
                    SELECT 1 FROM prv_operators operator
                     WHERE operator.provider_operator_id = NEW.cancelled_by
                       AND operator.lifecycle_state = 'ACTIVE')
                AND (
                    NEW.cancelled_by = OLD.requester_operator_id
                    OR EXISTS (
                        SELECT 1
                          FROM prv_operator_role_assignments assignment
                          JOIN prv_operator_roles role
                            ON role.role_code = assignment.role_code
                           AND role.lifecycle_state = 'ACTIVE'
                          JOIN prv_operator_role_permissions permission
                            ON permission.role_code = role.role_code
                           AND permission.permission_code = 'SUPPORT_ACCESS_REVIEW'
                         WHERE assignment.provider_operator_id = NEW.cancelled_by
                           AND assignment.lifecycle_state = 'ACTIVE'
                           AND (assignment.valid_from IS NULL
                                OR assignment.valid_from <= statement_timestamp())
                           AND (assignment.valid_to IS NULL
                                OR assignment.valid_to > statement_timestamp())))))
        AND NEW.cancellation_reason IS NOT NULL
        AND LENGTH(BTRIM(NEW.cancellation_reason)) > 0
    ) THEN
        RAISE EXCEPTION 'support request cancellation evidence is immutable';
    END IF;
    IF review_changed AND NOT (
        (OLD.lifecycle_state = 'ACTIVATED' AND NEW.lifecycle_state = 'COMPLETED'
            AND OLD.post_review_state = 'NOT_REQUIRED'
            AND NEW.post_review_state = 'PENDING'
            AND NEW.post_reviewed_at IS NULL AND NEW.post_reviewed_by IS NULL
            AND NEW.post_review_summary IS NULL)
        OR (OLD.lifecycle_state = 'COMPLETED' AND NEW.lifecycle_state = 'REVIEWED'
            AND OLD.post_review_state = 'PENDING'
            AND NEW.post_review_state = 'COMPLETED'
            AND NEW.post_reviewed_at IS NOT NULL
            AND NEW.post_reviewed_by IS NOT NULL
            AND NEW.post_reviewed_by <> OLD.requester_operator_id
            AND EXISTS (
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
                   AND permission.permission_code = 'SUPPORT_POST_REVIEW'
                 WHERE operator.provider_operator_id = NEW.post_reviewed_by
                   AND operator.lifecycle_state = 'ACTIVE')
            AND NEW.post_reviewed_at >= OLD.completed_at
            AND NEW.post_review_summary IS NOT NULL
            AND LENGTH(BTRIM(NEW.post_review_summary)) > 0)
    ) THEN
        RAISE EXCEPTION 'support request post-review evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prv_guard_support_session_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    request_row RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'support session history is immutable';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT request.*
          INTO request_row
          FROM prv_support_access_requests request
         WHERE request.support_access_request_id = NEW.support_access_request_id
           AND request.lifecycle_state = 'APPROVED'
           AND request.access_mode = 'STANDARD'
           AND request.risk_tier = 'L1'
           AND request.customer_approval_required
           AND request.requester_auth_session_id IS NOT NULL
           AND request.decided_by IS NOT NULL
           AND request.decided_by <> request.requester_operator_id
           AND statement_timestamp() < request.decision_due_at
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'support session requires an existing approved request';
        END IF;
        IF NOT EXISTS (
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
             WHERE operator.provider_operator_id = request_row.requester_operator_id
               AND operator.lifecycle_state = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'support session owner lacks active support authority';
        END IF;
        NEW.provider_tenant_id := request_row.provider_tenant_id;
        NEW.provider_operator_id := request_row.requester_operator_id;
        NEW.justification := request_row.justification;
        NEW.access_mode := request_row.access_mode;
        NEW.approval_reference := request_row.approval_reference;
        NEW.customer_approval_required := request_row.customer_approval_required;
        NEW.risk_tier := request_row.risk_tier;
        NEW.origin_auth_session_id := request_row.requester_auth_session_id;
        NEW.started_at := statement_timestamp();
        NEW.last_used_at := statement_timestamp();
        NEW.expires_at := statement_timestamp()
            + make_interval(mins => request_row.duration_minutes);
        NEW.created_at := statement_timestamp();
        NEW.updated_at := statement_timestamp();
        NEW.created_by := NEW.provider_operator_id;
        NEW.updated_by := NEW.provider_operator_id;
        IF NEW.lifecycle_state <> 'ACTIVE'
           OR NEW.support_access_request_id IS NULL
           OR NEW.origin_auth_session_id IS NULL
           OR NEW.access_mode <> 'STANDARD'
           OR NEW.risk_tier <> 'L1'
           OR NOT NEW.customer_approval_required
           OR NEW.approval_reference IS NULL
           OR LENGTH(BTRIM(NEW.approval_reference)) = 0
           OR LENGTH(BTRIM(NEW.justification)) = 0
           OR NEW.version <> 0
           OR NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL THEN
            RAISE EXCEPTION 'new support sessions must start as an exact active standard grant';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.support_session_id IS DISTINCT FROM NEW.support_session_id
       OR OLD.provider_tenant_id IS DISTINCT FROM NEW.provider_tenant_id
       OR OLD.provider_operator_id IS DISTINCT FROM NEW.provider_operator_id
       OR OLD.support_access_request_id IS DISTINCT FROM NEW.support_access_request_id
       OR OLD.justification IS DISTINCT FROM NEW.justification
       OR OLD.token_hash IS DISTINCT FROM NEW.token_hash
       OR OLD.started_at IS DISTINCT FROM NEW.started_at
       OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
       OR OLD.access_mode IS DISTINCT FROM NEW.access_mode
       OR OLD.approval_reference IS DISTINCT FROM NEW.approval_reference
       OR OLD.customer_approval_required IS DISTINCT FROM NEW.customer_approval_required
       OR OLD.risk_tier IS DISTINCT FROM NEW.risk_tier
       OR OLD.origin_auth_session_id IS DISTINCT FROM NEW.origin_auth_session_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.created_by IS DISTINCT FROM NEW.created_by THEN
        RAISE EXCEPTION 'support session security metadata is immutable';
    END IF;

    IF OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state THEN
        IF OLD.lifecycle_state <> 'ACTIVE'
           OR NEW.lifecycle_state NOT IN ('REVOKED', 'EXPIRED') THEN
            RAISE EXCEPTION 'invalid support session lifecycle transition: % -> %',
                OLD.lifecycle_state, NEW.lifecycle_state;
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'support session lifecycle transition must advance version exactly once';
        END IF;
        IF NEW.lifecycle_state = 'REVOKED' THEN
            NEW.revoked_at := statement_timestamp();
        END IF;
        IF NEW.lifecycle_state = 'REVOKED' AND (
            OLD.revoked_at IS NOT NULL OR OLD.revoked_by IS NOT NULL
            OR NEW.revoked_at IS NULL OR NEW.revoked_by IS NULL) THEN
            RAISE EXCEPTION 'support session revocation evidence is invalid';
        END IF;
        IF NEW.lifecycle_state = 'REVOKED' AND NOT (
            prv_valid_automatic_session_containment(
                NEW.support_session_id,
                NEW.provider_tenant_id,
                NEW.provider_operator_id,
                NEW.lifecycle_state,
                NEW.revoked_by,
                NEW.revocation_origin)
            OR (
                EXISTS (
                    SELECT 1 FROM prv_operators operator
                     WHERE operator.provider_operator_id = NEW.revoked_by
                       AND operator.lifecycle_state = 'ACTIVE')
                AND (
                    NEW.revoked_by = OLD.provider_operator_id
                    OR EXISTS (
                        SELECT 1
                          FROM prv_operator_role_assignments assignment
                          JOIN prv_operator_roles role
                            ON role.role_code = assignment.role_code
                           AND role.lifecycle_state = 'ACTIVE'
                         WHERE assignment.provider_operator_id = NEW.revoked_by
                           AND assignment.role_code = 'PROVIDER_ADMIN'
                           AND assignment.lifecycle_state = 'ACTIVE'
                           AND (assignment.valid_from IS NULL
                                OR assignment.valid_from <= statement_timestamp())
                           AND (assignment.valid_to IS NULL
                                OR assignment.valid_to > statement_timestamp()))))
        ) THEN
            RAISE EXCEPTION 'support session revocation actor is not authorized';
        END IF;
        IF NEW.lifecycle_state = 'EXPIRED'
           AND (NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL) THEN
            RAISE EXCEPTION 'expired support sessions cannot carry revocation evidence';
        END IF;
        IF NEW.lifecycle_state = 'REVOKED' THEN
            NEW.updated_by := NEW.revoked_by;
        ELSE
            NEW.updated_by := OLD.provider_operator_id;
        END IF;
        NEW.last_used_at := OLD.last_used_at;
    ELSE
        IF OLD.lifecycle_state <> 'ACTIVE' THEN
            RAISE EXCEPTION 'terminal support sessions are immutable';
        END IF;
        IF OLD.revoked_at IS DISTINCT FROM NEW.revoked_at
           OR OLD.revoked_by IS DISTINCT FROM NEW.revoked_by
           OR NEW.version IS DISTINCT FROM OLD.version
           OR statement_timestamp() >= OLD.expires_at
           OR OLD.last_used_at <= statement_timestamp() - INTERVAL '15 minutes' THEN
            RAISE EXCEPTION 'support session terminal evidence is immutable';
        END IF;
        NEW.last_used_at := statement_timestamp();
        NEW.updated_by := OLD.provider_operator_id;
    END IF;
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;


-- Preserve machine actor semantics in the enterprise audit envelope. Automatic
-- containment is never projected as an interactive user or role assignment.
CREATE OR REPLACE FUNCTION sys_provider_audit_to_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_tenant_id BIGINT;
    actor_display_name VARCHAR(200);
    actor_role VARCHAR(50);
    actor_auth_tenant_id BIGINT;
    actor_auth_user_id BIGINT;
BEGIN
    SELECT COALESCE(
               CASE WHEN tenant.auth_tenant_id > 0 THEN tenant.auth_tenant_id END,
               CASE WHEN actor.auth_tenant_id > 0 THEN actor.auth_tenant_id END,
               1),
           actor.display_name,
           actor.role_code,
           actor.auth_tenant_id,
           actor.auth_user_id
      INTO resolved_tenant_id, actor_display_name, actor_role,
           actor_auth_tenant_id, actor_auth_user_id
      FROM (SELECT 1) anchor
      LEFT JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = NEW.provider_tenant_id
      LEFT JOIN prv_operators actor
        ON actor.provider_operator_id = NEW.provider_operator_id;

    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), NEW.audit_event_id, resolved_tenant_id,
        jsonb_build_object(
            'eventId', NEW.audit_event_id,
            'eventVersion', '1.0',
            'occurredAt', NEW.occurred_at,
            'tenantId', resolved_tenant_id,
            'category', CASE
                WHEN NEW.event_category IN ('PROVISIONING', 'ONBOARDING')
                    OR NEW.action LIKE 'provider.tenant.%' THEN 'PROVISIONING'
                WHEN NEW.event_category IN ('SUPPORT', 'SECURITY', 'PRIVILEGED_ACCESS')
                    OR NEW.action LIKE 'provider.support-%' THEN 'AUTHORIZATION'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', NEW.outcome,
            'severity', CASE
                WHEN NEW.outcome = 'FAILED' THEN 'HIGH'
                WHEN NEW.outcome = 'DENIED' THEN 'MEDIUM'
                WHEN NEW.action LIKE 'provider.support-%' THEN 'MEDIUM'
                ELSE 'INFO'
            END,
            'riskScore', CASE
                WHEN NEW.outcome = 'FAILED' THEN 78
                WHEN NEW.outcome = 'DENIED' THEN 62
                WHEN NEW.action LIKE 'provider.support-%' THEN 55
                WHEN NEW.action LIKE '%delete%' OR NEW.action LIKE '%retire%' THEN 48
                ELSE 20
            END,
            'actorType', CASE
                WHEN actor_auth_tenant_id = -1
                 AND actor_auth_user_id = -5100001
                 AND actor_role = 'PROVIDER_SYSTEM_CONTAINMENT' THEN 'SYSTEM'
                ELSE 'USER'
            END,
            'actorId', NEW.actor_id::TEXT,
            'actorDisplayName', actor_display_name,
            'actorRoles', CASE
                WHEN actor_auth_tenant_id = -1
                 AND actor_auth_user_id = -5100001
                 AND actor_role = 'PROVIDER_SYSTEM_CONTAINMENT' THEN '[]'::jsonb
                WHEN actor_role IS NULL THEN '[]'::jsonb
                ELSE jsonb_build_array(actor_role)
            END,
            'sourceService', 'dwp-provider-server',
            'sourceModule', 'provider-control-plane',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', NEW.target_type,
            'targetId', NEW.target_id,
            'targetDisplayName', NEW.target_id,
            'correlationId', NEW.correlation_id,
            'beforeState', '{}'::jsonb,
            'afterState', COALESCE(NEW.redacted_snapshot, '{}'::jsonb),
            'metadata', jsonb_strip_nulls(jsonb_build_object(
                'legacyAuditEventId', NEW.audit_event_id,
                'providerTenantId', NEW.provider_tenant_id,
                'organizationId', NEW.organization_id,
                'providerEventCategory', NEW.event_category,
                'providerActorKind', CASE
                    WHEN actor_auth_tenant_id = -1
                     AND actor_auth_user_id = -5100001
                     AND actor_role = 'PROVIDER_SYSTEM_CONTAINMENT'
                        THEN 'SYSTEM_CONTAINMENT'
                    ELSE NULL
                END,
                'systemPrincipal', CASE
                    WHEN actor_auth_tenant_id = -1
                     AND actor_auth_user_id = -5100001
                     AND actor_role = 'PROVIDER_SYSTEM_CONTAINMENT'
                        THEN 'provider-support-containment'
                    ELSE NULL
                END,
                'transitionOrigin', NEW.redacted_snapshot ->> 'transitionOrigin')),
            'retentionClass', CASE
                WHEN NEW.event_category IN ('SUPPORT', 'SECURITY', 'PRIVILEGED_ACCESS')
                    OR NEW.action LIKE 'provider.support-%' THEN 'EXTENDED'
                ELSE 'STANDARD'
            END),
        'PENDING', 0, statement_timestamp(), statement_timestamp(), statement_timestamp())
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;
-- Serialize every bulk containment path before it mutates the shared grant ledger.
CREATE OR REPLACE FUNCTION prv_apply_support_activation_control()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_actor_id BIGINT;
BEGIN
    IF OLD.activation_enabled IS NOT DISTINCT FROM NEW.activation_enabled THEN
        RETURN NEW;
    END IF;
    IF NEW.changed_by IS NULL OR NEW.change_reason IS NULL
       OR LENGTH(BTRIM(NEW.change_reason)) = 0 THEN
        RAISE EXCEPTION 'support activation control changes require actor and reason';
    END IF;

    SELECT auth_user_id INTO resolved_actor_id
      FROM prv_operators WHERE provider_operator_id = NEW.changed_by;

    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp:provider:support-containment', 0));

    IF NOT NEW.activation_enabled THEN
        WITH revoked AS (
            UPDATE prv_support_sessions session
               SET lifecycle_state = 'REVOKED',
                   revoked_at = statement_timestamp(),
                   revoked_by = NEW.changed_by,
                   updated_at = statement_timestamp(),
                   updated_by = NEW.changed_by,
                   version = session.version + 1
             WHERE session.lifecycle_state = 'ACTIVE'
            RETURNING session.support_session_id,
                      session.support_access_request_id,
                      session.provider_tenant_id,
                      session.provider_operator_id,
                      session.expires_at,
                      session.last_used_at,
                      session.version
        )
        INSERT INTO prv_audit_events (
            audit_event_id, actor_id, action, target_type, target_id, outcome,
            correlation_id, redacted_snapshot, provider_operator_id,
            provider_tenant_id, organization_id, event_category)
        SELECT gen_random_uuid(), resolved_actor_id,
               'provider.support-session.revoked-by-kill-switch',
               'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
               COALESCE(NEW.change_correlation_id, 'control:support-activation-disabled'),
               jsonb_strip_nulls(jsonb_build_object(
                   'supportSessionId', revoked.support_session_id,
                   'supportAccessRequestId', revoked.support_access_request_id,
                   'absoluteExpiresAt', revoked.expires_at,
                   'lastUsedAt', revoked.last_used_at,
                   'sessionVersion', revoked.version,
                   'reasonCode', 'SUPPORT_KILL_SWITCH_DISABLED')),
               revoked.provider_operator_id, revoked.provider_tenant_id,
               tenant.organization_id, 'PRIVILEGED_ACCESS'
          FROM revoked
          JOIN prv_tenants tenant
            ON tenant.provider_tenant_id = revoked.provider_tenant_id;

        UPDATE prv_support_access_requests request
           SET lifecycle_state = 'COMPLETED',
               completed_at = statement_timestamp(),
               post_review_state = 'PENDING',
               updated_at = statement_timestamp(),
               updated_by = NEW.changed_by,
               version = request.version + 1
         WHERE request.lifecycle_state = 'ACTIVATED'
           AND EXISTS (
               SELECT 1 FROM prv_support_sessions session
                WHERE session.support_access_request_id = request.support_access_request_id
                  AND session.lifecycle_state = 'REVOKED');
    END IF;

    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id, event_category)
    VALUES (
        gen_random_uuid(), resolved_actor_id,
        CASE WHEN NEW.activation_enabled
            THEN 'provider.support-activation.enabled-by-db-control'
            ELSE 'provider.support-activation.disabled-by-db-control' END,
        'SUPPORT_CONTROL', NEW.control_key, 'SUCCESS',
        COALESCE(NEW.change_correlation_id, 'control:support-activation-change'),
        jsonb_build_object(
            'enabled', NEW.activation_enabled,
            'reason', NEW.change_reason,
            'controlVersion', NEW.version),
        NEW.changed_by, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;


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
    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp:provider:support-containment', 0));
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
    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp:provider:support-containment', 0));
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
    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp:provider:support-containment', 0));
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

ALTER TABLE prv_operators
    DROP CONSTRAINT ck_prv_support_containment_system_identity,
    ADD CONSTRAINT ck_prv_support_containment_system_identity
    CHECK (
        (auth_tenant_id = -1 AND auth_user_id = -5100001
            AND display_name = 'Provider support containment system'
            AND role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
            AND lifecycle_state = 'ACTIVE')
        OR (auth_tenant_id <> -1 AND auth_user_id <> -5100001
            AND role_code <> 'PROVIDER_SYSTEM_CONTAINMENT'));

-- Preserve the WORM boundary: do not rewrite V51 USER/tenant misclassification.
-- Append a SYSTEM compensation event for every affected automatic transition.
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category)
SELECT gen_random_uuid(), -5100001,
       'provider.support-containment.audit-classification-compensated',
       'AUDIT_EVENT', event.audit_event_id::TEXT, 'SUCCESS',
       'migration:v52:audit-compensation:' || event.audit_event_id::TEXT,
       jsonb_build_object(
           'originalAuditEventId', event.audit_event_id,
           'originalAction', event.action,
           'originalOutboxStatus', outbox.status,
           'originalActorType', outbox.payload ->> 'actorType',
           'originalTenantPartition', outbox.tenant_id,
           'v51InstalledAt', baseline.v51_installed_on,
           'v52StartedAt', baseline.v52_started_at,
           'reasonCode', 'SYSTEM_ACTOR_CLASSIFICATION_COMPENSATION',
           'transitionOrigin', 'AUTOMATIC_AUDIT_CLASSIFICATION_COMPENSATION'),
       baseline.system_operator_id, event.provider_tenant_id,
       event.organization_id, 'PRIVILEGED_ACCESS'
  FROM prv_audit_events event
  JOIN sys_audit_outbox outbox ON outbox.event_id = event.audit_event_id
  JOIN tmp_v52_containment_baseline baseline
    ON baseline.system_operator_id = event.provider_operator_id
 WHERE event.redacted_snapshot ->> 'transitionOrigin' IN (
           'AUTOMATIC_SCOPE_RETIREMENT',
           'AUTOMATIC_TENANT_CONTAINMENT',
           'AUTOMATIC_OPERATOR_CONTAINMENT')
   AND (outbox.payload ->> 'actorType' IS DISTINCT FROM 'SYSTEM'
        OR outbox.tenant_id <= 0);

-- Retire exactly the assignment created by the immutable V51 and append the
-- privilege-exposure closure evidence in the same statement and transaction.
WITH removed_assignment AS (
    DELETE FROM prv_operator_role_assignments assignment
     USING tmp_v52_containment_baseline baseline
     WHERE assignment.operator_role_assignment_id = baseline.admin_assignment_id
       AND assignment.provider_operator_id = baseline.system_operator_id
       AND assignment.role_code = 'PROVIDER_ADMIN'
    RETURNING assignment.operator_role_assignment_id, assignment.role_code
)
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id, event_category)
SELECT gen_random_uuid(), -5100001,
       'provider.support-containment.v51-admin-authority-retired',
       'SYSTEM_PRINCIPAL', 'provider-support-containment', 'SUCCESS',
       'migration:v52:containment-authority-retirement',
       jsonb_build_object(
           'removedAssignmentId', removed.operator_role_assignment_id,
           'removedRoleCode', removed.role_code,
           'v51InstalledAt', baseline.v51_installed_on,
           'v52StartedAt', baseline.v52_started_at,
           'exposureWindowClosedAt', statement_timestamp(),
           'affectedSystemEventCount', baseline.affected_system_event_count,
           'misclassifiedSystemEventCount', baseline.misclassified_system_event_count,
           'missingOrPrunedLocalOutboxCount',
               baseline.missing_or_pruned_local_outbox_count,
           'reasonCode', 'ZERO_INTERACTIVE_AUTHORITY',
           'transitionOrigin', 'AUTOMATIC_MIGRATION_HARDENING'),
       baseline.system_operator_id, 'PRIVILEGED_ACCESS'
  FROM removed_assignment removed
  CROSS JOIN tmp_v52_containment_baseline baseline;

CREATE OR REPLACE FUNCTION prv_guard_support_containment_system_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    system_operator_id BIGINT;
    affected_operator_id BIGINT;
    affected_role_code VARCHAR(80);
BEGIN
    SELECT provider_operator_id INTO STRICT system_operator_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
       AND lifecycle_state = 'ACTIVE';

    IF TG_TABLE_NAME = 'prv_operators' THEN
        affected_operator_id := CASE WHEN TG_OP = 'INSERT'
            THEN NEW.provider_operator_id ELSE OLD.provider_operator_id END;
        IF affected_operator_id = system_operator_id
           OR (TG_OP <> 'DELETE' AND (
               NEW.auth_tenant_id = -1
               OR NEW.auth_user_id = -5100001
               OR NEW.role_code = 'PROVIDER_SYSTEM_CONTAINMENT')) THEN
            RAISE EXCEPTION 'support containment system identity is immutable';
        END IF;
    ELSIF TG_TABLE_NAME = 'prv_operator_roles' THEN
        affected_role_code := OLD.role_code;
        IF affected_role_code = 'PROVIDER_SYSTEM_CONTAINMENT' THEN
            RAISE EXCEPTION 'support containment system role is immutable';
        END IF;
    ELSIF TG_TABLE_NAME = 'prv_operator_role_assignments' THEN
        affected_operator_id := CASE WHEN TG_OP = 'DELETE'
            THEN OLD.provider_operator_id ELSE NEW.provider_operator_id END;
        affected_role_code := CASE WHEN TG_OP = 'DELETE'
            THEN OLD.role_code ELSE NEW.role_code END;
        IF affected_operator_id = system_operator_id
           OR affected_role_code = 'PROVIDER_SYSTEM_CONTAINMENT' THEN
            RAISE EXCEPTION 'support containment system identity cannot receive role assignments';
        END IF;
    ELSIF TG_TABLE_NAME = 'prv_operator_role_permissions' THEN
        affected_role_code := CASE WHEN TG_OP = 'DELETE'
            THEN OLD.role_code ELSE NEW.role_code END;
        IF affected_role_code = 'PROVIDER_SYSTEM_CONTAINMENT' THEN
            RAISE EXCEPTION 'support containment system role cannot receive permissions';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_prv_guard_support_containment_system_operator
BEFORE INSERT OR UPDATE OR DELETE ON prv_operators
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_containment_system_authority();

CREATE TRIGGER trg_prv_guard_support_containment_system_role
BEFORE UPDATE OR DELETE ON prv_operator_roles
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_containment_system_authority();

CREATE TRIGGER trg_prv_guard_support_containment_system_assignment
BEFORE INSERT OR UPDATE OR DELETE ON prv_operator_role_assignments
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_containment_system_authority();

CREATE TRIGGER trg_prv_guard_support_containment_system_permission
BEFORE INSERT OR UPDATE OR DELETE ON prv_operator_role_permissions
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_containment_system_authority();
-- Reconcile poison rows that pre-date this forward migration. A no-op update
-- intentionally invokes the idempotent containment trigger for each inactive
-- human operator without changing operator lifecycle evidence.
UPDATE prv_operators
   SET lifecycle_state = lifecycle_state
 WHERE lifecycle_state <> 'ACTIVE'
   AND NOT (auth_tenant_id = -1 AND auth_user_id = -5100001);


DO $$
DECLARE
    system_operator_id BIGINT;
BEGIN
    SELECT provider_operator_id INTO STRICT system_operator_id
      FROM prv_operators
     WHERE auth_tenant_id = -1
       AND auth_user_id = -5100001
       AND display_name = 'Provider support containment system'
       AND role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
       AND lifecycle_state = 'ACTIVE';

    IF EXISTS (
        SELECT 1 FROM prv_operator_role_assignments
         WHERE provider_operator_id = system_operator_id
            OR role_code = 'PROVIDER_SYSTEM_CONTAINMENT')
       OR EXISTS (
        SELECT 1 FROM prv_operator_role_permissions
         WHERE role_code = 'PROVIDER_SYSTEM_CONTAINMENT') THEN
        RAISE EXCEPTION 'support containment system retained interactive authority';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM prv_support_access_requests request
         WHERE request.lifecycle_state IN (
                   'PENDING_APPROVAL', 'APPROVED', 'ACTIVATED')
           AND (
               NOT EXISTS (
                   SELECT 1 FROM prv_operators owner
                    WHERE owner.provider_operator_id = request.requester_operator_id
                      AND owner.lifecycle_state = 'ACTIVE')
               OR NOT EXISTS (
                   SELECT 1
                     FROM prv_operator_role_assignments assignment
                     JOIN prv_operator_roles role ON role.role_code = assignment.role_code
                     JOIN prv_operator_role_permissions permission
                       ON permission.role_code = role.role_code
                    WHERE assignment.provider_operator_id = request.requester_operator_id
                      AND assignment.lifecycle_state = 'ACTIVE'
                      AND (assignment.valid_from IS NULL
                           OR assignment.valid_from <= statement_timestamp())
                      AND (assignment.valid_to IS NULL
                           OR assignment.valid_to > statement_timestamp())
                      AND role.lifecycle_state = 'ACTIVE'
                      AND permission.permission_code = 'SUPPORT_SESSION_WRITE')
               OR (SELECT COUNT(*) FROM prv_support_access_request_scopes request_scope
                    WHERE request_scope.support_access_request_id =
                          request.support_access_request_id) <> 1
               OR NOT EXISTS (
                   SELECT 1
                     FROM prv_support_access_request_scopes request_scope
                     JOIN prv_support_scope_catalog catalog
                       ON catalog.scope_code = request_scope.scope_code
                    WHERE request_scope.support_access_request_id =
                          request.support_access_request_id
                      AND catalog.scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                      AND catalog.lifecycle_state = 'ACTIVE'
                      AND catalog.risk_tier = 'L1'
                      AND catalog.requires_customer_approval))) THEN
        RAISE EXCEPTION 'V52 found an invalid active support request grant';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM prv_support_sessions session
         WHERE session.lifecycle_state = 'ACTIVE'
           AND (
               NOT EXISTS (
                   SELECT 1 FROM prv_operators owner
                    WHERE owner.provider_operator_id = session.provider_operator_id
                      AND owner.lifecycle_state = 'ACTIVE')
               OR NOT EXISTS (
                   SELECT 1 FROM prv_tenants tenant
                    WHERE tenant.provider_tenant_id = session.provider_tenant_id
                      AND tenant.lifecycle_state = 'ACTIVE'
                      AND tenant.onboarding_state = 'READY'
                      AND tenant.auth_tenant_id IS NOT NULL)
               OR NOT EXISTS (
                   SELECT 1
                     FROM prv_operator_role_assignments assignment
                     JOIN prv_operator_roles role ON role.role_code = assignment.role_code
                     JOIN prv_operator_role_permissions permission
                       ON permission.role_code = role.role_code
                    WHERE assignment.provider_operator_id = session.provider_operator_id
                      AND assignment.lifecycle_state = 'ACTIVE'
                      AND (assignment.valid_from IS NULL
                           OR assignment.valid_from <= statement_timestamp())
                      AND (assignment.valid_to IS NULL
                           OR assignment.valid_to > statement_timestamp())
                      AND role.lifecycle_state = 'ACTIVE'
                      AND permission.permission_code = 'SUPPORT_SESSION_WRITE')
               OR (SELECT COUNT(*) FROM prv_support_session_scopes session_scope
                    WHERE session_scope.support_session_id =
                          session.support_session_id) <> 1
               OR NOT EXISTS (
                   SELECT 1
                     FROM prv_support_session_scopes session_scope
                     JOIN prv_support_scope_catalog catalog
                       ON catalog.scope_code = session_scope.scope_code
                    WHERE session_scope.support_session_id = session.support_session_id
                      AND catalog.scope_code = 'TENANT_EXPERIENCE_PREVIEW'
                      AND catalog.lifecycle_state = 'ACTIVE'
                      AND catalog.risk_tier = 'L1'
                      AND catalog.requires_customer_approval))) THEN
        RAISE EXCEPTION 'V52 found an invalid active support session grant';
    END IF;
END;
$$;
