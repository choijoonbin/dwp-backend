-- MOD-SYS-HRIS-NAV-POLICY-R1: governed HR Operations navigation policy.
-- Navigation visibility never grants route or PAGE authority.

CREATE TABLE hris_navigation_policy_heads (
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    scope_kind VARCHAR(24) NOT NULL,
    legal_entity_public_id UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_heads PRIMARY KEY (tenant_id, policy_id),
    CONSTRAINT uq_hris_navigation_policy_scope
        UNIQUE NULLS NOT DISTINCT (tenant_id, scope_kind, legal_entity_public_id),
    CONSTRAINT ck_hris_navigation_policy_head_tenant CHECK (tenant_id > 0),
    CONSTRAINT ck_hris_navigation_policy_scope CHECK (
        (scope_kind = 'TENANT' AND legal_entity_public_id IS NULL)
        OR (scope_kind = 'LEGAL_ENTITY' AND legal_entity_public_id IS NOT NULL))
);

CREATE TABLE hris_navigation_policy_revisions (
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    rollout_mode VARCHAR(16) NOT NULL,
    workbench_id VARCHAR(64) NOT NULL,
    policy_payload JSONB NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    authored_by BIGINT NOT NULL,
    validated_at TIMESTAMPTZ,
    validation_digest CHAR(64),
    submitted_at TIMESTAMPTZ,
    submission_digest CHAR(64),
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    approval_digest CHAR(64),
    published_at TIMESTAMPTZ,
    restored_from_revision BIGINT,
    restored_from_digest CHAR(64),
    supersedes_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_revisions
        PRIMARY KEY (tenant_id, policy_id, revision),
    CONSTRAINT uq_hris_navigation_policy_revision_id
        UNIQUE (tenant_id, revision_id),
    CONSTRAINT fk_hris_navigation_policy_revision_head
        FOREIGN KEY (tenant_id, policy_id)
        REFERENCES hris_navigation_policy_heads (tenant_id, policy_id),
    CONSTRAINT fk_hris_navigation_policy_restore_source
        FOREIGN KEY (tenant_id, policy_id, restored_from_revision)
        REFERENCES hris_navigation_policy_revisions (tenant_id, policy_id, revision),
    CONSTRAINT fk_hris_navigation_policy_supersedes
        FOREIGN KEY (tenant_id, policy_id, supersedes_revision)
        REFERENCES hris_navigation_policy_revisions (tenant_id, policy_id, revision),
    CONSTRAINT ck_hris_navigation_policy_revision_positive
        CHECK (revision > 0 AND aggregate_version >= 0),
    CONSTRAINT ck_hris_navigation_policy_lifecycle CHECK (lifecycle_state IN (
        'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED')),
    CONSTRAINT ck_hris_navigation_policy_rollout
        CHECK (rollout_mode IN ('OFF', 'SHADOW', 'ENFORCED')),
    CONSTRAINT ck_hris_navigation_policy_workbench
        CHECK (workbench_id = 'HRIS-WB-HR-OPERATIONS'),
    CONSTRAINT ck_hris_navigation_policy_payload CHECK (
        jsonb_typeof(policy_payload) = 'object'
        AND policy_payload ?& ARRAY[
            'scopeKind', 'legalEntityPublicId', 'rolloutMode', 'workbenchId', 'groups']),
    CONSTRAINT ck_hris_navigation_policy_payload_digest
        CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_navigation_policy_validation CHECK (
        validation_digest IS NULL OR (
            validation_digest ~ '^[a-f0-9]{64}$' AND validated_at IS NOT NULL)),
    CONSTRAINT ck_hris_navigation_policy_submission CHECK (
        submission_digest IS NULL OR (
            submission_digest ~ '^[a-f0-9]{64}$' AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_hris_navigation_policy_approval CHECK (
        approval_digest IS NULL OR (
            approval_digest ~ '^[a-f0-9]{64}$'
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_hris_navigation_policy_maker_checker
        CHECK (approved_by IS NULL OR approved_by <> authored_by),
    CONSTRAINT ck_hris_navigation_policy_restore_lineage CHECK (
        (restored_from_revision IS NULL AND restored_from_digest IS NULL)
        OR (restored_from_revision IS NOT NULL
            AND restored_from_digest ~ '^[a-f0-9]{64}$'))
);

CREATE INDEX ix_hris_navigation_policy_revision_state
    ON hris_navigation_policy_revisions
        (tenant_id, policy_id, lifecycle_state, revision DESC);

CREATE TABLE hris_navigation_policy_effective (
    tenant_id BIGINT NOT NULL,
    effective_id UUID NOT NULL,
    scope_kind VARCHAR(24) NOT NULL,
    legal_entity_public_id UUID,
    policy_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    rollout_mode VARCHAR(16) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hris_navigation_policy_effective
        PRIMARY KEY (tenant_id, effective_id),
    CONSTRAINT uq_hris_navigation_policy_effective_scope
        UNIQUE NULLS NOT DISTINCT (tenant_id, scope_kind, legal_entity_public_id),
    CONSTRAINT fk_hris_navigation_policy_effective_revision
        FOREIGN KEY (tenant_id, policy_id, revision)
        REFERENCES hris_navigation_policy_revisions (tenant_id, policy_id, revision),
    CONSTRAINT uq_hris_navigation_policy_effective_revision_id
        UNIQUE (tenant_id, revision_id),
    CONSTRAINT ck_hris_navigation_policy_effective_scope CHECK (
        (scope_kind = 'TENANT' AND legal_entity_public_id IS NULL)
        OR (scope_kind = 'LEGAL_ENTITY' AND legal_entity_public_id IS NOT NULL)),
    CONSTRAINT ck_hris_navigation_policy_effective_digest
        CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_navigation_policy_effective_rollout
        CHECK (rollout_mode IN ('OFF', 'SHADOW', 'ENFORCED'))
);

CREATE TABLE hris_navigation_policy_command_receipts (
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    subject_id BIGINT NOT NULL,
    originating_action VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    policy_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    receipt_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_receipts PRIMARY KEY (tenant_id, receipt_id),
    CONSTRAINT uq_hris_navigation_policy_idempotency
        UNIQUE (tenant_id, subject_id, originating_action, idempotency_key),
    CONSTRAINT fk_hris_navigation_policy_receipt_revision
        FOREIGN KEY (tenant_id, policy_id, policy_revision)
        REFERENCES hris_navigation_policy_revisions (tenant_id, policy_id, revision),
    CONSTRAINT ck_hris_navigation_policy_receipt_subject CHECK (subject_id > 0),
    CONSTRAINT ck_hris_navigation_policy_receipt_key
        CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_hris_navigation_policy_receipt_digest
        CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_navigation_policy_receipt_payload
        CHECK (jsonb_typeof(receipt_payload) = 'object')
);

CREATE TABLE hris_navigation_policy_audit (
    tenant_id BIGINT NOT NULL,
    audit_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    action VARCHAR(96) NOT NULL,
    actor_id BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_audit PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT ck_hris_navigation_policy_audit_digest
        CHECK (evidence_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_hris_navigation_policy_audit_subject
    ON hris_navigation_policy_audit
        (tenant_id, policy_id, policy_revision, occurred_at, audit_id);

CREATE TABLE hris_navigation_policy_outbox (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_hris_navigation_policy_outbox PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT uq_hris_navigation_policy_outbox_fact
        UNIQUE (tenant_id, policy_id, policy_revision, event_type),
    CONSTRAINT ck_hris_navigation_policy_outbox_event
        CHECK (event_type = 'dwp.hris.navigation-policy.published.v1'),
    CONSTRAINT ck_hris_navigation_policy_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY[
            'policyId', 'revision', 'scopeKind', 'legalEntityPublicId',
            'rolloutMode', 'payloadDigest', 'publishedAt', 'eventSequence']),
    CONSTRAINT ck_hris_navigation_policy_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_hris_navigation_policy_outbox_pending
    ON hris_navigation_policy_outbox (created_at, event_id)
    WHERE published_at IS NULL;

CREATE TABLE hris_navigation_policy_step_up_replay (
    replay_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    challenge_id VARCHAR(160) NOT NULL,
    nonce VARCHAR(160) NOT NULL,
    command_contract_key VARCHAR(200) NOT NULL,
    activation_policy VARCHAR(120) NOT NULL,
    capability_contract_key VARCHAR(200) NOT NULL,
    context_key VARCHAR(500) NOT NULL,
    scope_ref VARCHAR(500) NOT NULL,
    target_type VARCHAR(160) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    target_version BIGINT NOT NULL,
    command_method VARCHAR(16) NOT NULL,
    command_path VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    decision_revision VARCHAR(200) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_step_up_replay
        PRIMARY KEY (tenant_id, replay_id),
    CONSTRAINT uq_hris_navigation_policy_step_up_challenge
        UNIQUE (tenant_id, issuer, challenge_id),
    CONSTRAINT uq_hris_navigation_policy_step_up_nonce
        UNIQUE (tenant_id, issuer, nonce),
    CONSTRAINT ck_hris_navigation_policy_step_up_actor
        CHECK (tenant_id > 0 AND actor_user_id > 0),
    CONSTRAINT ck_hris_navigation_policy_step_up_target_version
        CHECK (target_version >= 0),
    CONSTRAINT ck_hris_navigation_policy_step_up_method
        CHECK (command_method = 'POST'),
    CONSTRAINT ck_hris_navigation_policy_step_up_payload_digest
        CHECK (payload_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_hris_navigation_policy_step_up_expiry
    ON hris_navigation_policy_step_up_replay (expires_at, replay_id);

-- All runtime transactions bind the gateway-verified tenant with SET LOCAL.
ALTER TABLE hris_navigation_policy_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_revisions FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_effective ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_effective FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_command_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_audit FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_step_up_replay ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_step_up_replay FORCE ROW LEVEL SECURITY;

CREATE POLICY hris_navigation_policy_heads_tenant_policy
    ON hris_navigation_policy_heads
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_revisions_tenant_policy
    ON hris_navigation_policy_revisions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_effective_tenant_policy
    ON hris_navigation_policy_effective
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_receipts_tenant_policy
    ON hris_navigation_policy_command_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_audit_tenant_policy
    ON hris_navigation_policy_audit
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_outbox_tenant_policy
    ON hris_navigation_policy_outbox
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_step_up_replay_tenant_policy
    ON hris_navigation_policy_step_up_replay
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

CREATE OR REPLACE FUNCTION guard_hris_navigation_policy_published_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state = 'PUBLISHED'
       AND (TG_OP = 'DELETE' OR NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'published HRIS navigation policy is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_published_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_revisions
FOR EACH ROW EXECUTE FUNCTION guard_hris_navigation_policy_published_immutable();

CREATE OR REPLACE FUNCTION reject_hris_navigation_policy_immutable_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable HRIS navigation policy evidence cannot be changed'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_receipt_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_command_receipts
FOR EACH ROW EXECUTE FUNCTION reject_hris_navigation_policy_immutable_evidence();
CREATE TRIGGER trg_hris_navigation_policy_audit_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_audit
FOR EACH ROW EXECUTE FUNCTION reject_hris_navigation_policy_immutable_evidence();

CREATE OR REPLACE FUNCTION guard_hris_navigation_policy_outbox_fact()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.tenant_id, OLD.event_id, OLD.policy_id, OLD.policy_revision,
           OLD.event_type, OLD.payload, OLD.created_at)
       IS DISTINCT FROM
       ROW(NEW.tenant_id, NEW.event_id, NEW.policy_id, NEW.policy_revision,
           NEW.event_type, NEW.payload, NEW.created_at) THEN
        RAISE EXCEPTION 'immutable HRIS navigation policy outbox fact cannot be changed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_outbox_fact_immutable
BEFORE UPDATE ON hris_navigation_policy_outbox
FOR EACH ROW EXECUTE FUNCTION guard_hris_navigation_policy_outbox_fact();

CREATE TRIGGER trg_hris_navigation_policy_step_up_replay_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_step_up_replay
FOR EACH ROW EXECUTE FUNCTION reject_hris_navigation_policy_immutable_evidence();
