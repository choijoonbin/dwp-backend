-- HRM-013 identity task-owner runtime.
--
-- This migration is deliberately data-empty.  It adds only the tenant-scoped,
-- durable ingress, replay, conflict, audit and HRM-008 receipt-outbox records
-- required by the default-OFF Auth identity-task owner.

CREATE TABLE auth_hrm013_identity_task_dispatch_inbox (
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    dispatch_event_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    task_version BIGINT NOT NULL,
    task_owner_revision BIGINT NOT NULL,
    person_public_id UUID NOT NULL,
    separation_type VARCHAR(128) NOT NULL,
    effective_date DATE NOT NULL,
    correlation_id UUID NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    request_body_sha256 CHAR(64) NOT NULL,
    workload_proof_sha256 CHAR(64) NOT NULL,
    exact_request_body BYTEA NOT NULL,
    dispatch_grant_set_id UUID NOT NULL,
    dispatch_grant_revision BIGINT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    dispatch_grant_use_mode VARCHAR(24) NOT NULL,
    drain_root_work_item_id UUID,
    drain_root_created_at TIMESTAMPTZ,
    drain_root_payload_sha256 CHAR(64),
    caller_signing_key_id VARCHAR(128) NOT NULL,
    dispatch_authority_request BYTEA NOT NULL,
    dispatch_authority_response BYTEA NOT NULL,
    dispatch_preparation_tx_id BIGINT NOT NULL,
    dispatch_preparation_sha256 CHAR(64) NOT NULL,
    admission_receipt BYTEA NOT NULL,
    admission_receipt_proof TEXT NOT NULL,
    admission_receipt_sha256 CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    terminal_command_sha256 CHAR(64),
    terminal_external_receipt_public_id UUID,
    admitted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    execution_attempt_count INTEGER NOT NULL DEFAULT 0,
    next_execution_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    last_execution_error VARCHAR(32),
    CONSTRAINT pk_auth_hrm013_identity_dispatch PRIMARY KEY (
        tenant_id, dispatch_event_id),
    CONSTRAINT uk_auth_hrm013_identity_task_version UNIQUE (
        tenant_id, task_public_id, task_version),
    CONSTRAINT ck_auth_hrm013_identity_dispatch_revisions CHECK (
        task_version > 0 AND task_owner_revision = 1
        AND dispatch_grant_revision > 0 AND tenant_grant_revision > 0
        AND authorization_revision > 0 AND dispatch_preparation_tx_id > 0
        AND execution_attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_auth_hrm013_identity_dispatch_type CHECK (
        separation_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_auth_hrm013_identity_dispatch_digests CHECK (
        payload_sha256 ~ '^[a-f0-9]{64}$'
        AND request_body_sha256 ~ '^[a-f0-9]{64}$'
        AND workload_proof_sha256 ~ '^[a-f0-9]{64}$'
        AND dispatch_preparation_sha256 ~ '^[a-f0-9]{64}$'
        AND admission_receipt_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_hrm013_identity_dispatch_bytes CHECK (
        octet_length(exact_request_body) BETWEEN 1 AND 32768
        AND octet_length(dispatch_authority_request) BETWEEN 1 AND 8192
        AND octet_length(dispatch_authority_response) BETWEEN 1 AND 16384
        AND octet_length(admission_receipt) BETWEEN 1 AND 16384
        AND octet_length(admission_receipt_proof) BETWEEN 1 AND 16384),
    CONSTRAINT ck_auth_hrm013_identity_dispatch_drain CHECK (
        (dispatch_grant_use_mode='NEW_WORK'
          AND drain_root_work_item_id IS NULL
          AND drain_root_created_at IS NULL
          AND drain_root_payload_sha256 IS NULL)
        OR (dispatch_grant_use_mode='DRAIN_EXISTING'
          AND drain_root_work_item_id IS NOT NULL
          AND drain_root_created_at IS NOT NULL
          AND drain_root_payload_sha256 IS NOT NULL
          AND drain_root_payload_sha256 ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_auth_hrm013_identity_dispatch_state CHECK (
        lifecycle_state IN ('RECEIVED', 'COMPLETED', 'FAILED')
        AND ((lifecycle_state = 'RECEIVED' AND completed_at IS NULL
              AND terminal_command_sha256 IS NULL
              AND terminal_external_receipt_public_id IS NULL)
          OR (lifecycle_state IN ('COMPLETED', 'FAILED')
              AND completed_at IS NOT NULL
              AND terminal_command_sha256 IS NOT NULL
              AND terminal_command_sha256 ~ '^[a-f0-9]{64}$'
              AND terminal_external_receipt_public_id IS NOT NULL)))
);

CREATE INDEX idx_auth_hrm013_identity_dispatch_ready
    ON auth_hrm013_identity_task_dispatch_inbox (
        tenant_id, lifecycle_state, effective_date, next_execution_at, admitted_at);

CREATE TABLE auth_hrm013_identity_task_dispatch_proof_replays (
    proof_replay_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    binding_id VARCHAR(40) NOT NULL,
    caller_workload_id VARCHAR(120) NOT NULL,
    token_kind VARCHAR(16) NOT NULL,
    token_digest CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT uk_auth_hrm013_identity_proof_digest UNIQUE (
        tenant_id, token_digest),
    CONSTRAINT ck_auth_hrm013_identity_proof_profile CHECK (
        binding_id = 'SVC-AUT-HRM-014'
        AND caller_workload_id = 'dwp-people-server'
        AND token_kind IN ('JTI', 'REQUEST_NONCE')),
    CONSTRAINT ck_auth_hrm013_identity_proof_digest CHECK (
        token_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_hrm013_identity_proof_window CHECK (
        expires_at > reserved_at
        AND expires_at <= reserved_at + INTERVAL '35 seconds')
);

CREATE INDEX idx_auth_hrm013_identity_proof_expiry
    ON auth_hrm013_identity_task_dispatch_proof_replays (tenant_id, expires_at);

CREATE FUNCTION auth_hrm013_identity_proof_window()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    NEW.reserved_at := statement_timestamp();
    IF NEW.expires_at <= NEW.reserved_at
       OR NEW.expires_at > NEW.reserved_at + INTERVAL '35 seconds' THEN
        RAISE EXCEPTION 'HRM-013 identity proof expiry is outside its sealed window'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_auth_hrm013_identity_proof_window
    BEFORE INSERT ON auth_hrm013_identity_task_dispatch_proof_replays
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_proof_window();

CREATE TABLE auth_hrm013_identity_task_dispatch_quarantine (
    quarantine_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    dispatch_event_id UUID NOT NULL,
    stored_request_body_sha256 CHAR(64) NOT NULL,
    rejected_request_body_sha256 CHAR(64) NOT NULL,
    rejected_workload_proof_sha256 CHAR(64) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_auth_hrm013_identity_quarantine_digest CHECK (
        stored_request_body_sha256 ~ '^[a-f0-9]{64}$'
        AND rejected_request_body_sha256 ~ '^[a-f0-9]{64}$'
        AND rejected_workload_proof_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_hrm013_identity_quarantine_reason CHECK (
        reason_code IN ('DISPATCH_DIGEST_CONFLICT', 'TASK_VERSION_CONFLICT',
            'TERMINAL_COMMAND_CONFLICT', 'EXECUTION_POISON'))
);

CREATE TABLE auth_hrm013_identity_task_receipt_outbox (
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    callback_event_id UUID NOT NULL,
    dispatch_event_id UUID NOT NULL,
    case_public_id UUID NOT NULL,
    task_public_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    exact_callback_body BYTEA NOT NULL,
    callback_payload_sha256 CHAR(64) NOT NULL,
    callback_grant_set_id UUID NOT NULL,
    callback_grant_revision BIGINT NOT NULL,
    tenant_grant_revision BIGINT NOT NULL,
    authorization_revision BIGINT NOT NULL,
    caller_signing_key_id VARCHAR(128) NOT NULL,
    grant_use_mode VARCHAR(24) NOT NULL,
    drain_root_work_item_id UUID,
    drain_root_created_at TIMESTAMPTZ,
    drain_root_payload_sha256 CHAR(64),
    drain_continuation_kind VARCHAR(24),
    drain_continuation_event_id UUID,
    drain_continuation_revision BIGINT,
    drain_continuation_payload_sha256 CHAR(64),
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claim_until TIMESTAMPTZ,
    claim_owner VARCHAR(128),
    claim_token UUID,
    delivered_at TIMESTAMPTZ,
    last_error_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT pk_auth_hrm013_identity_receipt_outbox PRIMARY KEY (
        tenant_id, callback_event_id),
    CONSTRAINT uk_auth_hrm013_identity_receipt_dispatch UNIQUE (
        tenant_id, dispatch_event_id),
    CONSTRAINT fk_auth_hrm013_identity_receipt_dispatch FOREIGN KEY (
        tenant_id, dispatch_event_id) REFERENCES
        auth_hrm013_identity_task_dispatch_inbox(tenant_id, dispatch_event_id),
    CONSTRAINT ck_auth_hrm013_identity_receipt_digests CHECK (
        callback_payload_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_auth_hrm013_identity_receipt_bytes CHECK (
        octet_length(exact_callback_body) BETWEEN 1 AND 32768),
    CONSTRAINT ck_auth_hrm013_identity_receipt_revision CHECK (
        callback_grant_revision > 0 AND tenant_grant_revision > 0
        AND authorization_revision > 0 AND attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_auth_hrm013_identity_receipt_grant_mode CHECK (
        (grant_use_mode = 'NEW_WORK'
          AND drain_root_work_item_id IS NULL
          AND drain_root_created_at IS NULL
          AND drain_root_payload_sha256 IS NULL
          AND drain_continuation_kind IS NULL
          AND drain_continuation_event_id IS NULL
          AND drain_continuation_revision IS NULL
          AND drain_continuation_payload_sha256 IS NULL)
        OR (grant_use_mode = 'DRAIN_EXISTING'
          AND drain_root_work_item_id IS NOT NULL
          AND drain_root_created_at IS NOT NULL
          AND drain_root_payload_sha256 IS NOT NULL
          AND drain_root_payload_sha256 ~ '^[a-f0-9]{64}$'
          AND drain_continuation_kind = 'TERMINAL_CHILD'
          AND drain_continuation_event_id IS NOT NULL
          AND drain_continuation_revision IS NOT NULL
          AND drain_continuation_revision > 0
          AND drain_continuation_payload_sha256 IS NOT NULL
          AND drain_continuation_payload_sha256 ~ '^[a-f0-9]{64}$')),
    CONSTRAINT ck_auth_hrm013_identity_receipt_state CHECK (
        delivery_state IN ('PENDING', 'CLAIMED', 'RETRY', 'DELIVERED', 'DEAD')
        AND (delivery_state = 'CLAIMED') = (claim_until IS NOT NULL)
        AND (delivery_state = 'CLAIMED') = (claim_owner IS NOT NULL)
        AND (delivery_state = 'CLAIMED') = (claim_token IS NOT NULL)
        AND (delivery_state = 'DELIVERED') = (delivered_at IS NOT NULL))
);

CREATE INDEX idx_auth_hrm013_identity_receipt_ready
    ON auth_hrm013_identity_task_receipt_outbox (
        tenant_id, delivery_state, next_attempt_at, created_at);

CREATE TABLE auth_hrm013_identity_revocation_watermarks (
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    person_public_id UUID NOT NULL,
    dispatch_event_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    effective_date DATE NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT pk_auth_hrm013_identity_revocation_watermark PRIMARY KEY (
        tenant_id, person_public_id),
    CONSTRAINT fk_auth_hrm013_identity_revocation_dispatch FOREIGN KEY (
        tenant_id, dispatch_event_id) REFERENCES
        auth_hrm013_identity_task_dispatch_inbox(tenant_id, dispatch_event_id)
);

CREATE TABLE auth_hrm013_identity_revocation_history (
    history_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    person_public_id UUID NOT NULL,
    dispatch_event_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    effective_date DATE NOT NULL,
    subject_state VARCHAR(16) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT uk_auth_hrm013_identity_revocation_history UNIQUE (
        tenant_id, dispatch_event_id),
    CONSTRAINT fk_auth_hrm013_identity_revocation_history_dispatch FOREIGN KEY (
        tenant_id, dispatch_event_id) REFERENCES
        auth_hrm013_identity_task_dispatch_inbox(tenant_id, dispatch_event_id),
    CONSTRAINT ck_auth_hrm013_identity_revocation_subject CHECK (
        subject_state IN ('ABSENT', 'REVOKED', 'ALREADY_REVOKED')),
    CONSTRAINT ck_auth_hrm013_identity_revocation_evidence CHECK (
        evidence_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE auth_hrm013_identity_sync_quarantine (
    quarantine_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    event_id UUID NOT NULL,
    person_public_id UUID NOT NULL,
    worker_status VARCHAR(32) NOT NULL,
    source_version VARCHAR(256),
    reason_code VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT uk_auth_hrm013_identity_sync_quarantine UNIQUE (
        tenant_id, event_id),
    CONSTRAINT ck_auth_hrm013_identity_sync_quarantine_reason CHECK (
        reason_code = 'REVOCATION_WATERMARK')
);

CREATE TABLE auth_hrm013_identity_task_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    dispatch_event_id UUID NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    outcome_code VARCHAR(16) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_auth_hrm013_identity_audit_action CHECK (
        action_code IN ('DISPATCH_ADMITTED', 'DISPATCH_REPLAYED',
            'DISPATCH_QUARANTINED', 'TASK_COMPLETED', 'TASK_FAILED',
            'IDENTITY_ACCESS_REVOKED', 'ROTATION_READINESS_OBSERVED',
            'RECEIPT_DELIVERED', 'RECEIPT_RETRY', 'RECEIPT_DEAD')),
    CONSTRAINT ck_auth_hrm013_identity_audit_outcome CHECK (
        outcome_code IN ('SUCCESS', 'CONFLICT', 'RETRY', 'FAILED')),
    CONSTRAINT ck_auth_hrm013_identity_audit_digest CHECK (
        evidence_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE UNIQUE INDEX uk_auth_hrm013_identity_revocation_success_audit
    ON auth_hrm013_identity_task_audit_events (
        tenant_id, dispatch_event_id, action_code)
    WHERE action_code='IDENTITY_ACCESS_REVOKED'
      AND outcome_code='SUCCESS';

CREATE FUNCTION auth_hrm013_identity_append_only()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'HRM-013 identity evidence is append-only'
        USING ERRCODE = '55000';
END $$;

CREATE TRIGGER trg_auth_hrm013_identity_quarantine_append_only
    BEFORE UPDATE OR DELETE ON auth_hrm013_identity_task_dispatch_quarantine
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_append_only();
CREATE TRIGGER trg_auth_hrm013_identity_audit_append_only
    BEFORE UPDATE OR DELETE ON auth_hrm013_identity_task_audit_events
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_append_only();
CREATE TRIGGER trg_auth_hrm013_identity_revocation_history_append_only
    BEFORE UPDATE OR DELETE ON auth_hrm013_identity_revocation_history
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_append_only();
CREATE TRIGGER trg_auth_hrm013_identity_sync_quarantine_append_only
    BEFORE UPDATE OR DELETE ON auth_hrm013_identity_sync_quarantine
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_append_only();

CREATE FUNCTION auth_hrm013_identity_revocation_watermark_guard()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE matching BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'HRM-013 identity revocation watermark is immutable';
    END IF;
    SELECT count(*) INTO matching
      FROM public.auth_hrm013_identity_task_dispatch_inbox inbox
      JOIN public.com_tenants tenant ON tenant.tenant_id=inbox.tenant_id
     WHERE inbox.tenant_id=NEW.tenant_id
       AND inbox.dispatch_event_id=NEW.dispatch_event_id
       AND inbox.person_public_id=NEW.person_public_id
       AND inbox.correlation_id=NEW.correlation_id
       AND inbox.effective_date=NEW.effective_date
       AND inbox.lifecycle_state='RECEIVED'
       AND inbox.effective_date <=
           (statement_timestamp() AT TIME ZONE tenant.time_zone)::DATE;
    IF matching <> 1 OR NEW.revoked_at IS DISTINCT FROM statement_timestamp() THEN
        RAISE EXCEPTION 'HRM-013 identity revocation watermark lacks due dispatch';
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION auth_hrm013_identity_revocation_watermark_guard() FROM PUBLIC;
CREATE TRIGGER trg_auth_hrm013_identity_revocation_watermark_guard
    BEFORE INSERT OR UPDATE OR DELETE ON auth_hrm013_identity_revocation_watermarks
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_revocation_watermark_guard();

CREATE FUNCTION auth_hrm013_identity_revocation_history_guard()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE
    matching BIGINT;
    expected_evidence TEXT;
    identity_inconsistent BOOLEAN;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'HRM-013 identity revocation history is append-only';
    END IF;
    SELECT count(*) INTO matching
      FROM public.auth_hrm013_identity_revocation_watermarks watermark
      JOIN public.auth_hrm013_identity_task_dispatch_inbox inbox
        ON inbox.tenant_id=NEW.tenant_id
       AND inbox.dispatch_event_id=NEW.dispatch_event_id
      JOIN public.com_tenants tenant ON tenant.tenant_id=inbox.tenant_id
     WHERE watermark.tenant_id=NEW.tenant_id
       AND watermark.person_public_id=NEW.person_public_id
       AND inbox.person_public_id=NEW.person_public_id
       AND inbox.correlation_id=NEW.correlation_id
       AND inbox.effective_date=NEW.effective_date
       AND inbox.lifecycle_state='RECEIVED'
       AND inbox.effective_date <=
           (statement_timestamp() AT TIME ZONE tenant.time_zone)::DATE
       AND ((watermark.dispatch_event_id=NEW.dispatch_event_id
             AND NEW.subject_state IN ('ABSENT','REVOKED'))
         OR (watermark.dispatch_event_id<>NEW.dispatch_event_id
             AND NEW.subject_state='ALREADY_REVOKED'));
    IF matching <> 1 THEN
        RAISE EXCEPTION 'HRM-013 identity revocation history lacks watermark';
    END IF;
    expected_evidence := encode(public.digest(convert_to(
        NEW.tenant_id::TEXT || chr(10) || NEW.person_public_id::TEXT
        || chr(10) || NEW.dispatch_event_id::TEXT || chr(10)
        || NEW.correlation_id::TEXT || chr(10) || NEW.effective_date::TEXT
        || chr(10) || NEW.subject_state, 'UTF8'), 'sha256'), 'hex');
    IF NEW.evidence_sha256 IS DISTINCT FROM expected_evidence THEN
        RAISE EXCEPTION 'HRM-013 identity revocation history evidence mismatch';
    END IF;
    IF NEW.subject_state='ABSENT' THEN
        SELECT EXISTS (
            SELECT 1 FROM public.com_users users
             WHERE users.tenant_id=NEW.tenant_id
               AND users.person_public_id=NEW.person_public_id
               AND users.identity_plane='TENANT') INTO identity_inconsistent;
    ELSE
        SELECT EXISTS (
            SELECT 1 FROM public.com_users users
             WHERE users.tenant_id=NEW.tenant_id
               AND users.person_public_id=NEW.person_public_id
               AND users.identity_plane='TENANT'
               AND (users.status<>'INACTIVE'
                 OR EXISTS (SELECT 1 FROM public.com_user_accounts accounts
                             WHERE accounts.tenant_id=users.tenant_id
                               AND accounts.user_id=users.user_id
                               AND accounts.status<>'RETIRED')
                 OR EXISTS (SELECT 1 FROM public.sys_auth_sessions sessions
                             WHERE sessions.tenant_id=users.tenant_id
                               AND sessions.user_id=users.user_id
                               AND sessions.revoked_at IS NULL)
                 OR EXISTS (SELECT 1
                              FROM public.sys_account_activation_tokens tokens
                             WHERE tokens.tenant_id=users.tenant_id
                               AND tokens.user_id=users.user_id
                               AND tokens.lifecycle_state='ACTIVE')
                 OR EXISTS (SELECT 1 FROM public.com_role_members roles
                             WHERE roles.tenant_id=users.tenant_id
                               AND roles.user_id=users.user_id)
                 OR EXISTS (SELECT 1 FROM public.com_group_members groups
                             WHERE groups.tenant_id=users.tenant_id
                               AND groups.user_id=users.user_id)))
          INTO identity_inconsistent;
    END IF;
    IF identity_inconsistent THEN
        RAISE EXCEPTION 'HRM-013 identity revocation side effects are incomplete';
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION auth_hrm013_identity_revocation_history_guard() FROM PUBLIC;
CREATE TRIGGER trg_auth_hrm013_identity_revocation_history_guard
    BEFORE INSERT ON auth_hrm013_identity_revocation_history
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_revocation_history_guard();

-- Session revocation emits the pre-existing enterprise audit outbox event.
-- Seal that trigger helper because the HRM-013 caller intentionally has no
-- direct audit-outbox table privilege.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE OR REPLACE FUNCTION public.sys_auth_session_to_audit_outbox()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE
    generated_event_id UUID := gen_random_uuid();
    event_action TEXT;
    event_time TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'INSERT' THEN
        event_action := 'authentication.session.created';
        event_time := COALESCE(
            NEW.issued_at, NEW.created_at AT TIME ZONE 'UTC', CURRENT_TIMESTAMP);
    ELSIF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
        event_action := 'authentication.session.revoked';
        event_time := NEW.revoked_at;
    ELSE
        RETURN NEW;
    END IF;
    INSERT INTO public.sys_audit_outbox(
      outbox_id,event_id,tenant_id,payload,status,attempt_count,
      available_at,created_at,updated_at)
    VALUES (gen_random_uuid(),generated_event_id,NEW.tenant_id,
      jsonb_build_object(
        'eventId',generated_event_id,'eventVersion','1.0',
        'occurredAt',event_time,'tenantId',NEW.tenant_id,
        'category','AUTHENTICATION','action',event_action,
        'outcome','SUCCESS',
        'severity',CASE WHEN event_action LIKE '%.revoked' THEN 'LOW' ELSE 'INFO' END,
        'riskScore',CASE WHEN event_action LIKE '%.revoked' THEN 20 ELSE 5 END,
        'actorType','USER','actorId',NEW.user_id::TEXT,
        'actorRoles','[]'::jsonb,'sourceService','dwp-auth-server',
        'sourceModule','session-management','environment',
        COALESCE(current_setting('dwp.environment',TRUE),'local'),
        'targetType','AUTH_SESSION','targetId',NEW.session_id::TEXT,
        'targetDisplayName','Session ' || LEFT(NEW.session_id::TEXT,8),
        'authenticationMethod','SESSION','beforeState','{}'::jsonb,
        'afterState','{}'::jsonb,'metadata',jsonb_build_object(
          'sessionFamilyId',NEW.session_family_id,'expiresAt',NEW.expires_at,
          'revokedAt',NEW.revoked_at,
          'networkContextPresent',NEW.ip_address IS NOT NULL,
          'clientContextPresent',NEW.user_agent IS NOT NULL),
        'retentionClass','EXTENDED'),
      'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.sys_auth_session_to_audit_outbox() FROM PUBLIC;

-- These pre-existing deferred trigger helpers contain legacy unqualified
-- public relation references.  Fix their path (without elevating them) so the
-- four explicitly-forced checks below run inside this sealed definer boundary.
ALTER FUNCTION public.dwp_enforce_scoped_duty_sod()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION public.dwp_enforce_app_responsibility_sod()
    SET search_path TO pg_catalog, public, pg_temp;
ALTER FUNCTION public.dwp_enforce_app_responsibility_user_sod()
    SET search_path TO pg_catalog, public, pg_temp;

CREATE FUNCTION auth_v272_revoke_identity_access(
    requested_dispatch_event_id UUID)
RETURNS TABLE(subject_state TEXT, evidence_sha256 TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE
    configured_tenant TEXT := current_setting('dwp.auth.hrm013.tenant', true);
    requested_tenant_id BIGINT;
    requested_person_public_id UUID;
    requested_correlation_id UUID;
    requested_effective_date DATE;
    due_dispatch RECORD;
    watermark_dispatch UUID;
    identity_row RECORD;
    identity_count INTEGER := 0;
    subject_user_id BIGINT;
    prior_subject_status TEXT;
    account_count INTEGER := 0;
    session_count INTEGER := 0;
    role_count INTEGER := 0;
    group_count INTEGER := 0;
    stored_history_count INTEGER;
    already_revoked BOOLEAN;
    result_state TEXT;
    result_evidence TEXT;
    history_evidence TEXT;
BEGIN
    IF requested_dispatch_event_id IS NULL OR configured_tenant IS NULL
       OR configured_tenant !~ '^[1-9][0-9]{0,18}$'
       OR (length(configured_tenant)=19
           AND configured_tenant > '9223372036854775807') THEN
        RAISE EXCEPTION 'HRM-013 identity revocation tenant mismatch'
            USING ERRCODE = '42501';
    END IF;
    requested_tenant_id := configured_tenant::BIGINT;

    SELECT inbox.* INTO due_dispatch
      FROM public.auth_hrm013_identity_task_dispatch_inbox inbox
      JOIN public.com_tenants tenant ON tenant.tenant_id=inbox.tenant_id
     WHERE inbox.tenant_id=requested_tenant_id
       AND inbox.dispatch_event_id=requested_dispatch_event_id
       AND inbox.lifecycle_state='RECEIVED'
       AND inbox.execution_attempt_count < 10
       AND inbox.next_execution_at <= statement_timestamp()
       AND inbox.effective_date <=
           (statement_timestamp() AT TIME ZONE tenant.time_zone)::DATE
     FOR UPDATE OF inbox;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'HRM-013 identity revocation lacks due locked dispatch'
            USING ERRCODE = '42501';
    END IF;
    requested_person_public_id := due_dispatch.person_public_id;
    requested_correlation_id := due_dispatch.correlation_id;
    requested_effective_date := due_dispatch.effective_date;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'DWP_AUTH_HRM013_IDENTITY_V1:' || requested_tenant_id::TEXT
        || ':person:' || requested_person_public_id::TEXT, 0));

    SELECT history.subject_state,audit.evidence_sha256
      INTO result_state,result_evidence
      FROM public.auth_hrm013_identity_revocation_history history
      JOIN public.auth_hrm013_identity_task_audit_events audit
        ON audit.tenant_id=history.tenant_id
       AND audit.dispatch_event_id=history.dispatch_event_id
       AND audit.correlation_id=history.correlation_id
       AND audit.action_code='IDENTITY_ACCESS_REVOKED'
       AND audit.outcome_code='SUCCESS'
     WHERE history.tenant_id=requested_tenant_id
       AND history.person_public_id=requested_person_public_id
       AND history.dispatch_event_id=requested_dispatch_event_id
       AND history.correlation_id=requested_correlation_id
       AND history.effective_date=requested_effective_date
     ORDER BY audit.recorded_at,audit.audit_event_id
     LIMIT 1;
    IF FOUND THEN
        RETURN QUERY SELECT result_state,result_evidence;
        RETURN;
    END IF;

    INSERT INTO public.auth_hrm013_identity_revocation_watermarks(
      tenant_id,person_public_id,dispatch_event_id,correlation_id,
      effective_date,revoked_at)
    VALUES (requested_tenant_id,requested_person_public_id,
      requested_dispatch_event_id,requested_correlation_id,
      requested_effective_date,statement_timestamp())
    ON CONFLICT (tenant_id,person_public_id) DO NOTHING;
    SELECT watermark.dispatch_event_id INTO STRICT watermark_dispatch
      FROM public.auth_hrm013_identity_revocation_watermarks watermark
     WHERE watermark.tenant_id=requested_tenant_id
       AND watermark.person_public_id=requested_person_public_id
     FOR UPDATE;
    already_revoked := watermark_dispatch <> requested_dispatch_event_id;

    FOR identity_row IN
        SELECT users.user_id,users.status
          FROM public.com_users users
         WHERE users.tenant_id=requested_tenant_id
           AND users.person_public_id=requested_person_public_id
           AND users.identity_plane='TENANT'
         FOR UPDATE
    LOOP
        identity_count := identity_count + 1;
        subject_user_id := identity_row.user_id;
        prior_subject_status := identity_row.status;
    END LOOP;
    IF identity_count > 1 THEN
        RAISE EXCEPTION 'HRM-013 identity revocation subject is ambiguous'
            USING ERRCODE = '23514';
    END IF;

    IF identity_count = 0 THEN
        result_state := CASE WHEN already_revoked
            THEN 'ALREADY_REVOKED' ELSE 'ABSENT' END;
        result_evidence := encode(public.digest(convert_to(
            'ABSENT' || chr(10) || requested_tenant_id::TEXT || chr(10)
            || requested_person_public_id::TEXT, 'UTF8'), 'sha256'), 'hex');
    ELSE
        UPDATE public.com_users
           SET status='INACTIVE',access_revision=access_revision+1,
               version=version+1,updated_at=transaction_timestamp(),
               updated_by=NULL
         WHERE tenant_id=requested_tenant_id
           AND user_id=subject_user_id AND identity_plane='TENANT';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'HRM-013 identity revocation subject update failed'
                USING ERRCODE = '23514';
        END IF;
        UPDATE public.com_user_accounts
           SET status='RETIRED',updated_at=transaction_timestamp(),updated_by=NULL
         WHERE tenant_id=requested_tenant_id AND user_id=subject_user_id
           AND status<>'RETIRED';
        GET DIAGNOSTICS account_count = ROW_COUNT;
        UPDATE public.sys_auth_sessions
           SET revoked_at=COALESCE(revoked_at,transaction_timestamp()),
               updated_at=transaction_timestamp(),updated_by=NULL
         WHERE tenant_id=requested_tenant_id AND user_id=subject_user_id
           AND revoked_at IS NULL;
        GET DIAGNOSTICS session_count = ROW_COUNT;
        UPDATE public.sys_account_activation_tokens
           SET lifecycle_state='REVOKED',
               revoked_at=COALESCE(revoked_at,transaction_timestamp())
         WHERE tenant_id=requested_tenant_id AND user_id=subject_user_id
           AND lifecycle_state='ACTIVE';
        DELETE FROM public.com_role_members
         WHERE tenant_id=requested_tenant_id AND user_id=subject_user_id;
        GET DIAGNOSTICS role_count = ROW_COUNT;
        DELETE FROM public.com_group_members
         WHERE tenant_id=requested_tenant_id AND user_id=subject_user_id;
        GET DIAGNOSTICS group_count = ROW_COUNT;
        -- Fire only the legacy cross-table SoD checks affected by these sealed
        -- DMLs while this SECURITY DEFINER boundary is still active.  The new
        -- HRM-013 history/terminal seals intentionally remain deferred.
        SET CONSTRAINTS public.trg_scoped_duty_user_sod,
            public.trg_scoped_duty_group_member_sod,
            public.trg_app_responsibility_user_state_sod,
            public.trg_app_responsibility_group_membership_sod IMMEDIATE;
        SET CONSTRAINTS public.trg_scoped_duty_user_sod,
            public.trg_scoped_duty_group_member_sod,
            public.trg_app_responsibility_user_state_sod,
            public.trg_app_responsibility_group_membership_sod DEFERRED;
        result_state := CASE WHEN already_revoked
            THEN 'ALREADY_REVOKED' ELSE 'REVOKED' END;
        result_evidence := encode(public.digest(convert_to(
            requested_tenant_id::TEXT || chr(10)
            || requested_person_public_id::TEXT || chr(10)
            || subject_user_id::TEXT || chr(10) || prior_subject_status
            || chr(10) || 'INACTIVE' || chr(10) || account_count::TEXT
            || chr(10) || session_count::TEXT || chr(10) || role_count::TEXT
            || chr(10) || group_count::TEXT, 'UTF8'), 'sha256'), 'hex');
    END IF;

    history_evidence := encode(public.digest(convert_to(
        requested_tenant_id::TEXT || chr(10)
        || requested_person_public_id::TEXT || chr(10)
        || requested_dispatch_event_id::TEXT || chr(10)
        || requested_correlation_id::TEXT || chr(10)
        || requested_effective_date::TEXT || chr(10) || result_state,
        'UTF8'), 'sha256'), 'hex');
    INSERT INTO public.auth_hrm013_identity_revocation_history(
      history_id,tenant_id,person_public_id,dispatch_event_id,
      correlation_id,effective_date,subject_state,evidence_sha256)
    VALUES (gen_random_uuid(),requested_tenant_id,requested_person_public_id,
      requested_dispatch_event_id,requested_correlation_id,
      requested_effective_date,result_state,history_evidence)
    ON CONFLICT (tenant_id,dispatch_event_id) DO NOTHING;
    SELECT count(*) INTO stored_history_count
      FROM public.auth_hrm013_identity_revocation_history history
     WHERE history.tenant_id=requested_tenant_id
       AND history.person_public_id=requested_person_public_id
       AND history.dispatch_event_id=requested_dispatch_event_id
       AND history.correlation_id=requested_correlation_id
       AND history.effective_date=requested_effective_date
       AND history.subject_state=result_state
       AND history.evidence_sha256=history_evidence;
    IF stored_history_count <> 1 THEN
        RAISE EXCEPTION 'HRM-013 identity revocation history conflict'
            USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.auth_hrm013_identity_task_audit_events(
      audit_event_id,tenant_id,dispatch_event_id,action_code,
      outcome_code,evidence_sha256,correlation_id)
    SELECT gen_random_uuid(),requested_tenant_id,requested_dispatch_event_id,
      'IDENTITY_ACCESS_REVOKED','SUCCESS',result_evidence,
      requested_correlation_id
    WHERE NOT EXISTS (
        SELECT 1 FROM public.auth_hrm013_identity_task_audit_events audit
         WHERE audit.tenant_id=requested_tenant_id
           AND audit.dispatch_event_id=requested_dispatch_event_id
           AND audit.action_code='IDENTITY_ACCESS_REVOKED'
           AND audit.outcome_code='SUCCESS'
           AND audit.evidence_sha256=result_evidence
           AND audit.correlation_id=requested_correlation_id);
    RETURN QUERY SELECT result_state,result_evidence;
END $$;
REVOKE ALL ON FUNCTION public.auth_v272_revoke_identity_access(UUID) FROM PUBLIC;

CREATE FUNCTION auth_hrm013_identity_audit_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE matching BIGINT;
BEGIN
    IF NEW.action_code <> 'IDENTITY_ACCESS_REVOKED' THEN
        RETURN NEW;
    END IF;
    SELECT count(*) INTO matching
      FROM public.auth_hrm013_identity_revocation_history history
     WHERE history.tenant_id=NEW.tenant_id
       AND history.dispatch_event_id=NEW.dispatch_event_id
       AND history.correlation_id=NEW.correlation_id;
    IF NEW.outcome_code <> 'SUCCESS' OR matching <> 1 THEN
        RAISE EXCEPTION 'HRM-013 reserved revocation audit requires sealed function'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION auth_hrm013_identity_audit_insert_guard() FROM PUBLIC;
CREATE TRIGGER trg_auth_hrm013_identity_audit_insert_guard
    BEFORE INSERT ON auth_hrm013_identity_task_audit_events
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_audit_insert_guard();

CREATE FUNCTION auth_hrm013_identity_replay_retention()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       OR clock_timestamp() < OLD.expires_at + INTERVAL '5 seconds' THEN
        RAISE EXCEPTION 'HRM-013 identity proof reservation is retained'
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END $$;

CREATE TRIGGER trg_auth_hrm013_identity_replay_retention
    BEFORE UPDATE OR DELETE ON auth_hrm013_identity_task_dispatch_proof_replays
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_replay_retention();

CREATE FUNCTION auth_v272_purge_expired_identity_replays(requested_tenant_id BIGINT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE
    configured_tenant TEXT := current_setting('dwp.auth.hrm013.tenant', true);
    purged BIGINT;
BEGIN
    IF requested_tenant_id < 1 OR configured_tenant IS NULL
       OR configured_tenant !~ '^[1-9][0-9]*$'
       OR configured_tenant::BIGINT <> requested_tenant_id THEN
        RAISE EXCEPTION 'HRM-013 identity replay purge tenant mismatch';
    END IF;
    WITH expired AS (
        SELECT proof_replay_id
          FROM public.auth_hrm013_identity_task_dispatch_proof_replays
         WHERE tenant_id = requested_tenant_id
           AND expires_at + INTERVAL '5 seconds' < clock_timestamp()
         ORDER BY expires_at, proof_replay_id
         LIMIT 100)
    DELETE FROM public.auth_hrm013_identity_task_dispatch_proof_replays replay
     USING expired WHERE replay.proof_replay_id = expired.proof_replay_id;
    GET DIAGNOSTICS purged = ROW_COUNT;
    RETURN purged;
END $$;
REVOKE ALL ON FUNCTION auth_v272_purge_expired_identity_replays(BIGINT) FROM PUBLIC;

CREATE FUNCTION auth_hrm013_identity_inbox_transition()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'RECEIVED'
           OR NEW.completed_at IS NOT NULL
           OR NEW.terminal_command_sha256 IS NOT NULL
           OR NEW.terminal_external_receipt_public_id IS NOT NULL
           OR NEW.execution_attempt_count <> 0
           OR NEW.last_execution_error IS NOT NULL
           OR NEW.next_execution_at IS DISTINCT FROM NEW.admitted_at THEN
            RAISE EXCEPTION 'HRM-013 identity inbox must begin RECEIVED';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 identity inbox rows cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['lifecycle_state','completed_at',
            'terminal_command_sha256','terminal_external_receipt_public_id',
            'execution_attempt_count','next_execution_at','last_execution_error'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['lifecycle_state','completed_at',
            'terminal_command_sha256','terminal_external_receipt_public_id',
            'execution_attempt_count','next_execution_at','last_execution_error']) THEN
        RAISE EXCEPTION 'HRM-013 identity inbox snapshot is immutable';
    END IF;
    IF OLD.lifecycle_state = 'RECEIVED'
       AND NEW.lifecycle_state = 'RECEIVED'
       AND NEW.completed_at IS NULL
       AND NEW.terminal_command_sha256 IS NULL
       AND NEW.terminal_external_receipt_public_id IS NULL
       AND NEW.execution_attempt_count = OLD.execution_attempt_count + 1
       AND NEW.execution_attempt_count <= 10
       AND NEW.next_execution_at >= statement_timestamp()
       AND NEW.next_execution_at <= statement_timestamp() + INTERVAL '300 seconds'
       AND NEW.last_execution_error IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state <> 'RECEIVED'
       OR NEW.lifecycle_state NOT IN ('COMPLETED', 'FAILED')
       OR NEW.completed_at IS DISTINCT FROM transaction_timestamp()
       OR NEW.completed_at > clock_timestamp()
       OR NEW.completed_at + INTERVAL '30 seconds' < clock_timestamp()
       OR OLD.terminal_command_sha256 IS NOT NULL
       OR NEW.terminal_command_sha256 IS NULL
       OR NEW.terminal_command_sha256 !~ '^[a-f0-9]{64}$'
       OR OLD.terminal_external_receipt_public_id IS NOT NULL
       OR NEW.terminal_external_receipt_public_id IS NULL
       OR NEW.execution_attempt_count <> OLD.execution_attempt_count
       OR NEW.next_execution_at IS DISTINCT FROM OLD.next_execution_at
       OR NEW.last_execution_error IS DISTINCT FROM OLD.last_execution_error THEN
        RAISE EXCEPTION 'Invalid HRM-013 identity inbox transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_auth_hrm013_identity_inbox_transition
    BEFORE INSERT OR UPDATE OR DELETE ON auth_hrm013_identity_task_dispatch_inbox
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_inbox_transition();

CREATE FUNCTION auth_v272_uuid_v5(namespace_id UUID, exact_name TEXT)
RETURNS UUID LANGUAGE plpgsql IMMUTABLE STRICT SET search_path = pg_catalog AS $$
DECLARE
    hashed BYTEA := substring(public.digest(
        uuid_send(namespace_id) || convert_to(exact_name, 'UTF8'), 'sha1') FROM 1 FOR 16);
    encoded TEXT;
BEGIN
    hashed := set_byte(hashed, 6, (get_byte(hashed, 6) & 15) | 80);
    hashed := set_byte(hashed, 8, (get_byte(hashed, 8) & 63) | 128);
    encoded := encode(hashed, 'hex');
    RETURN (substring(encoded,1,8)||'-'||substring(encoded,9,4)||'-'
        ||substring(encoded,13,4)||'-'||substring(encoded,17,4)||'-'
        ||substring(encoded,21,12))::UUID;
END $$;
REVOKE ALL ON FUNCTION auth_v272_uuid_v5(UUID, TEXT) FROM PUBLIC;

CREATE FUNCTION auth_hrm013_identity_receipt_transition()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE
    parent RECORD;
    callback_body JSONB;
    dispatch_body JSONB;
    expected_callback_id UUID;
    expected_root_id UUID;
    expected_root_created_at TIMESTAMPTZ;
    expected_root_payload_sha256 TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.delivery_state <> 'PENDING' OR NEW.attempt_count <> 0
           OR NEW.next_attempt_at IS DISTINCT FROM NEW.created_at
           OR NEW.created_at IS DISTINCT FROM transaction_timestamp()
           OR NEW.created_at > clock_timestamp()
           OR NEW.created_at + INTERVAL '30 seconds' < clock_timestamp()
           OR NEW.claim_until IS NOT NULL OR NEW.claim_owner IS NOT NULL
           OR NEW.claim_token IS NOT NULL OR NEW.delivered_at IS NOT NULL
           OR NEW.last_error_code IS NOT NULL THEN
            RAISE EXCEPTION 'HRM-013 identity receipt must begin pending and unclaimed';
        END IF;
        SELECT inbox.* INTO parent
          FROM public.auth_hrm013_identity_task_dispatch_inbox inbox
         WHERE inbox.tenant_id=NEW.tenant_id
           AND inbox.dispatch_event_id=NEW.dispatch_event_id;
        IF NOT FOUND OR parent.lifecycle_state NOT IN ('COMPLETED','FAILED')
           OR parent.completed_at IS NULL THEN
            RAISE EXCEPTION 'HRM-013 identity receipt requires terminal parent';
        END IF;
        BEGIN
            callback_body := convert_from(NEW.exact_callback_body,'UTF8')::JSONB;
            dispatch_body := convert_from(parent.exact_request_body,'UTF8')::JSONB;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'HRM-013 identity receipt body is invalid';
        END;
        IF parent.dispatch_grant_use_mode='NEW_WORK' THEN
            expected_root_id := parent.dispatch_event_id;
            expected_root_created_at := (dispatch_body->>'dispatchedAt')::TIMESTAMPTZ;
            expected_root_payload_sha256 := parent.payload_sha256;
        ELSE
            expected_root_id := parent.drain_root_work_item_id;
            expected_root_created_at := parent.drain_root_created_at;
            expected_root_payload_sha256 := parent.drain_root_payload_sha256;
        END IF;
        expected_callback_id := public.auth_v272_uuid_v5(
            '8ca8c32b-c8f7-5ab4-a9a5-18166dc32128'::UUID,
            NEW.tenant_id::TEXT || chr(10) || expected_root_id::TEXT
            || chr(10) || parent.task_owner_revision::TEXT || chr(10)
            || 'SVC-PEP-HRM-008' || chr(10) || 'TERMINAL_CHILD');
        IF jsonb_typeof(callback_body) <> 'object'
           OR NOT (callback_body ?& ARRAY['callbackEventId','casePublicId',
              'taskPublicId','taskType','status','ownerRevision',
              'externalReceiptPublicId','originatingPayloadSha256','occurredAt',
              'payloadSha256','correlationId'])
           OR (SELECT count(*) FROM jsonb_object_keys(callback_body)) <> 11
           OR NEW.callback_event_id IS DISTINCT FROM expected_callback_id
           OR NEW.case_public_id IS DISTINCT FROM parent.case_public_id
           OR NEW.task_public_id IS DISTINCT FROM parent.task_public_id
           OR NEW.correlation_id IS DISTINCT FROM parent.correlation_id
           OR NEW.created_at IS DISTINCT FROM parent.completed_at
           OR callback_body->>'callbackEventId' IS DISTINCT FROM NEW.callback_event_id::TEXT
           OR callback_body->>'casePublicId' IS DISTINCT FROM NEW.case_public_id::TEXT
           OR callback_body->>'taskPublicId' IS DISTINCT FROM NEW.task_public_id::TEXT
           OR callback_body->>'taskType' IS DISTINCT FROM 'IDENTITY_ACCESS_REVOCATION'
           OR callback_body->>'status' IS DISTINCT FROM parent.lifecycle_state
           OR (callback_body->>'ownerRevision')::BIGINT
                IS DISTINCT FROM parent.task_owner_revision
           OR callback_body->>'externalReceiptPublicId' IS DISTINCT FROM
                parent.terminal_external_receipt_public_id::TEXT
           OR callback_body->>'originatingPayloadSha256'
                IS DISTINCT FROM parent.payload_sha256
           OR (callback_body->>'occurredAt')::TIMESTAMPTZ
                IS DISTINCT FROM parent.completed_at
           OR callback_body->>'payloadSha256'
                IS DISTINCT FROM NEW.callback_payload_sha256
           OR callback_body->>'correlationId' IS DISTINCT FROM NEW.correlation_id::TEXT
           OR (NEW.grant_use_mode='DRAIN_EXISTING' AND (
                NEW.drain_root_work_item_id IS DISTINCT FROM expected_root_id
                OR NEW.drain_root_created_at IS DISTINCT FROM expected_root_created_at
                OR NEW.drain_root_payload_sha256
                    IS DISTINCT FROM expected_root_payload_sha256
                OR NEW.drain_continuation_kind IS DISTINCT FROM 'TERMINAL_CHILD'
                OR NEW.drain_continuation_event_id IS DISTINCT FROM NEW.callback_event_id
                OR NEW.drain_continuation_revision IS DISTINCT FROM parent.task_owner_revision
                OR NEW.drain_continuation_payload_sha256
                    IS DISTINCT FROM NEW.callback_payload_sha256)) THEN
            RAISE EXCEPTION 'HRM-013 identity receipt requires exact terminal parent';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRM-013 identity receipt rows cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.tenant_id <> NEW.tenant_id
       OR OLD.callback_event_id <> NEW.callback_event_id
       OR OLD.dispatch_event_id <> NEW.dispatch_event_id
       OR OLD.case_public_id <> NEW.case_public_id
       OR OLD.task_public_id <> NEW.task_public_id
       OR OLD.correlation_id <> NEW.correlation_id
       OR OLD.exact_callback_body <> NEW.exact_callback_body
       OR OLD.callback_payload_sha256 <> NEW.callback_payload_sha256
       OR OLD.callback_grant_set_id <> NEW.callback_grant_set_id
       OR OLD.callback_grant_revision <> NEW.callback_grant_revision
       OR OLD.tenant_grant_revision <> NEW.tenant_grant_revision
       OR OLD.authorization_revision <> NEW.authorization_revision
       OR OLD.caller_signing_key_id <> NEW.caller_signing_key_id
       OR OLD.grant_use_mode <> NEW.grant_use_mode
       OR OLD.drain_root_work_item_id IS DISTINCT FROM NEW.drain_root_work_item_id
       OR OLD.drain_root_created_at IS DISTINCT FROM NEW.drain_root_created_at
       OR OLD.drain_root_payload_sha256 IS DISTINCT FROM NEW.drain_root_payload_sha256
       OR OLD.drain_continuation_kind IS DISTINCT FROM NEW.drain_continuation_kind
       OR OLD.drain_continuation_event_id IS DISTINCT FROM NEW.drain_continuation_event_id
       OR OLD.drain_continuation_revision IS DISTINCT FROM NEW.drain_continuation_revision
       OR OLD.drain_continuation_payload_sha256 IS DISTINCT FROM NEW.drain_continuation_payload_sha256
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'HRM-013 identity receipt snapshot is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.delivery_state IN ('PENDING','RETRY')
       AND OLD.next_attempt_at <= statement_timestamp()
       AND OLD.attempt_count < 10
       AND NEW.delivery_state='CLAIMED'
       AND NEW.attempt_count=OLD.attempt_count+1
       AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
       AND NEW.claim_owner IS NOT NULL AND NEW.claim_token IS NOT NULL
       AND NEW.claim_until > statement_timestamp()
       AND NEW.claim_until <= statement_timestamp() + INTERVAL '300 seconds'
       AND NEW.delivered_at IS NULL
       AND NEW.last_error_code IS NULL THEN RETURN NEW;
    END IF;
    IF OLD.delivery_state='CLAIMED' AND OLD.claim_until <= statement_timestamp()
       AND OLD.attempt_count < 10 AND NEW.delivery_state='CLAIMED'
       AND NEW.attempt_count=OLD.attempt_count+1
       AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
       AND NEW.claim_owner IS NOT NULL AND NEW.claim_token IS NOT NULL
       AND NEW.claim_token IS DISTINCT FROM OLD.claim_token
       AND NEW.claim_until > statement_timestamp()
       AND NEW.claim_until <= statement_timestamp() + INTERVAL '300 seconds'
       AND NEW.delivered_at IS NULL AND NEW.last_error_code IS NULL THEN RETURN NEW;
    END IF;
    IF OLD.delivery_state='CLAIMED' AND OLD.claim_until > statement_timestamp()
       AND NEW.delivery_state='DELIVERED'
       AND NEW.attempt_count=OLD.attempt_count
       AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
       AND NEW.claim_owner IS NULL AND NEW.claim_token IS NULL
       AND NEW.claim_until IS NULL
       AND NEW.delivered_at IS NOT DISTINCT FROM statement_timestamp()
       AND NEW.last_error_code IS NULL THEN RETURN NEW;
    END IF;
    IF OLD.delivery_state='CLAIMED' AND OLD.claim_until > statement_timestamp()
       AND OLD.attempt_count < 10 AND NEW.delivery_state='RETRY'
       AND NEW.attempt_count=OLD.attempt_count
       AND NEW.next_attempt_at >= statement_timestamp()
       AND NEW.next_attempt_at <= statement_timestamp() + INTERVAL '300 seconds'
       AND NEW.claim_owner IS NULL AND NEW.claim_token IS NULL
       AND NEW.claim_until IS NULL AND NEW.delivered_at IS NULL
       AND NEW.last_error_code IS NOT NULL
       AND NEW.last_error_code <> 'RESULT_UNKNOWN' THEN RETURN NEW;
    END IF;
    IF OLD.delivery_state='CLAIMED' AND OLD.attempt_count=10
       AND NEW.delivery_state='DEAD'
       AND NEW.attempt_count=OLD.attempt_count
       AND NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at
       AND NEW.claim_owner IS NULL AND NEW.claim_token IS NULL
       AND NEW.claim_until IS NULL AND NEW.delivered_at IS NULL
       AND NEW.last_error_code='RESULT_UNKNOWN'
       AND OLD.claim_until IS NOT NULL
       THEN RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Invalid HRM-013 identity receipt transition'
        USING ERRCODE = '23514';
END $$;
REVOKE ALL ON FUNCTION auth_hrm013_identity_receipt_transition() FROM PUBLIC;

CREATE TRIGGER trg_auth_hrm013_identity_receipt_transition
    BEFORE INSERT OR UPDATE OR DELETE ON auth_hrm013_identity_task_receipt_outbox
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_receipt_transition();

CREATE FUNCTION auth_hrm013_identity_terminal_requires_receipt()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE matching BIGINT;
BEGIN
    IF NEW.lifecycle_state IN ('COMPLETED','FAILED') THEN
        IF NEW.completed_at IS DISTINCT FROM transaction_timestamp()
           OR NEW.completed_at > clock_timestamp()
           OR NEW.completed_at + INTERVAL '30 seconds' < clock_timestamp() THEN
            RAISE EXCEPTION 'HRM-013 terminal completion clock is stale'
                USING ERRCODE = '23514';
        END IF;
        SELECT count(*) INTO matching
          FROM public.auth_hrm013_identity_task_receipt_outbox receipt
         WHERE receipt.tenant_id=NEW.tenant_id
           AND receipt.dispatch_event_id=NEW.dispatch_event_id;
        IF matching <> 1 THEN
            RAISE EXCEPTION 'HRM-013 terminal inbox requires exact receipt outbox'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION auth_hrm013_identity_terminal_requires_receipt() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER trg_auth_hrm013_identity_terminal_requires_receipt
    AFTER UPDATE ON auth_hrm013_identity_task_dispatch_inbox
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_terminal_requires_receipt();

CREATE FUNCTION auth_hrm013_identity_history_requires_terminal()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO pg_catalog AS $$
DECLARE matching BIGINT;
BEGIN
    SELECT count(*) INTO matching
      FROM public.auth_hrm013_identity_task_dispatch_inbox inbox
      JOIN public.auth_hrm013_identity_task_receipt_outbox receipt
        ON receipt.tenant_id=inbox.tenant_id
       AND receipt.dispatch_event_id=inbox.dispatch_event_id
     WHERE inbox.tenant_id=NEW.tenant_id
       AND inbox.dispatch_event_id=NEW.dispatch_event_id
       AND inbox.person_public_id=NEW.person_public_id
       AND inbox.correlation_id=NEW.correlation_id
       AND inbox.effective_date=NEW.effective_date
       AND inbox.lifecycle_state IN ('COMPLETED','FAILED')
       AND inbox.completed_at IS NOT NULL;
    IF matching <> 1 THEN
        RAISE EXCEPTION 'HRM-013 revocation history requires terminal receipt'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION auth_hrm013_identity_history_requires_terminal() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER trg_auth_hrm013_identity_history_requires_terminal
    AFTER INSERT ON auth_hrm013_identity_revocation_history
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION auth_hrm013_identity_history_requires_terminal();

ALTER TABLE auth_hrm013_identity_task_dispatch_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_dispatch_inbox FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_dispatch_proof_replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_dispatch_proof_replays FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_dispatch_quarantine ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_dispatch_quarantine FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_receipt_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_receipt_outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_task_audit_events FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_revocation_watermarks ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_revocation_watermarks FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_revocation_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_revocation_history FORCE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_sync_quarantine ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_hrm013_identity_sync_quarantine FORCE ROW LEVEL SECURITY;

CREATE POLICY auth_hrm013_identity_inbox_tenant_scope
    ON auth_hrm013_identity_task_dispatch_inbox
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_replay_tenant_scope
    ON auth_hrm013_identity_task_dispatch_proof_replays
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_quarantine_tenant_scope
    ON auth_hrm013_identity_task_dispatch_quarantine
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_receipt_tenant_scope
    ON auth_hrm013_identity_task_receipt_outbox
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_audit_tenant_scope
    ON auth_hrm013_identity_task_audit_events
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_revocation_watermark_tenant_scope
    ON auth_hrm013_identity_revocation_watermarks
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_revocation_history_tenant_scope
    ON auth_hrm013_identity_revocation_history
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);
CREATE POLICY auth_hrm013_identity_sync_quarantine_tenant_scope
    ON auth_hrm013_identity_sync_quarantine
    USING (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.auth.hrm013.tenant', true), '')::BIGINT);

DO $auth_v272_runtime_access$
DECLARE
    runtime_role_name TEXT := NULLIF(
        current_setting('dwp.auth.hrm013_runtime_role', true), '');
    runtime_role RECORD;
    table_name TEXT;
    privilege_name TEXT;
    replay_sequence REGCLASS;
BEGIN
    IF runtime_role_name IS NULL THEN
        RAISE NOTICE 'HRM-013 runtime role GUC absent; V272 runtime grants skipped';
        RETURN;
    END IF;
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid Auth runtime role name';
    END IF;
    SELECT rolname,rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls
      INTO runtime_role FROM pg_roles WHERE rolname=runtime_role_name;
    IF NOT FOUND OR runtime_role.rolsuper OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Auth runtime role is missing or unsafe';
    END IF;
    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I',runtime_role_name);
    IF has_schema_privilege(runtime_role_name,'public','CREATE') THEN
        RAISE EXCEPTION 'Auth runtime role inherits forbidden public schema CREATE';
    END IF;
    FOREACH table_name IN ARRAY ARRAY[
      'auth_hrm013_identity_task_dispatch_inbox',
      'auth_hrm013_identity_task_dispatch_proof_replays',
      'auth_hrm013_identity_task_dispatch_quarantine',
      'auth_hrm013_identity_task_receipt_outbox',
      'auth_hrm013_identity_task_audit_events',
      'auth_hrm013_identity_revocation_watermarks',
      'auth_hrm013_identity_revocation_history',
      'auth_hrm013_identity_sync_quarantine'] LOOP
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY[
      'com_users','com_user_accounts','sys_auth_sessions',
      'sys_account_activation_tokens','com_role_members','com_group_members'] LOOP
        EXECUTE format('REVOKE ALL ON TABLE %I FROM %I',
                table_name,runtime_role_name);
        FOREACH privilege_name IN ARRAY ARRAY[
          'SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'] LOOP
            IF has_table_privilege(runtime_role_name,
                    format('public.%I',table_name),privilege_name) THEN
                RAISE EXCEPTION
                    'Auth runtime role inherits forbidden % privilege on %',
                    privilege_name,table_name;
            END IF;
        END LOOP;
        FOREACH privilege_name IN ARRAY ARRAY[
          'SELECT','INSERT','UPDATE','REFERENCES'] LOOP
            IF has_any_column_privilege(runtime_role_name,
                    format('public.%I',table_name),privilege_name) THEN
                RAISE EXCEPTION
                    'Auth runtime role inherits forbidden column % privilege on %',
                    privilege_name,table_name;
            END IF;
        END LOOP;
    END LOOP;
    EXECUTE format('GRANT SELECT (tenant_id,status,time_zone) ON com_tenants TO %I',
            runtime_role_name);
    EXECUTE format('GRANT EXECUTE ON FUNCTION public.auth_v272_revoke_identity_access(UUID) TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT ON auth_hrm013_identity_task_dispatch_inbox TO %I',runtime_role_name);
    EXECUTE format('GRANT INSERT (tenant_id,dispatch_event_id,case_public_id,task_public_id,task_version,task_owner_revision,person_public_id,separation_type,effective_date,correlation_id,payload_sha256,request_body_sha256,workload_proof_sha256,exact_request_body,dispatch_grant_set_id,dispatch_grant_revision,tenant_grant_revision,authorization_revision,dispatch_grant_use_mode,drain_root_work_item_id,drain_root_created_at,drain_root_payload_sha256,caller_signing_key_id,dispatch_authority_request,dispatch_authority_response,dispatch_preparation_tx_id,dispatch_preparation_sha256,admission_receipt,admission_receipt_proof,admission_receipt_sha256,lifecycle_state,admitted_at,next_execution_at) ON auth_hrm013_identity_task_dispatch_inbox TO %I',runtime_role_name);
    EXECUTE format('GRANT UPDATE (lifecycle_state,completed_at,terminal_command_sha256,terminal_external_receipt_public_id,execution_attempt_count,next_execution_at,last_execution_error) ON auth_hrm013_identity_task_dispatch_inbox TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT,INSERT ON auth_hrm013_identity_task_dispatch_proof_replays TO %I',runtime_role_name);
    SELECT pg_get_serial_sequence(
            'auth_hrm013_identity_task_dispatch_proof_replays',
            'proof_replay_id')::REGCLASS
      INTO STRICT replay_sequence;
    EXECUTE format('GRANT USAGE,SELECT ON SEQUENCE %s TO %I',
            replay_sequence, runtime_role_name);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth_v272_purge_expired_identity_replays(BIGINT) TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT,INSERT ON auth_hrm013_identity_task_dispatch_quarantine TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT ON auth_hrm013_identity_task_receipt_outbox TO %I',runtime_role_name);
    EXECUTE format('GRANT INSERT (tenant_id,callback_event_id,dispatch_event_id,case_public_id,task_public_id,correlation_id,exact_callback_body,callback_payload_sha256,callback_grant_set_id,callback_grant_revision,tenant_grant_revision,authorization_revision,caller_signing_key_id,grant_use_mode,drain_root_work_item_id,drain_root_created_at,drain_root_payload_sha256,drain_continuation_kind,drain_continuation_event_id,drain_continuation_revision,drain_continuation_payload_sha256,next_attempt_at,created_at) ON auth_hrm013_identity_task_receipt_outbox TO %I',runtime_role_name);
    EXECUTE format('GRANT UPDATE (delivery_state,attempt_count,next_attempt_at,claim_until,claim_owner,claim_token,delivered_at,last_error_code) ON auth_hrm013_identity_task_receipt_outbox TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT,INSERT ON auth_hrm013_identity_task_audit_events TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT ON auth_hrm013_identity_revocation_watermarks TO %I',runtime_role_name);
    EXECUTE format('GRANT SELECT,INSERT ON auth_hrm013_identity_sync_quarantine TO %I',runtime_role_name);
END
$auth_v272_runtime_access$;

COMMENT ON TABLE auth_hrm013_identity_task_dispatch_inbox IS
    'Durable exactly-once HRM-013 identity task admissions and immutable admission receipts.';
COMMENT ON TABLE auth_hrm013_identity_task_dispatch_quarantine IS
    'Append-only evidence for authenticated dispatch identity/digest conflicts.';
COMMENT ON TABLE auth_hrm013_identity_task_receipt_outbox IS
    'Durable HRM-008 identity completion/failure callbacks; V272 creates no row.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM auth_hrm013_identity_task_dispatch_inbox)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_task_dispatch_proof_replays)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_task_dispatch_quarantine)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_task_receipt_outbox)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_task_audit_events)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_revocation_watermarks)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_revocation_history)
       OR EXISTS (SELECT 1 FROM auth_hrm013_identity_sync_quarantine) THEN
        RAISE EXCEPTION 'V272 must not create identity authority, enrollment or runtime data';
    END IF;
END $$;
