-- Drain compatibility for provider nodes that still emit the former L3
-- risk-review approval shape. V54 corrected rows present at its installation;
-- this update closes the rolling gap before the trigger rejects stale writers.

-- V54 and V55 are separate commits. Drain every former and current provider
-- execution writer before inspecting the post-V54 state, then hold these locks
-- until all forward fences are visible. Tenant precedes operation deliberately:
-- the legacy CONTROL_RECORD transaction created its tenant before associating
-- the operation, so this order matches that transaction's lock order.

LOCK TABLE
    prv_tenants,
    prv_operations,
    prv_operation_steps,
    prv_operation_step_attempts,
    prv_tenant_service_instances,
    prv_maintenance_windows,
    prv_tenant_mutations,
    prv_tenant_command_outbox,
    prv_audit_events,
    prv_operation_approvals
IN SHARE ROW EXCLUSIVE MODE;

-- A V53 worker can finish a projection after V54 recovered its RUNNING step,
-- but before V55 owns the corresponding table lock. Rewind only the two exact
-- projections whose already-advanced local state makes retry impossible while
-- their recovered evidence remains incomplete. Remote state is reconciled by
-- retry.

UPDATE prv_operation_steps step
   SET lifecycle_state = 'FAILED',
       external_reference = NULL,
       redacted_result = '{}'::jsonb,
       next_retry_at = NULL,
       last_error_code = 'DEPLOYMENT_RECOVERY_REQUIRED',
       last_error_message =
           'The prior provider worker stopped before recording a terminal result.',
       completed_at = statement_timestamp()
  FROM prv_operations operation
 WHERE step.operation_id = operation.operation_id
   AND operation.lifecycle_state = 'PARTIAL'
   AND operation.failure_code = 'DEPLOYMENT_RECOVERY_REQUIRED'
   AND step.lifecycle_state IN ('RUNNING', 'SUCCEEDED')
   AND EXISTS (
       SELECT 1
         FROM prv_operation_step_attempts attempt
        WHERE attempt.operation_step_id = step.operation_step_id
          AND attempt.attempt_number = step.attempt_count
          AND attempt.lifecycle_state = 'ABANDONED'
          AND attempt.error_code = 'DEPLOYMENT_RECOVERY_REQUIRED'
   );

UPDATE prv_tenants tenant
       SET lifecycle_state = 'PROVISIONING',
       onboarding_state = 'PENDING_EXTERNAL',
       updated_at = statement_timestamp(),
       version = tenant.version + 1
  FROM prv_operations operation
  JOIN prv_operation_steps step
    ON step.operation_id = operation.operation_id
 WHERE operation.provider_tenant_id = tenant.provider_tenant_id
   AND operation.operation_type = 'TENANT_ONBOARD'
   AND operation.lifecycle_state = 'PARTIAL'
   AND operation.failure_code = 'DEPLOYMENT_RECOVERY_REQUIRED'
   AND step.step_key = 'ACTIVATE_TENANT'
   AND step.lifecycle_state = 'FAILED'
   AND step.last_error_code = 'DEPLOYMENT_RECOVERY_REQUIRED'
   AND tenant.lifecycle_state = 'ACTIVE'
   AND tenant.onboarding_state = 'READY';

UPDATE prv_maintenance_windows maintenance
   SET lifecycle_state = 'DRAFT',
       updated_at = statement_timestamp(),
       updated_by = maintenance.created_by,
       version = maintenance.version + 1
  FROM prv_operations operation
  JOIN prv_operation_steps step
    ON step.operation_id = operation.operation_id
 WHERE maintenance.operation_id = operation.operation_id
   AND operation.operation_type = 'MAINTENANCE_SCHEDULE'
   AND operation.lifecycle_state = 'PARTIAL'
   AND operation.failure_code = 'DEPLOYMENT_RECOVERY_REQUIRED'
   AND step.step_key = 'SCHEDULE_MAINTENANCE'
   AND step.lifecycle_state = 'FAILED'
   AND step.last_error_code = 'DEPLOYMENT_RECOVERY_REQUIRED'
   AND maintenance.lifecycle_state = 'SCHEDULED';

UPDATE prv_operation_approvals
   SET required_role_code = 'PROVIDER_CHANGE_APPROVER',
       separation_of_duties = TRUE,
       version = version + 1
 WHERE gate_key = 'RISK_REVIEW'
   AND lifecycle_state = 'PENDING'
   AND decided_by IS NULL
   AND decided_at IS NULL
   AND (
       required_role_code <> 'PROVIDER_CHANGE_APPROVER'
       OR NOT separation_of_duties
   )
   AND EXISTS (
       SELECT 1
         FROM prv_operations operation
        WHERE operation.operation_id = prv_operation_approvals.operation_id
          AND operation.risk_tier = 'L3'
   );

CREATE OR REPLACE FUNCTION prv_enforce_l3_risk_review_approval_role()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.gate_key = 'RISK_REVIEW'
       AND NEW.lifecycle_state = 'PENDING'
       AND (
           NEW.required_role_code <> 'PROVIDER_CHANGE_APPROVER'
           OR NOT NEW.separation_of_duties
       )
       AND EXISTS (
           SELECT 1
             FROM prv_operations operation
            WHERE operation.operation_id = NEW.operation_id
              AND operation.risk_tier = 'L3'
        ) THEN
        RAISE EXCEPTION
            'L3 RISK_REVIEW PENDING approvals require PROVIDER_CHANGE_APPROVER with separation_of_duties'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_l3_risk_review_approval_role';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_enforce_l3_risk_review_approval_role
    BEFORE INSERT OR UPDATE ON prv_operation_approvals
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_l3_risk_review_approval_role();

ALTER TABLE prv_audit_events
    ADD COLUMN provider_operation_id UUID REFERENCES prv_operations(operation_id),
    ADD COLUMN provider_operation_lease_token UUID,
    ADD CONSTRAINT ck_prv_audit_provider_operation_lease
        CHECK ((provider_operation_id IS NULL) = (provider_operation_lease_token IS NULL));

CREATE INDEX idx_prv_audit_provider_operation_lease
    ON prv_audit_events(provider_operation_id, provider_operation_lease_token)
    WHERE provider_operation_id IS NOT NULL;

COMMENT ON COLUMN prv_audit_events.provider_operation_lease_token IS
    'Immutable proof that canonical terminal audit was appended under the releasing operation lease.';
COMMENT ON COLUMN prv_audit_events.provider_operation_id IS
    'Provider operation whose bound execution lease appended this canonical terminal evidence.';

-- V53 workers recorded the terminal operation before appending the canonical
-- terminal audit event. Preserve the append-only ledger contract by filling
-- only genuinely absent success evidence before installing the writer fence.

INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category, occurred_at)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.tenant-onboarding.succeeded',
       'PROVIDER_TENANT', tenant.provider_tenant_id::text, 'SUCCESS',
       'migration:provider-terminal-audit:' || operation.operation_id,
       jsonb_build_object(
           'tenantKey', tenant.tenant_key,
           'authTenantId', tenant.auth_tenant_id),
       operator.provider_operator_id,
       tenant.provider_tenant_id, tenant.organization_id,
       'TENANT_LIFECYCLE',
       COALESCE(operation.completed_at, operation.updated_at, CURRENT_TIMESTAMP)
  FROM prv_operations operation
  JOIN prv_operators operator
    ON operator.provider_operator_id = operation.requested_by
  JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = operation.provider_tenant_id
 WHERE operation.operation_type = 'TENANT_ONBOARD'
   AND operation.lifecycle_state = 'SUCCEEDED'
   AND NOT EXISTS (
       SELECT 1
         FROM prv_audit_events audit
        WHERE audit.action = 'provider.tenant-onboarding.succeeded'
          AND audit.target_type = 'PROVIDER_TENANT'
          AND audit.target_id = tenant.provider_tenant_id::text
          AND audit.outcome = 'SUCCESS'
          AND audit.provider_tenant_id = tenant.provider_tenant_id
   );

INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category, occurred_at)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.maintenance.scheduled',
       'MAINTENANCE_WINDOW', maintenance.maintenance_window_id::text, 'SUCCESS',
       'migration:provider-terminal-audit:' || operation.operation_id,
       jsonb_build_object(
           'operationId', operation.operation_id,
           'planHash', operation.plan_hash),
       operator.provider_operator_id,
       tenant.provider_tenant_id, tenant.organization_id,
       'CHANGE_MANAGEMENT',
       COALESCE(operation.completed_at, operation.updated_at, CURRENT_TIMESTAMP)
  FROM prv_operations operation
  JOIN prv_operators operator
    ON operator.provider_operator_id = operation.requested_by
  JOIN prv_maintenance_windows maintenance
    ON maintenance.operation_id = operation.operation_id
  LEFT JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = operation.provider_tenant_id
 WHERE operation.operation_type = 'MAINTENANCE_SCHEDULE'
   AND operation.lifecycle_state = 'SUCCEEDED'
   AND NOT EXISTS (
       SELECT 1
         FROM prv_audit_events audit
        WHERE audit.action = 'provider.maintenance.scheduled'
          AND audit.target_type = 'MAINTENANCE_WINDOW'
          AND audit.target_id = maintenance.maintenance_window_id::text
          AND audit.outcome = 'SUCCESS'
   );

INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category, occurred_at)
SELECT gen_random_uuid(), operator.auth_user_id,
       CASE operation.operation_type
           WHEN 'TENANT_ONBOARD' THEN 'provider.tenant-onboarding.step-failed'
           ELSE 'provider.maintenance.schedule-failed'
       END,
       'PROVIDER_OPERATION', operation.operation_id::text, 'FAILED',
       'migration:provider-terminal-audit:' || operation.operation_id,
       jsonb_build_object(
           'step', COALESCE(failed_step.step_key, 'OPERATION_LEASE'),
           'errorCode', COALESCE(
               failed_step.last_error_code,
               operation.failure_code,
               'PROVISIONING_FAILED'),
           'attempt', COALESCE(failed_step.attempt_count, 0)),
       operator.provider_operator_id,
       tenant.provider_tenant_id, tenant.organization_id,
       CASE operation.operation_type
           WHEN 'TENANT_ONBOARD' THEN 'TENANT_LIFECYCLE'
           ELSE 'CHANGE_MANAGEMENT'
       END,
       COALESCE(operation.completed_at, operation.updated_at, CURRENT_TIMESTAMP)
  FROM prv_operations operation
  JOIN prv_operators operator
    ON operator.provider_operator_id = operation.requested_by
  LEFT JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = operation.provider_tenant_id
  LEFT JOIN LATERAL (
      SELECT step.step_key, step.last_error_code, step.attempt_count
        FROM prv_operation_steps step
       WHERE step.operation_id = operation.operation_id
         AND step.lifecycle_state = 'FAILED'
       ORDER BY step.completed_at DESC NULLS LAST,
                step.step_order DESC,
                step.operation_step_id DESC
       LIMIT 1
  ) failed_step ON TRUE
 WHERE operation.operation_type IN ('TENANT_ONBOARD', 'MAINTENANCE_SCHEDULE')
   AND operation.lifecycle_state IN ('PARTIAL', 'FAILED')
   AND NOT EXISTS (
       SELECT 1
         FROM prv_audit_events audit
        WHERE audit.action = CASE operation.operation_type
                WHEN 'TENANT_ONBOARD' THEN 'provider.tenant-onboarding.step-failed'
                ELSE 'provider.maintenance.schedule-failed'
              END
          AND audit.target_type = 'PROVIDER_OPERATION'
          AND audit.target_id = operation.operation_id::text
          AND audit.outcome = 'FAILED'
   );

-- Bind every execution write transaction to the opaque lease token installed
-- on its operation. The settings are transaction-local; an autocommit bind is
-- intentionally useless. Every protected statement revalidates the ledger so
-- a copied or expired setting cannot act as authority.

CREATE OR REPLACE FUNCTION prv_require_provider_operation_lease(
    expected_operation_id UUID DEFAULT NULL)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    raw_operation_id TEXT;
    raw_lease_token TEXT;
    bound_operation_id UUID;
    bound_lease_token UUID;
BEGIN
    raw_operation_id := NULLIF(current_setting('dwp.provider_operation_id', TRUE), '');
    raw_lease_token := NULLIF(current_setting('dwp.provider_operation_lease_token', TRUE), '');
    IF raw_operation_id IS NULL OR raw_lease_token IS NULL THEN
        RAISE EXCEPTION 'provider operation write requires a transaction-local lease binding'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;

    BEGIN
        bound_operation_id := raw_operation_id::UUID;
        bound_lease_token := raw_lease_token::UUID;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION 'provider operation lease binding is malformed'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END;

    IF expected_operation_id IS NOT NULL
       AND bound_operation_id <> expected_operation_id THEN
        RAISE EXCEPTION 'provider operation lease binding targets another operation'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;

    PERFORM 1
      FROM prv_operations operation
     WHERE operation.operation_id = bound_operation_id
       AND operation.lifecycle_state = 'EXECUTING'
       AND operation.lease_token = bound_lease_token
       AND operation.lease_expires_at > clock_timestamp();
    IF NOT FOUND THEN
        RAISE EXCEPTION 'provider operation lease binding is stale or expired'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;

    RETURN bound_operation_id;
END;
$$;

CREATE OR REPLACE FUNCTION prv_bind_provider_operation_lease(
    requested_operation_id UUID,
    requested_lease_token UUID)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    existing_operation_id TEXT;
    existing_lease_token TEXT;
BEGIN
    IF requested_operation_id IS NULL OR requested_lease_token IS NULL THEN
        RAISE EXCEPTION 'provider operation lease binding requires an operation and token'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;

    existing_operation_id := NULLIF(current_setting('dwp.provider_operation_id', TRUE), '');
    existing_lease_token := NULLIF(
        current_setting('dwp.provider_operation_lease_token', TRUE), '');
    IF (existing_operation_id IS NOT NULL
        AND existing_operation_id <> requested_operation_id::text)
       OR (existing_lease_token IS NOT NULL
           AND existing_lease_token <> requested_lease_token::text) THEN
        RAISE EXCEPTION 'provider operation lease cannot be rebound inside one transaction'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;

    PERFORM 1
      FROM prv_operations operation
     WHERE operation.operation_id = requested_operation_id
       AND operation.lifecycle_state = 'EXECUTING'
       AND operation.lease_token = requested_lease_token
       AND operation.lease_expires_at > clock_timestamp()
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'provider operation lease cannot be bound after ownership is lost'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;

    PERFORM set_config('dwp.provider_operation_id', requested_operation_id::text, TRUE);
    PERFORM set_config(
        'dwp.provider_operation_lease_token', requested_lease_token::text, TRUE);
    RETURN requested_operation_id;
END;
$$;

CREATE OR REPLACE FUNCTION prv_require_provider_tenant_projection_lease(
    expected_operation_id UUID,
    expected_tenant_id UUID)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    bound_operation_id UUID;
BEGIN
    bound_operation_id := prv_require_provider_operation_lease(expected_operation_id);
    IF NOT EXISTS (
        SELECT 1
          FROM prv_operations operation
         WHERE operation.operation_id = bound_operation_id
           AND operation.provider_tenant_id = expected_tenant_id
    ) THEN
        RAISE EXCEPTION 'provider projection lease does not own the target tenant'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_tenant_projection_lease';
    END IF;
    RETURN bound_operation_id;
END;
$$;

CREATE OR REPLACE FUNCTION prv_enforce_provider_operation_write_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    acquiring_lease BOOLEAN;
    terminal_audit_present BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state = 'EXECUTING' THEN
            RAISE EXCEPTION 'provider operations must be previewed before a lease is acquired'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
        END IF;
        RETURN NEW;
    END IF;

    acquiring_lease :=
        NEW.lifecycle_state = 'EXECUTING'
        AND NEW.lease_owner IS NOT NULL
        AND NEW.lease_token IS NOT NULL
        AND NEW.lease_token IS DISTINCT FROM OLD.lease_token
        AND NEW.lease_expires_at > clock_timestamp()
        AND (
            OLD.lifecycle_state IN ('PREVIEWED', 'PARTIAL', 'FAILED')
            OR (
                OLD.lifecycle_state = 'EXECUTING'
                AND OLD.lease_expires_at <= clock_timestamp()
            )
        );
    IF acquiring_lease THEN
        RETURN NEW;
    END IF;

    IF NEW.provider_tenant_id IS DISTINCT FROM OLD.provider_tenant_id
       AND NEW.operation_type = 'TENANT_ONBOARD' THEN
        PERFORM prv_require_provider_operation_lease(OLD.operation_id);
    END IF;

    IF OLD.lifecycle_state IN ('EXECUTING', 'PARTIAL', 'FAILED', 'SUCCEEDED')
       AND (
           NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state
           OR NEW.failure_code IS DISTINCT FROM OLD.failure_code
           OR NEW.failure_message IS DISTINCT FROM OLD.failure_message
           OR NEW.started_at IS DISTINCT FROM OLD.started_at
           OR NEW.completed_at IS DISTINCT FROM OLD.completed_at
           OR NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
           OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
           OR NEW.lease_expires_at IS DISTINCT FROM OLD.lease_expires_at
       ) THEN
        PERFORM prv_require_provider_operation_lease(OLD.operation_id);
    END IF;

    IF NEW.lifecycle_state = 'SUCCEEDED'
       AND OLD.lifecycle_state IS DISTINCT FROM 'SUCCEEDED' THEN
        IF NEW.operation_type = 'TENANT_ONBOARD' THEN
            SELECT EXISTS (
                SELECT 1
                  FROM prv_audit_events audit
                 WHERE audit.action = 'provider.tenant-onboarding.succeeded'
                   AND audit.target_type = 'PROVIDER_TENANT'
                   AND audit.target_id = NEW.provider_tenant_id::text
                   AND audit.outcome = 'SUCCESS'
                   AND audit.provider_tenant_id = NEW.provider_tenant_id
                   AND audit.provider_operation_id = NEW.operation_id
                   AND audit.provider_operation_lease_token = OLD.lease_token
            ) INTO terminal_audit_present;
        ELSIF NEW.operation_type = 'MAINTENANCE_SCHEDULE' THEN
            SELECT EXISTS (
                SELECT 1
                  FROM prv_maintenance_windows maintenance
                  JOIN prv_audit_events audit
                    ON audit.target_id = maintenance.maintenance_window_id::text
                   AND audit.action = 'provider.maintenance.scheduled'
                   AND audit.target_type = 'MAINTENANCE_WINDOW'
                   AND audit.outcome = 'SUCCESS'
                   AND audit.provider_operation_id = NEW.operation_id
                   AND audit.provider_operation_lease_token = OLD.lease_token
                 WHERE maintenance.operation_id = NEW.operation_id
            ) INTO terminal_audit_present;
        ELSE
            terminal_audit_present := TRUE;
        END IF;
        IF NOT terminal_audit_present THEN
            RAISE EXCEPTION 'provider operation cannot succeed before canonical terminal audit'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'ck_prv_provider_operation_terminal_audit';
        END IF;
    END IF;

    IF NEW.lifecycle_state IN ('PARTIAL', 'FAILED')
       AND NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state
       AND NEW.operation_type IN ('TENANT_ONBOARD', 'MAINTENANCE_SCHEDULE')
       AND NOT EXISTS (
           SELECT 1
             FROM prv_audit_events audit
            WHERE audit.action = CASE NEW.operation_type
                    WHEN 'TENANT_ONBOARD' THEN 'provider.tenant-onboarding.step-failed'
                    ELSE 'provider.maintenance.schedule-failed'
                  END
              AND audit.target_type = 'PROVIDER_OPERATION'
              AND audit.target_id = NEW.operation_id::text
              AND audit.outcome = 'FAILED'
              AND audit.provider_operation_id = NEW.operation_id
              AND audit.provider_operation_lease_token = OLD.lease_token
       ) THEN
        RAISE EXCEPTION 'provider operation cannot release a failed lease before canonical audit'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_terminal_audit';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_operation_write_lease
    BEFORE INSERT OR UPDATE ON prv_operations
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_operation_write_lease();

CREATE OR REPLACE FUNCTION prv_enforce_provider_step_write_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_operation_id UUID;
BEGIN
    IF TG_OP = 'INSERT'
       AND NEW.lifecycle_state = 'PENDING'
       AND NEW.attempt_count = 0
       AND NEW.external_reference IS NULL
       AND NEW.redacted_result = '{}'::jsonb
       AND NEW.started_at IS NULL
       AND NEW.completed_at IS NULL
       AND NEW.next_retry_at IS NULL
       AND NEW.last_error_code IS NULL
       AND NEW.last_error_message IS NULL
       AND EXISTS (
           SELECT 1
             FROM prv_operations operation
            WHERE operation.operation_id = NEW.operation_id
              AND operation.lifecycle_state = 'PREVIEWED'
       ) THEN
        RETURN NEW;
    END IF;

    affected_operation_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.operation_id ELSE NEW.operation_id END;
    PERFORM prv_require_provider_operation_lease(affected_operation_id);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_step_write_lease
    BEFORE INSERT OR UPDATE OR DELETE ON prv_operation_steps
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_step_write_lease();

CREATE OR REPLACE FUNCTION prv_enforce_provider_attempt_write_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_step_id BIGINT;
    affected_operation_id UUID;
BEGIN
    affected_step_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.operation_step_id ELSE NEW.operation_step_id END;
    SELECT step.operation_id
      INTO affected_operation_id
      FROM prv_operation_steps step
     WHERE step.operation_step_id = affected_step_id;
    IF affected_operation_id IS NULL THEN
        RAISE EXCEPTION 'provider operation attempt has no owning step'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_lease_binding';
    END IF;
    PERFORM prv_require_provider_operation_lease(affected_operation_id);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_attempt_write_lease
    BEFORE INSERT OR UPDATE OR DELETE ON prv_operation_step_attempts
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_attempt_write_lease();

CREATE OR REPLACE FUNCTION prv_enforce_provider_tenant_projection_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    owning_operation_id UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state = 'PROVISIONING'
           AND NEW.onboarding_state = 'CONTROL_PLANE_READY' THEN
            SELECT operation.operation_id
              INTO owning_operation_id
              FROM prv_operations operation
             WHERE operation.operation_type = 'TENANT_ONBOARD'
               AND operation.plan ->> 'tenantKey' = NEW.tenant_key
               AND operation.lifecycle_state IN ('EXECUTING', 'PARTIAL')
             ORDER BY operation.created_at DESC, operation.operation_id DESC
             LIMIT 1;
            IF owning_operation_id IS NOT NULL THEN
                PERFORM prv_require_provider_operation_lease(owning_operation_id);
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.auth_tenant_id IS DISTINCT FROM OLD.auth_tenant_id
       OR (NEW.onboarding_state IS DISTINCT FROM OLD.onboarding_state
           AND NEW.onboarding_state IN ('PENDING_EXTERNAL', 'READY', 'FAILED'))
       OR (OLD.lifecycle_state = 'PROVISIONING'
           AND NEW.lifecycle_state = 'ACTIVE') THEN
        SELECT operation.operation_id
          INTO owning_operation_id
          FROM prv_operations operation
         WHERE operation.provider_tenant_id = NEW.provider_tenant_id
           AND operation.operation_type = 'TENANT_ONBOARD'
           AND operation.lifecycle_state IN ('EXECUTING', 'PARTIAL', 'FAILED', 'SUCCEEDED')
         ORDER BY operation.created_at DESC, operation.operation_id DESC
         LIMIT 1;
        IF owning_operation_id IS NOT NULL THEN
            PERFORM prv_require_provider_tenant_projection_lease(
                owning_operation_id, NEW.provider_tenant_id);
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_tenant_projection_lease
    BEFORE INSERT OR UPDATE ON prv_tenants
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_tenant_projection_lease();

CREATE OR REPLACE FUNCTION prv_enforce_provider_service_projection_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_tenant_id UUID;
    owning_operation_id UUID;
    owning_operation_state VARCHAR(24);
    protected_projection BOOLEAN;
BEGIN
    affected_tenant_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.provider_tenant_id ELSE NEW.provider_tenant_id END;
    SELECT operation.operation_id, operation.lifecycle_state
      INTO owning_operation_id, owning_operation_state
      FROM prv_operations operation
     WHERE operation.provider_tenant_id = affected_tenant_id
       AND operation.operation_type = 'TENANT_ONBOARD'
       AND operation.lifecycle_state IN ('EXECUTING', 'PARTIAL', 'FAILED', 'SUCCEEDED')
     ORDER BY operation.created_at DESC, operation.operation_id DESC
     LIMIT 1;

    IF owning_operation_id IS NULL THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    protected_projection :=
        TG_OP IN ('INSERT', 'DELETE') AND owning_operation_state <> 'SUCCEEDED';
    IF TG_OP = 'UPDATE' THEN
        protected_projection :=
            (NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state
             AND NEW.lifecycle_state IN ('READY', 'FAILED'))
            OR NEW.external_resource_id IS DISTINCT FROM OLD.external_resource_id
            OR NEW.applied_schema_version IS DISTINCT FROM OLD.applied_schema_version
            OR (owning_operation_state <> 'SUCCEEDED'
                AND NEW.health_snapshot IS DISTINCT FROM OLD.health_snapshot);
    END IF;

    IF protected_projection THEN
        PERFORM prv_require_provider_tenant_projection_lease(
            owning_operation_id, affected_tenant_id);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_service_projection_lease
    BEFORE INSERT OR UPDATE OR DELETE ON prv_tenant_service_instances
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_service_projection_lease();

CREATE OR REPLACE FUNCTION prv_require_onboarding_mutation_lease(
    expected_mutation_id UUID)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    provider_operation_text TEXT;
    provider_operation_id UUID;
    mutation_tenant_id UUID;
BEGIN
    SELECT mutation.desired_payload ->> 'providerOperationId',
           mutation.provider_tenant_id
      INTO provider_operation_text, mutation_tenant_id
      FROM prv_tenant_mutations mutation
     WHERE mutation.mutation_id = expected_mutation_id;
    IF provider_operation_text IS NULL THEN
        RETURN NULL;
    END IF;

    BEGIN
        provider_operation_id := provider_operation_text::UUID;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION 'onboarding mutation provider operation binding is malformed'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_onboarding_mutation_operation_lease';
    END;

    IF NOT EXISTS (
        SELECT 1
          FROM prv_operations operation
         WHERE operation.operation_id = provider_operation_id
           AND operation.operation_type = 'TENANT_ONBOARD'
           AND operation.provider_tenant_id = mutation_tenant_id
    ) THEN
        RAISE EXCEPTION 'onboarding mutation does not match its provider operation tenant'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_onboarding_mutation_operation_lease';
    END IF;
    PERFORM prv_require_provider_operation_lease(provider_operation_id);
    RETURN provider_operation_id;
END;
$$;

CREATE OR REPLACE FUNCTION prv_enforce_onboarding_mutation_write_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_mutation_id UUID;
BEGIN
    affected_mutation_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.mutation_id ELSE NEW.mutation_id END;
    PERFORM prv_require_onboarding_mutation_lease(affected_mutation_id);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_onboarding_mutation_write_lease
    BEFORE UPDATE OR DELETE ON prv_tenant_mutations
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_onboarding_mutation_write_lease();

CREATE OR REPLACE FUNCTION prv_enforce_onboarding_command_write_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_mutation_id UUID;
BEGIN
    affected_mutation_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.mutation_id ELSE NEW.mutation_id END;
    PERFORM prv_require_onboarding_mutation_lease(affected_mutation_id);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_onboarding_command_write_lease
    BEFORE UPDATE OR DELETE ON prv_tenant_command_outbox
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_onboarding_command_write_lease();

CREATE OR REPLACE FUNCTION prv_enforce_provider_maintenance_projection_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state = 'SCHEDULED' THEN
        PERFORM prv_require_provider_operation_lease(NEW.operation_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_maintenance_projection_lease
    BEFORE UPDATE ON prv_maintenance_windows
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_maintenance_projection_lease();

CREATE OR REPLACE FUNCTION prv_enforce_provider_terminal_audit_lease()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    bound_operation_id UUID;
    bound_lease_token UUID;
    audit_matches_operation BOOLEAN;
BEGIN
    IF NEW.action NOT IN (
        'provider.tenant-onboarding.succeeded',
        'provider.tenant-onboarding.step-failed',
        'provider.maintenance.scheduled',
        'provider.maintenance.schedule-failed') THEN
        RETURN NEW;
    END IF;

    bound_operation_id := prv_require_provider_operation_lease(NULL);
    SELECT operation.lease_token
      INTO bound_lease_token
      FROM prv_operations operation
     WHERE operation.operation_id = bound_operation_id;
    IF NEW.action = 'provider.tenant-onboarding.succeeded' THEN
        SELECT EXISTS (
            SELECT 1
              FROM prv_operations operation
             WHERE operation.operation_id = bound_operation_id
               AND operation.operation_type = 'TENANT_ONBOARD'
               AND operation.provider_tenant_id = NEW.provider_tenant_id
               AND NEW.target_type = 'PROVIDER_TENANT'
               AND NEW.target_id = operation.provider_tenant_id::text
               AND NEW.outcome = 'SUCCESS'
        ) INTO audit_matches_operation;
    ELSIF NEW.action = 'provider.maintenance.scheduled' THEN
        SELECT EXISTS (
            SELECT 1
              FROM prv_maintenance_windows maintenance
              JOIN prv_operations operation
                ON operation.operation_id = maintenance.operation_id
             WHERE operation.operation_id = bound_operation_id
               AND NEW.provider_tenant_id IS NOT DISTINCT FROM operation.provider_tenant_id
               AND NEW.target_type = 'MAINTENANCE_WINDOW'
               AND NEW.target_id = maintenance.maintenance_window_id::text
               AND NEW.outcome = 'SUCCESS'
        ) INTO audit_matches_operation;
    ELSE
        audit_matches_operation :=
            NEW.target_type = 'PROVIDER_OPERATION'
            AND NEW.target_id = bound_operation_id::text
            AND NEW.outcome = 'FAILED'
            AND EXISTS (
                SELECT 1
                  FROM prv_operations operation
                 WHERE operation.operation_id = bound_operation_id
                   AND NEW.provider_tenant_id IS NOT DISTINCT FROM operation.provider_tenant_id
                   AND (
                       (NEW.action = 'provider.tenant-onboarding.step-failed'
                        AND operation.operation_type = 'TENANT_ONBOARD')
                       OR (NEW.action = 'provider.maintenance.schedule-failed'
                           AND operation.operation_type = 'MAINTENANCE_SCHEDULE')
                   )
            );
    END IF;

    IF NOT audit_matches_operation THEN
        RAISE EXCEPTION 'provider terminal audit does not match its bound operation'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_prv_provider_operation_terminal_audit';
    END IF;
    NEW.provider_operation_id := bound_operation_id;
    NEW.provider_operation_lease_token := bound_lease_token;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_provider_terminal_audit_lease
    BEFORE INSERT ON prv_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION prv_enforce_provider_terminal_audit_lease();
