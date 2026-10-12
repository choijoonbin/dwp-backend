-- SYS BASE-TFR-SYS-007: lifecycle metadata only. Domain payloads remain in their owner SoR.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE hris_reference_configurations (
    tenant_id BIGINT NOT NULL,
    configuration_id UUID NOT NULL,
    configuration_key VARCHAR(128) NOT NULL,
    domain VARCHAR(8) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_reference_configurations
        PRIMARY KEY (tenant_id, configuration_id),
    CONSTRAINT uq_hris_reference_configurations_key
        UNIQUE (tenant_id, configuration_key),
    CONSTRAINT ck_hris_reference_configurations_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_hris_reference_configurations_domain
        CHECK (domain IN ('HRM', 'PER', 'TIM', 'PAY', 'SYS'))
);

CREATE TABLE hris_reference_configuration_versions (
    tenant_id BIGINT NOT NULL,
    configuration_id UUID NOT NULL,
    version BIGINT NOT NULL,
    domain VARCHAR(8) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    schema_key VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_reference VARCHAR(256) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    validation_receipt_id UUID,
    simulation_receipt_id UUID,
    approval_receipt_id UUID,
    retirement_approval_receipt_id UUID,
    impact_digest CHAR(64),
    approval_decision_digest CHAR(64),
    scheduled_at TIMESTAMPTZ,
    replacement_version BIGINT,
    supersedes_version BIGINT,
    reason_code VARCHAR(64),
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    authored_by BIGINT NOT NULL,
    approved_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_reference_configuration_versions
        PRIMARY KEY (tenant_id, configuration_id, version),
    CONSTRAINT fk_hris_reference_configuration_versions_owner
        FOREIGN KEY (tenant_id, configuration_id)
        REFERENCES hris_reference_configurations (tenant_id, configuration_id),
    CONSTRAINT ck_hris_reference_configuration_versions_version
        CHECK (version > 0 AND aggregate_version >= 0 AND schema_version > 0),
    CONSTRAINT ck_hris_reference_configuration_versions_domain
        CHECK (domain IN ('HRM', 'PER', 'TIM', 'PAY', 'SYS')),
    CONSTRAINT ck_hris_reference_configuration_versions_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'VALIDATED', 'SIMULATED', 'PENDING_APPROVAL', 'APPROVED',
            'SCHEDULED', 'PUBLISHED', 'REJECTED', 'RETIRED', 'FAILED_ACTIVATION')),
    CONSTRAINT ck_hris_reference_configuration_versions_window
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_hris_reference_configuration_versions_payload_ref
        CHECK (length(trim(payload_reference)) BETWEEN 1 AND 256),
    CONSTRAINT ck_hris_reference_configuration_versions_payload_digest
        CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_reference_configuration_versions_impact_digest
        CHECK (impact_digest IS NULL OR impact_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_reference_configuration_versions_approval_digest
        CHECK (approval_decision_digest IS NULL OR approval_decision_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_reference_configuration_versions_sod
        CHECK (approved_by IS NULL OR approved_by <> authored_by),
    CONSTRAINT fk_hris_reference_configuration_versions_replacement
        FOREIGN KEY (tenant_id, configuration_id, replacement_version)
        REFERENCES hris_reference_configuration_versions (tenant_id, configuration_id, version),
    CONSTRAINT fk_hris_reference_configuration_versions_supersedes
        FOREIGN KEY (tenant_id, configuration_id, supersedes_version)
        REFERENCES hris_reference_configuration_versions (tenant_id, configuration_id, version)
);

ALTER TABLE hris_reference_configuration_versions
    ADD CONSTRAINT ex_hris_reference_configuration_published_window
    EXCLUDE USING gist (
        tenant_id WITH =,
        configuration_id WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&)
    WHERE (lifecycle_state = 'PUBLISHED');

CREATE INDEX ix_hris_reference_configuration_versions_state
    ON hris_reference_configuration_versions (
        tenant_id, lifecycle_state, updated_at DESC, configuration_id, version DESC);

CREATE TABLE hris_reference_configuration_command_receipts (
    tenant_id BIGINT NOT NULL,
    receipt_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    configuration_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    receipt_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_reference_configuration_command_receipts
        PRIMARY KEY (tenant_id, receipt_id),
    CONSTRAINT uq_hris_reference_configuration_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_hris_reference_configuration_receipt_version
        FOREIGN KEY (tenant_id, configuration_id, configuration_version)
        REFERENCES hris_reference_configuration_versions (tenant_id, configuration_id, version),
    CONSTRAINT ck_hris_reference_configuration_receipt_key
        CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_hris_reference_configuration_receipt_digest
        CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_hris_reference_configuration_receipt_payload
        CHECK (jsonb_typeof(receipt_payload) = 'object')
);

CREATE TABLE hris_reference_configuration_outbox (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_hris_reference_configuration_outbox
        PRIMARY KEY (tenant_id, event_id),
    CONSTRAINT uq_hris_reference_configuration_outbox_fact
        UNIQUE (tenant_id, aggregate_id, aggregate_version, event_type),
    CONSTRAINT ck_hris_reference_configuration_outbox_event
        CHECK (event_type = 'dwp.hris.configuration.published.v1'),
    CONSTRAINT ck_hris_reference_configuration_outbox_payload
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_hris_reference_configuration_outbox_attempts
        CHECK (attempt_count >= 0)
);

CREATE INDEX ix_hris_reference_configuration_outbox_pending
    ON hris_reference_configuration_outbox (created_at, event_id)
    WHERE published_at IS NULL;

CREATE TABLE hris_reference_configuration_audit (
    tenant_id BIGINT NOT NULL,
    audit_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    action VARCHAR(96) NOT NULL,
    actor_id BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_hris_reference_configuration_audit
        PRIMARY KEY (tenant_id, audit_id),
    CONSTRAINT ck_hris_reference_configuration_audit_digest
        CHECK (evidence_digest ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_hris_reference_configuration_audit_subject
    ON hris_reference_configuration_audit (
        tenant_id, configuration_id, configuration_version, occurred_at, audit_id);

-- RLS is defense in depth. The application role owns these tables in existing deployments,
-- while restricted runtime/reporting roles must bind dwp.tenant_id for every transaction.
ALTER TABLE hris_reference_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_audit ENABLE ROW LEVEL SECURITY;

CREATE POLICY hris_reference_configurations_tenant_policy
    ON hris_reference_configurations
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_reference_configuration_versions_tenant_policy
    ON hris_reference_configuration_versions
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_reference_configuration_receipts_tenant_policy
    ON hris_reference_configuration_command_receipts
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_reference_configuration_outbox_tenant_policy
    ON hris_reference_configuration_outbox
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_reference_configuration_audit_tenant_policy
    ON hris_reference_configuration_audit
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);

CREATE OR REPLACE FUNCTION guard_hris_reference_configuration_immutable_content()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle_state IN ('PUBLISHED', 'RETIRED')
       AND ROW(
           OLD.domain,
           OLD.schema_key,
           OLD.schema_version,
           OLD.payload_reference,
           OLD.payload_digest,
           OLD.validation_receipt_id,
           OLD.simulation_receipt_id,
           OLD.approval_receipt_id,
           OLD.impact_digest,
           OLD.approval_decision_digest,
           OLD.effective_from)
           IS DISTINCT FROM ROW(
           NEW.domain,
           NEW.schema_key,
           NEW.schema_version,
           NEW.payload_reference,
           NEW.payload_digest,
           NEW.validation_receipt_id,
           NEW.simulation_receipt_id,
           NEW.approval_receipt_id,
           NEW.impact_digest,
           NEW.approval_decision_digest,
           NEW.effective_from) THEN
        RAISE EXCEPTION 'published HRIS reference configuration content is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_reference_configuration_immutable_content
BEFORE UPDATE ON hris_reference_configuration_versions
FOR EACH ROW EXECUTE FUNCTION guard_hris_reference_configuration_immutable_content();

CREATE OR REPLACE FUNCTION reject_hris_reference_configuration_immutable_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'immutable HRIS reference-configuration evidence cannot be changed'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_hris_reference_configuration_receipt_immutable
BEFORE UPDATE OR DELETE ON hris_reference_configuration_command_receipts
FOR EACH ROW EXECUTE FUNCTION reject_hris_reference_configuration_immutable_row();

CREATE TRIGGER trg_hris_reference_configuration_audit_immutable
BEFORE UPDATE OR DELETE ON hris_reference_configuration_audit
FOR EACH ROW EXECUTE FUNCTION reject_hris_reference_configuration_immutable_row();

CREATE OR REPLACE FUNCTION guard_hris_reference_configuration_outbox_fact()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        OLD.tenant_id,
        OLD.event_id,
        OLD.aggregate_id,
        OLD.aggregate_version,
        OLD.event_type,
        OLD.payload,
        OLD.created_at)
        IS DISTINCT FROM ROW(
        NEW.tenant_id,
        NEW.event_id,
        NEW.aggregate_id,
        NEW.aggregate_version,
        NEW.event_type,
        NEW.payload,
        NEW.created_at) THEN
        RAISE EXCEPTION 'immutable HRIS reference-configuration outbox fact cannot be changed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_hris_reference_configuration_outbox_fact_immutable
BEFORE UPDATE ON hris_reference_configuration_outbox
FOR EACH ROW EXECUTE FUNCTION guard_hris_reference_configuration_outbox_fact();
