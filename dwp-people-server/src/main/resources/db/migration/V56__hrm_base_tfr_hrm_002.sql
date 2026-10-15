-- BASE-TFR-HRM-002: durable, tenant-scoped HRIS experience handoff and
-- notification receipt evidence. The V54 ppl_command_receipts ledger remains
-- the only command-receipt source of truth.
SET LOCAL search_path = pg_catalog;

CREATE TABLE public.ppl_hris_notification_intent_handoffs (
    intent_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.sys_service_tenants(tenant_id),
    source_event_id UUID NOT NULL,
    source_urn VARCHAR(200) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    recipient_user_id BIGINT,
    excluded_user_id BIGINT NOT NULL,
    decision VARCHAR(48) NOT NULL,
    omission_code VARCHAR(80),
    producer_app_key VARCHAR(128),
    type_key VARCHAR(80),
    purpose_code VARCHAR(80),
    thread_ref VARCHAR(512),
    locale VARCHAR(35),
    reason_code VARCHAR(80),
    subject_ref VARCHAR(512),
    target_ref VARCHAR(512),
    template_key VARCHAR(128),
    template_version BIGINT,
    template_model_digest CHAR(64),
    classification VARCHAR(24),
    action_required BOOLEAN,
    idempotency_key VARCHAR(128),
    canonical_intent_sha256 CHAR(64),
    correlation_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT uk_ppl_hris_notification_intent_tenant
        UNIQUE (tenant_id, intent_id),
    CONSTRAINT uk_ppl_hris_notification_source_event
        UNIQUE (tenant_id, source_event_id),
    CONSTRAINT ck_ppl_hris_notification_intent_identity CHECK (
        source_urn = 'urn:dwp:people:hrm:dwp-experience'
        AND event_type = 'EmployeeChangeAppliedNotificationRequested.v1'
        AND aggregate_type = 'EMPLOYEE_CHANGE_REQUEST'
        AND excluded_user_id > 0),
    CONSTRAINT ck_ppl_hris_notification_intent_decision CHECK (
        decision IN ('READY_LOCAL_ONLY', 'OMITTED_NO_ELIGIBLE_RECIPIENT',
                     'OMITTED_ACTOR_EXCLUDED')),
    CONSTRAINT ck_ppl_hris_notification_intent_payload CHECK (
        (decision = 'READY_LOCAL_ONLY'
         AND recipient_user_id IS NOT NULL AND recipient_user_id > 0
         AND recipient_user_id <> excluded_user_id
         AND omission_code IS NULL
         AND producer_app_key = 'hcm'
         AND type_key = 'HCM.EMPLOYEE_CHANGE_APPLIED'
         AND purpose_code = 'EMPLOYEE_SELF_SERVICE'
         AND thread_ref = 'hris:employee-change:' || aggregate_id::text
         AND locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'
         AND reason_code = 'EMPLOYEE_CHANGE_APPLIED'
         AND subject_ref ~ '^person:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
         AND target_ref = 'employee-change:' || aggregate_id::text
         AND template_key = 'hcm.employee-change.applied'
         AND template_version = 1
         AND template_model_digest =
             '24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784'
         AND classification = 'INTERNAL' AND action_required = false
         AND idempotency_key = 'hrm002:' || source_event_id::text
         AND canonical_intent_sha256 ~ '^[0-9a-f]{64}$'
        )
        OR
        (decision <> 'READY_LOCAL_ONLY'
         AND recipient_user_id IS NULL
         AND omission_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'
         AND producer_app_key IS NULL AND type_key IS NULL
         AND purpose_code IS NULL AND thread_ref IS NULL AND locale IS NULL
         AND reason_code IS NULL AND subject_ref IS NULL AND target_ref IS NULL
         AND template_key IS NULL AND template_version IS NULL
         AND template_model_digest IS NULL AND classification IS NULL
         AND action_required IS NULL AND idempotency_key IS NULL
         AND canonical_intent_sha256 IS NULL
        ))
);

CREATE TABLE public.ppl_hris_notification_handoff_claims (
    tenant_id BIGINT NOT NULL REFERENCES public.sys_service_tenants(tenant_id),
    intent_id UUID NOT NULL,
    claim_state VARCHAR(24) NOT NULL DEFAULT 'READY',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    maximum_attempts INTEGER NOT NULL DEFAULT 10,
    lease_id UUID,
    lease_expires_at TIMESTAMPTZ,
    retry_eligible_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    PRIMARY KEY (tenant_id, intent_id),
    CONSTRAINT fk_ppl_hris_notification_handoff_claim_intent FOREIGN KEY
        (tenant_id, intent_id)
        REFERENCES public.ppl_hris_notification_intent_handoffs(tenant_id, intent_id),
    CONSTRAINT ck_ppl_hris_notification_handoff_claim_values CHECK (
        claim_state IN ('READY', 'CLAIMED', 'RETRY_WAIT', 'COMPLETED', 'DEAD_LETTERED')
        AND attempt_count >= 0 AND maximum_attempts BETWEEN 1 AND 100
        AND attempt_count <= maximum_attempts
        AND (last_error_code IS NULL
             OR last_error_code ~ '^[A-Z][A-Z0-9._-]{0,79}$')
        AND (
          (claim_state = 'READY' AND attempt_count = 0
           AND lease_id IS NULL AND lease_expires_at IS NULL
           AND retry_eligible_at IS NULL AND last_error_code IS NULL)
          OR
          (claim_state = 'CLAIMED' AND attempt_count > 0
           AND lease_id IS NOT NULL AND lease_expires_at IS NOT NULL
           AND retry_eligible_at IS NULL)
          OR
          (claim_state = 'RETRY_WAIT' AND attempt_count > 0
           AND attempt_count < maximum_attempts
           AND lease_id IS NULL AND lease_expires_at IS NULL
           AND retry_eligible_at IS NOT NULL AND last_error_code IS NOT NULL)
          OR
          (claim_state = 'COMPLETED' AND attempt_count > 0
           AND lease_id IS NULL AND lease_expires_at IS NULL
           AND retry_eligible_at IS NULL AND last_error_code IS NULL)
          OR
          (claim_state = 'DEAD_LETTERED' AND attempt_count > 0
           AND lease_id IS NULL AND lease_expires_at IS NULL
           AND retry_eligible_at IS NULL AND last_error_code IS NOT NULL)
        ))
);

CREATE TABLE public.ppl_hris_notification_receipt_streams (
    tenant_id BIGINT NOT NULL REFERENCES public.sys_service_tenants(tenant_id),
    intent_id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    terminal_state VARCHAR(24),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    PRIMARY KEY (tenant_id, intent_id, recipient_user_id),
    CONSTRAINT fk_ppl_hris_notification_receipt_stream_intent FOREIGN KEY
        (tenant_id, intent_id)
        REFERENCES public.ppl_hris_notification_intent_handoffs(tenant_id, intent_id),
    CONSTRAINT ck_ppl_hris_notification_receipt_stream_values CHECK (
        recipient_user_id > 0 AND last_sequence >= 0
        AND (terminal_state IS NULL OR terminal_state IN
             ('SUPPRESSED', 'DELIVERED', 'FAILED', 'DEAD_LETTERED', 'CANCELED')))
);

CREATE TABLE public.ppl_hris_notification_receipt_inbox (
    receipt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.sys_service_tenants(tenant_id),
    intent_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    receipt_sequence BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    decision_code VARCHAR(80) NOT NULL,
    reason_code VARCHAR(80),
    notification_id UUID,
    policy_revision BIGINT NOT NULL,
    template_version BIGINT NOT NULL,
    materialized_digest CHAR(64),
    provider_receipt_digest CHAR(64),
    attempt_number INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(80),
    retryable BOOLEAN NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    canonical_receipt_sha256 CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT uk_ppl_hris_notification_receipt_tenant
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT uk_ppl_hris_notification_receipt_sequence
        UNIQUE (tenant_id, intent_id, recipient_user_id, receipt_sequence),
    CONSTRAINT fk_ppl_hris_notification_receipt_intent FOREIGN KEY
        (tenant_id, intent_id)
        REFERENCES public.ppl_hris_notification_intent_handoffs(tenant_id, intent_id),
    CONSTRAINT ck_ppl_hris_notification_receipt_values CHECK (
        recipient_user_id > 0 AND receipt_sequence > 0
        AND state IN ('RECEIVED', 'ADMITTED', 'SUPPRESSED', 'MATERIALIZED',
                      'QUEUED', 'DELIVERED', 'FAILED', 'DEAD_LETTERED', 'CANCELED')
        AND channel IN ('NONE', 'IN_APP', 'EMAIL', 'PUSH', 'SMS')
        AND decision_code ~ '^[A-Z][A-Z0-9._-]{0,79}$'
        AND (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9._-]{0,79}$')
        AND policy_revision > 0 AND template_version > 0
        AND (materialized_digest IS NULL
             OR materialized_digest ~ '^[0-9a-f]{64}$')
        AND (provider_receipt_digest IS NULL
             OR provider_receipt_digest ~ '^[0-9a-f]{64}$')
        AND attempt_number >= 0
        AND (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9._-]{0,79}$')
        AND canonical_receipt_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_ppl_hris_notification_intent_ready
    ON public.ppl_hris_notification_intent_handoffs(
        tenant_id, requested_at, intent_id)
    WHERE decision = 'READY_LOCAL_ONLY';

CREATE INDEX idx_ppl_hris_notification_receipt_intent
    ON public.ppl_hris_notification_receipt_inbox(
        tenant_id, intent_id, recipient_user_id, receipt_sequence);

CREATE FUNCTION public.reject_ppl_hris_experience_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'HRIS experience handoff and receipt evidence is append-only'
        USING ERRCODE = '23514';
END
$$;

CREATE FUNCTION public.protect_ppl_hris_notification_receipt_stream()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    receipt_state VARCHAR(24);
    receipt_retryable BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRIS notification receipt stream cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.intent_id, NEW.recipient_user_id)
       IS DISTINCT FROM ROW(OLD.tenant_id, OLD.intent_id, OLD.recipient_user_id)
       OR NEW.last_sequence <= OLD.last_sequence
       OR OLD.terminal_state IS NOT NULL
       OR (OLD.terminal_state IS NULL AND NEW.terminal_state IS NULL
           AND NEW.last_sequence <> OLD.last_sequence + 1)
       OR (NEW.terminal_state IS NOT NULL
           AND NEW.last_sequence <> OLD.last_sequence + 1) THEN
        RAISE EXCEPTION 'HRIS notification receipt stream ordering is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.terminal_state IS NOT NULL THEN
        SELECT inbox.state,inbox.retryable
          INTO receipt_state,receipt_retryable
          FROM public.ppl_hris_notification_receipt_inbox inbox
         WHERE inbox.tenant_id=NEW.tenant_id
           AND inbox.intent_id=NEW.intent_id
           AND inbox.recipient_user_id=NEW.recipient_user_id
           AND inbox.receipt_sequence=NEW.last_sequence;
        IF NOT FOUND OR receipt_state <> NEW.terminal_state
           OR (NEW.terminal_state = 'FAILED' AND receipt_retryable) THEN
            RAISE EXCEPTION 'HRIS notification terminal state lacks exact receipt evidence'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

CREATE FUNCTION public.protect_ppl_hris_notification_handoff_claim()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HRIS notification handoff claim cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.intent_id, NEW.maximum_attempts)
       IS DISTINCT FROM ROW(OLD.tenant_id, OLD.intent_id, OLD.maximum_attempts)
       OR OLD.claim_state IN ('COMPLETED', 'DEAD_LETTERED')
       OR NOT (
          (OLD.claim_state = 'READY' AND NEW.claim_state = 'CLAIMED'
           AND NEW.attempt_count = 1)
          OR
          (OLD.claim_state = 'RETRY_WAIT' AND NEW.claim_state = 'CLAIMED'
           AND NEW.attempt_count = OLD.attempt_count + 1
           AND OLD.retry_eligible_at <= pg_catalog.clock_timestamp())
          OR
          (OLD.claim_state = 'CLAIMED'
           AND NEW.claim_state IN ('RETRY_WAIT', 'COMPLETED', 'DEAD_LETTERED')
           AND NEW.attempt_count = OLD.attempt_count)
       )
       OR (NEW.claim_state = 'CLAIMED'
           AND (NEW.lease_expires_at <= pg_catalog.clock_timestamp()
                OR NEW.lease_expires_at >
                   pg_catalog.clock_timestamp() + INTERVAL '5 minutes'))
       OR (NEW.claim_state = 'RETRY_WAIT'
           AND NEW.retry_eligible_at <= pg_catalog.clock_timestamp()) THEN
        RAISE EXCEPTION 'HRIS notification handoff claim transition is invalid'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.reject_ppl_hris_experience_evidence_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.protect_ppl_hris_notification_receipt_stream() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.protect_ppl_hris_notification_handoff_claim() FROM PUBLIC;

CREATE TRIGGER trg_ppl_hris_notification_intent_immutable
    BEFORE UPDATE OR DELETE ON public.ppl_hris_notification_intent_handoffs
    FOR EACH ROW EXECUTE FUNCTION public.reject_ppl_hris_experience_evidence_mutation();

CREATE TRIGGER trg_ppl_hris_notification_receipt_immutable
    BEFORE UPDATE OR DELETE ON public.ppl_hris_notification_receipt_inbox
    FOR EACH ROW EXECUTE FUNCTION public.reject_ppl_hris_experience_evidence_mutation();

CREATE TRIGGER trg_ppl_hris_notification_receipt_stream_guard
    BEFORE UPDATE OR DELETE ON public.ppl_hris_notification_receipt_streams
    FOR EACH ROW EXECUTE FUNCTION public.protect_ppl_hris_notification_receipt_stream();

CREATE TRIGGER trg_ppl_hris_notification_handoff_claim_guard
    BEFORE UPDATE OR DELETE ON public.ppl_hris_notification_handoff_claims
    FOR EACH ROW EXECUTE FUNCTION public.protect_ppl_hris_notification_handoff_claim();

ALTER TABLE public.ppl_hris_notification_intent_handoffs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ppl_hris_notification_intent_handoffs FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_hris_notification_intent_handoffs_tenant_policy
    ON public.ppl_hris_notification_intent_handoffs
    USING (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE public.ppl_hris_notification_handoff_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ppl_hris_notification_handoff_claims FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_hris_notification_handoff_claims_tenant_policy
    ON public.ppl_hris_notification_handoff_claims
    USING (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE public.ppl_hris_notification_receipt_streams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ppl_hris_notification_receipt_streams FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_hris_notification_receipt_streams_tenant_policy
    ON public.ppl_hris_notification_receipt_streams
    USING (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE public.ppl_hris_notification_receipt_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ppl_hris_notification_receipt_inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_hris_notification_receipt_inbox_tenant_policy
    ON public.ppl_hris_notification_receipt_inbox
    USING (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

COMMENT ON TABLE public.ppl_hris_notification_intent_handoffs IS
    'Default-off, local-only PDX-012 intent evidence; runtime Notification onboarding remains Central-owned.';
COMMENT ON TABLE public.ppl_hris_notification_receipt_inbox IS
    'Append-only ordered PDX-013 evidence for People-owned HRIS notification intents.';
COMMENT ON TABLE public.ppl_hris_notification_handoff_claims IS
    'Default-off bounded local claim state; no Notification runtime is onboarded by V56.';
