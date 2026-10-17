-- R5: seven-workbench navigation policy aggregate v2.
-- V326 rows and digests remain byte-for-byte immutable; v2 has a separate aggregate identity.

CREATE TABLE hris_navigation_policy_v2_heads (
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    scope_kind VARCHAR(24) NOT NULL,
    legal_entity_public_id UUID,
    model_version SMALLINT NOT NULL DEFAULT 2,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_v2_heads PRIMARY KEY (tenant_id, policy_id),
    CONSTRAINT uq_hris_navigation_policy_v2_scope
        UNIQUE NULLS NOT DISTINCT (tenant_id, scope_kind, legal_entity_public_id),
    CONSTRAINT ck_hris_navigation_policy_v2_head CHECK (
        tenant_id > 0 AND model_version = 2
        AND ((scope_kind = 'TENANT' AND legal_entity_public_id IS NULL)
          OR (scope_kind = 'LEGAL_ENTITY' AND legal_entity_public_id IS NOT NULL)))
);

CREATE TABLE hris_navigation_policy_v2_revisions (
    tenant_id BIGINT NOT NULL,
    policy_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    rollout_mode VARCHAR(16) NOT NULL,
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
    topology_revision VARCHAR(64) NOT NULL,
    topology_sha256 CHAR(64) NOT NULL,
    ia_register_sha256 CHAR(64) NOT NULL,
    shell_register_sha256 CHAR(64) NOT NULL,
    task_group_register_sha256 CHAR(64) NOT NULL,
    generated_ia_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_v2_revisions
        PRIMARY KEY (tenant_id, policy_id, revision),
    CONSTRAINT uq_hris_navigation_policy_v2_revision_id
        UNIQUE (tenant_id, revision_id),
    CONSTRAINT uq_hris_navigation_policy_v2_revision_identity
        UNIQUE (tenant_id, policy_id, revision, revision_id, payload_digest),
    CONSTRAINT fk_hris_navigation_policy_v2_revision_head
        FOREIGN KEY (tenant_id, policy_id)
        REFERENCES hris_navigation_policy_v2_heads (tenant_id, policy_id),
    CONSTRAINT fk_hris_navigation_policy_v2_restore_source
        FOREIGN KEY (tenant_id, policy_id, restored_from_revision)
        REFERENCES hris_navigation_policy_v2_revisions (tenant_id, policy_id, revision),
    CONSTRAINT fk_hris_navigation_policy_v2_supersedes
        FOREIGN KEY (tenant_id, policy_id, supersedes_revision)
        REFERENCES hris_navigation_policy_v2_revisions (tenant_id, policy_id, revision),
    CONSTRAINT ck_hris_navigation_policy_v2_revision CHECK (
        revision > 0 AND aggregate_version >= 0
        AND lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED')
        AND rollout_mode IN ('OFF', 'SHADOW', 'ENFORCED')
        AND topology_revision = 'HRIS-IA-98-WB7-G46-N97-R5'
        AND topology_sha256 =
            '4d27b3b8958d591968ed42e7ccd6387664fff66a364adfc83a8a5b7f223a6d8a'
        AND ia_register_sha256 =
            'ab506a2bb2f80c8ab81a4b9d575374d6bda83b312d11dce07495d4812bdf4686'
        AND shell_register_sha256 =
            'f85ee40a66ce4100c5578fd3ca96bc89b652b0762e19d74cc7aeb76338009000'
        AND task_group_register_sha256 =
            'f0b1ebb316e4d5944aac90002e144f25d8c1318216b6733e674cdc8c5042872b'
        AND generated_ia_sha256 =
            '584cc32f845720ad06fb1bdcea7c967026c0e3a648f70e0215fd8a95a6b055bd'),
    CONSTRAINT ck_hris_navigation_policy_v2_payload CHECK (
        jsonb_typeof(policy_payload) = 'object'
        AND policy_payload ->> 'schemaVersion' = '2'
        AND policy_payload ?& ARRAY[
            'scopeKind', 'legalEntityPublicId', 'rolloutMode', 'workbenches']
        AND policy_payload ->> 'rolloutMode' = rollout_mode
        AND jsonb_typeof(policy_payload -> 'workbenches') = 'array'
        AND jsonb_array_length(policy_payload -> 'workbenches') = 7),
    CONSTRAINT ck_hris_navigation_policy_v2_digests CHECK (
        payload_digest ~ '^[a-f0-9]{64}$'
        AND topology_sha256 ~ '^[a-f0-9]{64}$'
        AND ia_register_sha256 ~ '^[a-f0-9]{64}$'
        AND shell_register_sha256 ~ '^[a-f0-9]{64}$'
        AND task_group_register_sha256 ~ '^[a-f0-9]{64}$'
        AND generated_ia_sha256 ~ '^[a-f0-9]{64}$'
        AND (validation_digest IS NULL OR
            (validation_digest ~ '^[a-f0-9]{64}$' AND validated_at IS NOT NULL))
        AND (submission_digest IS NULL OR
            (submission_digest ~ '^[a-f0-9]{64}$' AND submitted_at IS NOT NULL))
        AND (approval_digest IS NULL OR
            (approval_digest ~ '^[a-f0-9]{64}$'
             AND approved_by IS NOT NULL AND approved_at IS NOT NULL))
        AND ((restored_from_revision IS NULL AND restored_from_digest IS NULL)
          OR (restored_from_revision IS NOT NULL
              AND restored_from_digest ~ '^[a-f0-9]{64}$'))),
    CONSTRAINT ck_hris_navigation_policy_v2_maker_checker
        CHECK (approved_by IS NULL OR approved_by <> authored_by),
    CONSTRAINT ck_hris_navigation_policy_v2_publication_state CHECK (
        (lifecycle_state = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (lifecycle_state <> 'PUBLISHED' AND published_at IS NULL))
);

CREATE INDEX ix_hris_navigation_policy_v2_revision_state
    ON hris_navigation_policy_v2_revisions
        (tenant_id, policy_id, lifecycle_state, revision DESC);

CREATE TABLE hris_navigation_policy_v2_effective (
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
    CONSTRAINT pk_hris_navigation_policy_v2_effective
        PRIMARY KEY (tenant_id, effective_id),
    CONSTRAINT uq_hris_navigation_policy_v2_effective_scope
        UNIQUE NULLS NOT DISTINCT (tenant_id, scope_kind, legal_entity_public_id),
    CONSTRAINT fk_hris_navigation_policy_v2_effective_revision
        FOREIGN KEY (tenant_id, policy_id, revision, revision_id, payload_digest)
        REFERENCES hris_navigation_policy_v2_revisions
            (tenant_id, policy_id, revision, revision_id, payload_digest),
    CONSTRAINT uq_hris_navigation_policy_v2_effective_revision_id
        UNIQUE (tenant_id, revision_id),
    CONSTRAINT ck_hris_navigation_policy_v2_effective CHECK (
        ((scope_kind = 'TENANT' AND legal_entity_public_id IS NULL)
          OR (scope_kind = 'LEGAL_ENTITY' AND legal_entity_public_id IS NOT NULL))
        AND payload_digest ~ '^[a-f0-9]{64}$'
        AND rollout_mode IN ('OFF', 'SHADOW', 'ENFORCED'))
);

CREATE TABLE hris_navigation_policy_v2_command_receipts (
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    subject_id BIGINT NOT NULL,
    originating_action VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    policy_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    receipt_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_v2_receipts
        PRIMARY KEY (tenant_id, receipt_id),
    CONSTRAINT uq_hris_navigation_policy_v2_idempotency
        UNIQUE (tenant_id, subject_id, originating_action, idempotency_key),
    CONSTRAINT fk_hris_navigation_policy_v2_receipt_revision
        FOREIGN KEY (tenant_id, policy_id, policy_revision, revision_id, payload_digest)
        REFERENCES hris_navigation_policy_v2_revisions
            (tenant_id, policy_id, revision, revision_id, payload_digest),
    CONSTRAINT ck_hris_navigation_policy_v2_receipt CHECK (
        subject_id > 0 AND length(idempotency_key) BETWEEN 16 AND 128
        AND request_digest ~ '^[a-f0-9]{64}$'
        AND payload_digest ~ '^[a-f0-9]{64}$'
        AND jsonb_typeof(receipt_payload) = 'object')
);

CREATE TABLE hris_navigation_policy_v2_audit (
    tenant_id BIGINT NOT NULL,
    audit_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    action VARCHAR(96) NOT NULL,
    actor_id BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_navigation_policy_v2_audit
        PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT fk_hris_navigation_policy_v2_audit_revision
        FOREIGN KEY (tenant_id, policy_id, policy_revision, revision_id, payload_digest)
        REFERENCES hris_navigation_policy_v2_revisions
            (tenant_id, policy_id, revision, revision_id, payload_digest),
    CONSTRAINT ck_hris_navigation_policy_v2_audit_digest
        CHECK (evidence_digest ~ '^[a-f0-9]{64}$'
            AND payload_digest ~ '^[a-f0-9]{64}$')
);

CREATE TABLE hris_navigation_policy_v2_outbox (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    revision_id UUID NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_hris_navigation_policy_v2_outbox
        PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT uq_hris_navigation_policy_v2_outbox_fact
        UNIQUE (tenant_id, policy_id, policy_revision, event_type),
    CONSTRAINT fk_hris_navigation_policy_v2_outbox_revision
        FOREIGN KEY (tenant_id, policy_id, policy_revision, revision_id, payload_digest)
        REFERENCES hris_navigation_policy_v2_revisions
            (tenant_id, policy_id, revision, revision_id, payload_digest),
    CONSTRAINT ck_hris_navigation_policy_v2_outbox CHECK (
        event_type = 'dwp.hris.navigation-policy.aggregate.published.v2'
        AND jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY[
            'schemaVersion', 'policyId', 'revision', 'scopeKind',
            'legalEntityPublicId', 'rolloutMode', 'payloadDigest',
            'topologyRevision', 'topologySha256', 'iaRegisterSha256',
            'shellRegisterSha256', 'taskGroupRegisterSha256',
            'generatedIaSha256', 'publishedAt', 'eventSequence']
        AND payload_digest ~ '^[a-f0-9]{64}$'
        AND attempt_count >= 0)
);

CREATE INDEX ix_hris_navigation_policy_v2_outbox_pending
    ON hris_navigation_policy_v2_outbox (created_at, event_id)
    WHERE published_at IS NULL;

-- Read-only bridge proves preservation without making any V1 write depend on V2.
CREATE VIEW hris_navigation_policy_v2_legacy_evidence
WITH (security_invoker = true) AS
SELECT tenant_id,
       policy_id AS v1_policy_id,
       revision AS v1_revision,
       revision_id AS v1_revision_id,
       payload_digest AS v1_payload_digest,
       lifecycle_state AS v1_lifecycle_state,
       'PRESERVED_V1'::VARCHAR(32) AS preservation_state
  FROM hris_navigation_policy_revisions;

CREATE OR REPLACE FUNCTION reject_hris_navigation_policy_v2_legacy_view_write()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'HRIS navigation V1 preservation evidence is read only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_v2_legacy_view_read_only
INSTEAD OF INSERT OR UPDATE OR DELETE
ON hris_navigation_policy_v2_legacy_evidence
FOR EACH ROW EXECUTE FUNCTION reject_hris_navigation_policy_v2_legacy_view_write();

ALTER TABLE hris_navigation_policy_v2_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_revisions FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_effective ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_effective FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_command_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_audit FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_navigation_policy_v2_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY hris_navigation_policy_v2_heads_tenant
    ON hris_navigation_policy_v2_heads
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_v2_revisions_tenant
    ON hris_navigation_policy_v2_revisions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_v2_effective_tenant
    ON hris_navigation_policy_v2_effective
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_v2_receipts_tenant
    ON hris_navigation_policy_v2_command_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_v2_audit_tenant
    ON hris_navigation_policy_v2_audit
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_navigation_policy_v2_outbox_tenant
    ON hris_navigation_policy_v2_outbox
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE OR REPLACE FUNCTION guard_hris_navigation_policy_v2_published_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state = 'PUBLISHED'
       AND (TG_OP = 'DELETE' OR NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'published HRIS navigation aggregate v2 is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_v2_published_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_v2_revisions
FOR EACH ROW EXECUTE FUNCTION guard_hris_navigation_policy_v2_published_immutable();

CREATE OR REPLACE FUNCTION reject_hris_navigation_policy_v2_evidence_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable HRIS navigation aggregate v2 evidence cannot be changed'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_v2_receipt_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_v2_command_receipts
FOR EACH ROW EXECUTE FUNCTION reject_hris_navigation_policy_v2_evidence_change();
CREATE TRIGGER trg_hris_navigation_policy_v2_audit_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_v2_audit
FOR EACH ROW EXECUTE FUNCTION reject_hris_navigation_policy_v2_evidence_change();
CREATE OR REPLACE FUNCTION guard_hris_navigation_policy_v2_outbox_fact()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'immutable HRIS navigation aggregate v2 outbox fact cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(OLD.tenant_id, OLD.event_id, OLD.policy_id, OLD.policy_revision,
           OLD.revision_id, OLD.payload_digest,
           OLD.event_type, OLD.payload, OLD.created_at)
       IS DISTINCT FROM
       ROW(NEW.tenant_id, NEW.event_id, NEW.policy_id, NEW.policy_revision,
           NEW.revision_id, NEW.payload_digest,
           NEW.event_type, NEW.payload, NEW.created_at) THEN
        RAISE EXCEPTION 'immutable HRIS navigation aggregate v2 outbox fact cannot be changed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_v2_outbox_fact_immutable
BEFORE UPDATE OR DELETE ON hris_navigation_policy_v2_outbox
FOR EACH ROW EXECUTE FUNCTION guard_hris_navigation_policy_v2_outbox_fact();

CREATE OR REPLACE FUNCTION validate_hris_navigation_policy_v2_effective()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM hris_navigation_policy_v2_revisions r
         WHERE r.tenant_id = NEW.tenant_id
           AND r.policy_id = NEW.policy_id
           AND r.revision = NEW.revision
           AND r.revision_id = NEW.revision_id
           AND r.payload_digest = NEW.payload_digest
           AND r.lifecycle_state = 'PUBLISHED'
           AND r.rollout_mode = NEW.rollout_mode
           AND r.published_at = NEW.published_at
           AND r.policy_payload ->> 'scopeKind' = NEW.scope_kind
           AND ((NEW.scope_kind = 'TENANT'
                 AND r.policy_payload ->> 'legalEntityPublicId' IS NULL)
             OR (NEW.scope_kind = 'LEGAL_ENTITY'
                 AND r.policy_payload ->> 'legalEntityPublicId' =
                     NEW.legal_entity_public_id::TEXT))) THEN
        RAISE EXCEPTION 'effective HRIS navigation aggregate must match exact published revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_navigation_policy_v2_effective_exact
BEFORE INSERT OR UPDATE ON hris_navigation_policy_v2_effective
FOR EACH ROW EXECUTE FUNCTION validate_hris_navigation_policy_v2_effective();
