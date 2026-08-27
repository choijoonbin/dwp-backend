-- V48 bound support activation to the login session that created the request.
-- V49 makes the complete request/session grant immutable and bidirectional.
-- Existing executable rows cannot prove that they were created under this
-- stronger contract, so they are terminated instead of grandfathered.
WITH revoked AS (
    UPDATE prv_support_sessions session
       SET lifecycle_state = 'REVOKED',
           revoked_at = statement_timestamp(),
           revoked_by = session.provider_operator_id,
           updated_at = statement_timestamp(),
           updated_by = session.provider_operator_id,
           version = session.version + 1
     WHERE session.lifecycle_state = 'ACTIVE'
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
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.support-session.revoked-by-exact-grant-migration',
       'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
       'migration:v49:exact-support-grant',
       jsonb_build_object(
           'supportSessionId', revoked.support_session_id,
           'supportAccessRequestId', revoked.support_access_request_id,
           'reasonCode', 'EXACT_GRANT_REAUTHORIZATION_REQUIRED',
           'sessionVersion', revoked.version),
       revoked.provider_operator_id, revoked.provider_tenant_id,
       tenant.organization_id, 'PRIVILEGED_ACCESS'
  FROM revoked
  JOIN prv_operators operator
    ON operator.provider_operator_id = revoked.provider_operator_id
  JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = revoked.provider_tenant_id;

UPDATE prv_support_access_requests request
   SET lifecycle_state = 'COMPLETED',
       completed_at = statement_timestamp(),
       post_review_state = 'PENDING',
       updated_at = statement_timestamp(),
       updated_by = request.requester_operator_id,
       version = request.version + 1
 WHERE request.lifecycle_state = 'ACTIVATED';

UPDATE prv_support_access_requests request
   SET lifecycle_state = 'EXPIRED',
       updated_at = statement_timestamp(),
       updated_by = request.requester_operator_id,
       version = request.version + 1
 WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED');

-- Deploying a stronger grant contract is itself a re-authorization boundary.
-- Keep the durable switch disabled until an operator deliberately re-enables
-- it through the governed database control.
UPDATE prv_support_activation_control control
   SET activation_enabled = FALSE,
       change_reason = 'V49 exact support grant re-authorization required',
       change_correlation_id = 'migration:v49:exact-support-grant',
       changed_at = statement_timestamp(),
       changed_by = COALESCE(
           control.changed_by,
           (SELECT MIN(operator.provider_operator_id) FROM prv_operators operator)),
       version = control.version + 1
 WHERE control.control_key = 'STANDARD_JIT'
   AND control.activation_enabled;

-- V46/V48 left deferred lifecycle validators on these tables. Flush the
-- migration's fail-closed terminal transitions before adding the provenance
-- foreign key; PostgreSQL rejects ALTER TABLE while trigger events are queued.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE prv_support_sessions
    ADD CONSTRAINT fk_prv_support_sessions_revoked_by
        FOREIGN KEY (revoked_by)
        REFERENCES prv_operators(provider_operator_id)
        ON DELETE RESTRICT;

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
           AND operator.lifecycle_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'support activation control actor is not authorized';
    END IF;
    NEW.change_reason := BTRIM(NEW.change_reason);
    NEW.changed_at := statement_timestamp();
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

DROP TRIGGER IF EXISTS trg_prv_guard_support_activation_control
    ON prv_support_activation_control;
CREATE TRIGGER trg_prv_guard_support_activation_control
BEFORE INSERT OR UPDATE OR DELETE ON prv_support_activation_control
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_activation_control();

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
        AND EXISTS (
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
                        OR assignment.valid_to > statement_timestamp())))
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

DROP TRIGGER IF EXISTS trg_prv_guard_support_request_mutation
    ON prv_support_access_requests;
CREATE TRIGGER trg_prv_guard_support_request_mutation
BEFORE INSERT OR UPDATE OR DELETE ON prv_support_access_requests
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_request_mutation();

CREATE OR REPLACE FUNCTION prv_guard_support_request_scope_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    request_state VARCHAR(24);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'support request scope history is immutable';
    END IF;
    SELECT request.lifecycle_state
      INTO request_state
      FROM prv_support_access_requests request
     WHERE request.support_access_request_id = NEW.support_access_request_id;
    IF request_state <> 'PENDING_APPROVAL'
       OR NEW.scope_code <> 'TENANT_EXPERIENCE_PREVIEW'
       OR EXISTS (
           SELECT 1 FROM prv_support_access_request_scopes scope
            WHERE scope.support_access_request_id = NEW.support_access_request_id) THEN
        RAISE EXCEPTION 'support request requires one immutable preview scope';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_guard_support_request_scope_mutation
    ON prv_support_access_request_scopes;
CREATE TRIGGER trg_prv_guard_support_request_scope_mutation
BEFORE INSERT OR UPDATE OR DELETE ON prv_support_access_request_scopes
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_request_scope_mutation();

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
                            OR assignment.valid_to > statement_timestamp())))
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

DROP TRIGGER IF EXISTS trg_prv_guard_support_session_mutation ON prv_support_sessions;
CREATE TRIGGER trg_prv_guard_support_session_mutation
BEFORE INSERT OR UPDATE OR DELETE ON prv_support_sessions
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_session_mutation();

CREATE OR REPLACE FUNCTION prv_guard_support_session_scope_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_state VARCHAR(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'support session scope history is immutable';
    END IF;
    SELECT session.lifecycle_state INTO parent_state
      FROM prv_support_sessions session
     WHERE session.support_session_id = NEW.support_session_id;
    IF (FOUND AND parent_state <> 'ACTIVE')
       OR NEW.scope_code <> 'TENANT_EXPERIENCE_PREVIEW'
       OR EXISTS (
           SELECT 1 FROM prv_support_session_scopes scope
            WHERE scope.support_session_id = NEW.support_session_id) THEN
        RAISE EXCEPTION 'support session requires one immutable preview scope';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_guard_support_session_scope_mutation
    ON prv_support_session_scopes;
CREATE TRIGGER trg_prv_guard_support_session_scope_mutation
BEFORE INSERT OR UPDATE OR DELETE ON prv_support_session_scopes
FOR EACH ROW EXECUTE FUNCTION prv_guard_support_session_scope_mutation();

CREATE OR REPLACE FUNCTION prv_complete_request_after_support_session_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    completed_count INTEGER;
BEGIN
    UPDATE prv_support_access_requests request
       SET lifecycle_state = 'COMPLETED',
           completed_at = COALESCE(NEW.revoked_at, statement_timestamp()),
           post_review_state = 'PENDING',
           updated_at = statement_timestamp(),
           updated_by = COALESCE(NEW.revoked_by, NEW.provider_operator_id),
           version = request.version + 1
     WHERE request.support_access_request_id = NEW.support_access_request_id
       AND request.lifecycle_state = 'ACTIVATED';
    GET DIAGNOSTICS completed_count = ROW_COUNT;
    IF completed_count <> 1 THEN
        RAISE EXCEPTION 'terminal support session must complete exactly one activated request';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_complete_request_after_support_session_terminal
    ON prv_support_sessions;
CREATE TRIGGER trg_prv_complete_request_after_support_session_terminal
AFTER UPDATE OF lifecycle_state ON prv_support_sessions
FOR EACH ROW
WHEN (OLD.lifecycle_state = 'ACTIVE' AND NEW.lifecycle_state IN ('REVOKED', 'EXPIRED'))
EXECUTE FUNCTION prv_complete_request_after_support_session_terminal();

CREATE OR REPLACE FUNCTION prv_validate_exact_support_grant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_request_id UUID;
    request_row RECORD;
    session_row RECORD;
    request_scope_count INTEGER;
    request_preview_count INTEGER;
    session_scope_count INTEGER;
    session_preview_count INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'prv_support_access_requests' THEN
        resolved_request_id := COALESCE(NEW.support_access_request_id, OLD.support_access_request_id);
    ELSIF TG_TABLE_NAME = 'prv_support_access_request_scopes' THEN
        resolved_request_id := COALESCE(NEW.support_access_request_id, OLD.support_access_request_id);
    ELSIF TG_TABLE_NAME = 'prv_support_sessions' THEN
        resolved_request_id := COALESCE(NEW.support_access_request_id, OLD.support_access_request_id);
    ELSE
        SELECT session.support_access_request_id
          INTO resolved_request_id
          FROM prv_support_sessions session
         WHERE session.support_session_id = COALESCE(NEW.support_session_id, OLD.support_session_id);
    END IF;

    SELECT request.* INTO request_row
      FROM prv_support_access_requests request
     WHERE request.support_access_request_id = resolved_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'support grant request % is missing', resolved_request_id;
    END IF;

    SELECT COUNT(*), COUNT(*) FILTER (
               WHERE scope.scope_code = 'TENANT_EXPERIENCE_PREVIEW')
      INTO request_scope_count, request_preview_count
      FROM prv_support_access_request_scopes scope
     WHERE scope.support_access_request_id = resolved_request_id;

    IF request_row.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVATED') AND (
        request_row.access_mode <> 'STANDARD'
        OR request_row.risk_tier <> 'L1'
        OR NOT request_row.customer_approval_required
        OR request_row.approval_reference IS NULL
        OR LENGTH(BTRIM(request_row.approval_reference)) = 0
        OR request_row.requester_auth_session_id IS NULL
        OR LENGTH(BTRIM(request_row.justification)) = 0
        OR request_row.duration_minutes NOT BETWEEN 5 AND 60
        OR request_row.decision_due_at <= request_row.created_at
        OR request_scope_count <> 1
        OR request_preview_count <> 1
    ) THEN
        RAISE EXCEPTION 'support request % violates the exact grant contract', resolved_request_id;
    END IF;

    IF request_row.lifecycle_state IN ('APPROVED', 'ACTIVATED') AND (
        request_row.decided_at IS NULL
        OR request_row.decided_by IS NULL
        OR request_row.decided_by = request_row.requester_operator_id
        OR request_row.decision_reason IS NULL
        OR LENGTH(BTRIM(request_row.decision_reason)) = 0
        OR request_row.decided_at > request_row.decision_due_at
    ) THEN
        RAISE EXCEPTION 'support request % lacks an independent decision', resolved_request_id;
    END IF;

    SELECT session.* INTO session_row
      FROM prv_support_sessions session
     WHERE session.support_access_request_id = resolved_request_id;

    IF request_row.lifecycle_state = 'ACTIVATED' THEN
        IF NOT FOUND OR session_row.lifecycle_state <> 'ACTIVE' THEN
            RAISE EXCEPTION 'activated support request % must have one active session',
                resolved_request_id;
        END IF;
        SELECT COUNT(*), COUNT(*) FILTER (
                   WHERE scope.scope_code = 'TENANT_EXPERIENCE_PREVIEW')
          INTO session_scope_count, session_preview_count
          FROM prv_support_session_scopes scope
         WHERE scope.support_session_id = session_row.support_session_id;

        IF session_row.provider_tenant_id <> request_row.provider_tenant_id
           OR session_row.provider_operator_id <> request_row.requester_operator_id
           OR session_row.origin_auth_session_id <> request_row.requester_auth_session_id
           OR session_row.justification <> request_row.justification
           OR session_row.access_mode <> request_row.access_mode
           OR session_row.risk_tier <> request_row.risk_tier
           OR session_row.customer_approval_required <> request_row.customer_approval_required
           OR session_row.approval_reference IS DISTINCT FROM request_row.approval_reference
           OR session_row.started_at IS DISTINCT FROM request_row.activated_at
           OR session_row.expires_at IS DISTINCT FROM (
               session_row.started_at
               + make_interval(mins => request_row.duration_minutes))
           OR request_row.decided_at > session_row.started_at
           OR request_row.decision_due_at <= session_row.started_at
           OR statement_timestamp() >= session_row.expires_at
           OR session_row.last_used_at < session_row.started_at
           OR session_row.last_used_at > session_row.expires_at
           OR session_scope_count <> 1
           OR session_preview_count <> 1 THEN
            RAISE EXCEPTION 'active support session % does not exactly match request %',
                session_row.support_session_id, resolved_request_id;
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM prv_support_sessions session
         WHERE session.support_access_request_id = resolved_request_id
           AND session.lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'only an activated support request may retain an active session';
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_validate_exact_support_request
    ON prv_support_access_requests;
CREATE CONSTRAINT TRIGGER trg_prv_validate_exact_support_request
AFTER INSERT OR UPDATE ON prv_support_access_requests
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_validate_exact_support_grant();

DROP TRIGGER IF EXISTS trg_prv_validate_exact_support_request_scope
    ON prv_support_access_request_scopes;
CREATE CONSTRAINT TRIGGER trg_prv_validate_exact_support_request_scope
AFTER INSERT OR UPDATE OR DELETE ON prv_support_access_request_scopes
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_validate_exact_support_grant();

DROP TRIGGER IF EXISTS trg_prv_validate_exact_support_session ON prv_support_sessions;
CREATE CONSTRAINT TRIGGER trg_prv_validate_exact_support_session
AFTER INSERT OR UPDATE ON prv_support_sessions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_validate_exact_support_grant();

DROP TRIGGER IF EXISTS trg_prv_validate_exact_support_session_scope
    ON prv_support_session_scopes;
CREATE CONSTRAINT TRIGGER trg_prv_validate_exact_support_session_scope
AFTER INSERT OR UPDATE OR DELETE ON prv_support_session_scopes
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION prv_validate_exact_support_grant();

COMMENT ON FUNCTION prv_validate_exact_support_grant() IS
    'Deferred bidirectional invariant for the immutable Provider standard JIT request/session grant.';

-- Java records command intent with the inbound trace. These database events
-- independently prove that the ledger transition itself committed, including
-- an authorized direct-SQL command that never crossed the Java audit boundary.
CREATE OR REPLACE FUNCTION prv_audit_support_request_ledger_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transition_action VARCHAR(140);
    transition_actor BIGINT;
    resolved_actor_id BIGINT;
    resolved_organization_id UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        transition_action := 'provider.support-access.request-persisted';
        transition_actor := NEW.requester_operator_id;
    ELSIF OLD.lifecycle_state = 'PENDING_APPROVAL'
          AND NEW.lifecycle_state IN ('APPROVED', 'DENIED') THEN
        transition_action := 'provider.support-access.decision-persisted';
        transition_actor := NEW.decided_by;
    ELSIF OLD.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
          AND NEW.lifecycle_state = 'CANCELLED' THEN
        transition_action := 'provider.support-access.cancellation-persisted';
        transition_actor := NEW.cancelled_by;
    ELSIF OLD.lifecycle_state = 'APPROVED'
          AND NEW.lifecycle_state = 'ACTIVATED' THEN
        transition_action := 'provider.support-access.activation-persisted';
        transition_actor := NEW.requester_operator_id;
    ELSIF OLD.lifecycle_state = 'COMPLETED'
          AND NEW.lifecycle_state = 'REVIEWED' THEN
        transition_action := 'provider.support-access.review-persisted';
        transition_actor := NEW.post_reviewed_by;
    ELSE
        RETURN NEW;
    END IF;

    SELECT operator.auth_user_id, tenant.organization_id
      INTO STRICT resolved_actor_id, resolved_organization_id
      FROM prv_operators operator
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = NEW.provider_tenant_id
     WHERE operator.provider_operator_id = transition_actor;

    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    VALUES (
        gen_random_uuid(), resolved_actor_id, transition_action,
        'SUPPORT_ACCESS_REQUEST', NEW.support_access_request_id::text, 'SUCCESS',
        replace(gen_random_uuid()::text, '-', ''),
        jsonb_strip_nulls(jsonb_build_object(
            'supportAccessRequestId', NEW.support_access_request_id,
            'fromState', CASE WHEN TG_OP = 'UPDATE' THEN OLD.lifecycle_state ELSE NULL END,
            'toState', NEW.lifecycle_state,
            'accessMode', NEW.access_mode,
            'riskTier', NEW.risk_tier,
            'durationMinutes', NEW.duration_minutes,
            'requestVersion', NEW.version)),
        transition_actor, NEW.provider_tenant_id,
        resolved_organization_id, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_audit_support_request_ledger_transition
    ON prv_support_access_requests;
CREATE TRIGGER trg_prv_audit_support_request_ledger_transition
AFTER INSERT OR UPDATE OF lifecycle_state ON prv_support_access_requests
FOR EACH ROW EXECUTE FUNCTION prv_audit_support_request_ledger_transition();

CREATE OR REPLACE FUNCTION prv_audit_support_session_ledger_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transition_action VARCHAR(140);
    transition_actor BIGINT;
    resolved_actor_id BIGINT;
    resolved_organization_id UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        transition_action := 'provider.support-session.grant-persisted';
        transition_actor := NEW.provider_operator_id;
    ELSIF OLD.lifecycle_state = 'ACTIVE' AND NEW.lifecycle_state = 'REVOKED' THEN
        transition_action := 'provider.support-session.revocation-persisted';
        transition_actor := NEW.revoked_by;
    ELSE
        RETURN NEW;
    END IF;

    SELECT operator.auth_user_id, tenant.organization_id
      INTO STRICT resolved_actor_id, resolved_organization_id
      FROM prv_operators operator
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = NEW.provider_tenant_id
     WHERE operator.provider_operator_id = transition_actor;

    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    VALUES (
        gen_random_uuid(), resolved_actor_id, transition_action,
        'SUPPORT_SESSION', NEW.support_session_id::text, 'SUCCESS',
        replace(gen_random_uuid()::text, '-', ''),
        jsonb_strip_nulls(jsonb_build_object(
            'supportSessionId', NEW.support_session_id,
            'supportAccessRequestId', NEW.support_access_request_id,
            'fromState', CASE WHEN TG_OP = 'UPDATE' THEN OLD.lifecycle_state ELSE NULL END,
            'toState', NEW.lifecycle_state,
            'accessMode', NEW.access_mode,
            'riskTier', NEW.risk_tier,
            'sessionVersion', NEW.version)),
        transition_actor, NEW.provider_tenant_id,
        resolved_organization_id, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_audit_support_session_ledger_transition
    ON prv_support_sessions;
CREATE TRIGGER trg_prv_audit_support_session_ledger_transition
AFTER INSERT OR UPDATE OF lifecycle_state ON prv_support_sessions
FOR EACH ROW EXECUTE FUNCTION prv_audit_support_session_ledger_transition();
