-- PER-004 only: immutable owner evidence and reference-only delivery. No owner content is stored.
ALTER TABLE hris_reference_configuration_versions
    ADD COLUMN owner_scope_digest CHAR(64) CHECK (owner_scope_digest ~ '^[a-f0-9]{64}$'),
    ADD COLUMN owner_preparation_receipt_id UUID,
    ADD COLUMN owner_preparation_evidence_digest CHAR(64),
    ADD COLUMN owner_finalization_receipt_id UUID,
    ADD COLUMN owner_finalization_evidence_digest CHAR(64),
    ADD COLUMN owner_validation_receipt_id UUID,
    ADD COLUMN owner_validation_evidence_digest CHAR(64),
    ADD COLUMN owner_simulation_receipt_id UUID,
    ADD COLUMN owner_simulation_evidence_digest CHAR(64);

CREATE UNIQUE INDEX uq_hris_per004_one_working_version
    ON hris_reference_configuration_versions (tenant_id, configuration_id)
    WHERE domain = 'PER' AND schema_key IN (
        'dwp.hris.performance.review-form.v1', 'dwp.hris.performance.rating-scale.v1',
        'dwp.hris.performance.policy.v1')
    AND lifecycle_state IN ('DRAFT','VALIDATED','SIMULATED','PENDING_APPROVAL','APPROVED','SCHEDULED');

CREATE TABLE hris_reference_configuration_owner_receipts (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    owner_receipt_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PREPARATION','FINALIZATION','VALIDATION','SIMULATION')),
    domain VARCHAR(8) NOT NULL CHECK (domain = 'PER'),
    schema_key VARCHAR(128) NOT NULL CHECK (schema_key IN (
        'dwp.hris.performance.review-form.v1', 'dwp.hris.performance.rating-scale.v1',
        'dwp.hris.performance.policy.v1')),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload_reference VARCHAR(256) NOT NULL CHECK (payload_reference ~ '^ref_per_artifact_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    payload_digest CHAR(64) NOT NULL CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
    scope_digest CHAR(64) NOT NULL CHECK (scope_digest ~ '^[a-f0-9]{64}$'),
    owner_service VARCHAR(64) NOT NULL CHECK (owner_service = 'dwp-people-server'),
    prior_owner_receipt_id UUID,
    evidence_digest CHAR(64) NOT NULL CHECK (evidence_digest ~ '^[a-f0-9]{64}$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    revalidate_at TIMESTAMPTZ NOT NULL CHECK (revalidate_at > occurred_at),
    impact_digest CHAR(64) CHECK (impact_digest ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY (tenant_id, owner_receipt_id),
    UNIQUE (tenant_id, configuration_id, configuration_version, kind),
    FOREIGN KEY (tenant_id, configuration_id, configuration_version)
        REFERENCES hris_reference_configuration_versions (tenant_id, configuration_id, version),
    FOREIGN KEY (tenant_id, prior_owner_receipt_id)
        REFERENCES hris_reference_configuration_owner_receipts (tenant_id, owner_receipt_id),
    CHECK ((kind = 'PREPARATION') = (prior_owner_receipt_id IS NULL)),
    CHECK ((kind = 'SIMULATION') = (impact_digest IS NOT NULL))
);

CREATE TABLE hris_reference_configuration_owner_outbox (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    event_id UUID NOT NULL,
    event_type VARCHAR(96) NOT NULL
        CHECK (event_type = 'dwp.hris.configuration.owner-binding-finalize-requested.v1'),
    configuration_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    platform_create_receipt_id UUID NOT NULL,
    owner_preparation_receipt_id UUID NOT NULL,
    create_subject_principal_public_id UUID NOT NULL,
    originating_action VARCHAR(128) NOT NULL CHECK (originating_action = 'configuration.version.create'),
    create_idempotency_key VARCHAR(128) NOT NULL,
    create_request_digest CHAR(64) NOT NULL CHECK (create_request_digest ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 8),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    quarantined_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, event_id),
    UNIQUE (tenant_id, configuration_id, configuration_version),
    UNIQUE (tenant_id, create_subject_principal_public_id, originating_action, create_idempotency_key),
    FOREIGN KEY (tenant_id, owner_preparation_receipt_id)
        REFERENCES hris_reference_configuration_owner_receipts (tenant_id, owner_receipt_id),
    FOREIGN KEY (tenant_id, platform_create_receipt_id)
        REFERENCES hris_reference_configuration_command_receipts (tenant_id, receipt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (acknowledged_at IS NULL OR quarantined_at IS NULL)
);

-- A negative answer is authoritative only after this durable exact-identity fence commits.
CREATE TABLE hris_reference_configuration_create_fences (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    identity_digest CHAR(64) NOT NULL CHECK (identity_digest ~ '^[a-f0-9]{64}$'),
    fence_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, identity_digest),
    UNIQUE (tenant_id, fence_id)
);

CREATE TABLE hris_reference_configuration_workload_replays (
    proof_id UUID PRIMARY KEY,
    request_nonce UUID NOT NULL UNIQUE CHECK (request_nonce <> proof_id),
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    binding_id VARCHAR(96) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION guard_hris_per004_owner_chain() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE prior hris_reference_configuration_owner_receipts; expected_kind TEXT;
BEGIN
    IF NEW.kind <> 'PREPARATION' THEN
        expected_kind := CASE NEW.kind WHEN 'FINALIZATION' THEN 'PREPARATION'
            WHEN 'VALIDATION' THEN 'FINALIZATION' WHEN 'SIMULATION' THEN 'VALIDATION' END;
        SELECT * INTO prior FROM hris_reference_configuration_owner_receipts
            WHERE tenant_id = NEW.tenant_id AND owner_receipt_id = NEW.prior_owner_receipt_id;
        IF NOT FOUND OR prior.kind <> expected_kind OR NEW.occurred_at < prior.occurred_at OR
           ROW(prior.configuration_id, prior.configuration_version, prior.domain, prior.schema_key,
               prior.schema_version, prior.payload_reference, prior.payload_digest, prior.scope_digest)
           IS DISTINCT FROM ROW(NEW.configuration_id, NEW.configuration_version, NEW.domain, NEW.schema_key,
               NEW.schema_version, NEW.payload_reference, NEW.payload_digest, NEW.scope_digest) THEN
            RAISE EXCEPTION 'PER004_OWNER_CHAIN_MISMATCH' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_hris_per004_owner_chain BEFORE INSERT
    ON hris_reference_configuration_owner_receipts FOR EACH ROW EXECUTE FUNCTION guard_hris_per004_owner_chain();
CREATE TRIGGER trg_hris_per004_owner_receipt_immutable BEFORE UPDATE OR DELETE
    ON hris_reference_configuration_owner_receipts FOR EACH ROW
    EXECUTE FUNCTION reject_hris_reference_configuration_immutable_row();
CREATE TRIGGER trg_hris_per004_fence_immutable BEFORE UPDATE OR DELETE
    ON hris_reference_configuration_create_fences FOR EACH ROW
    EXECUTE FUNCTION reject_hris_reference_configuration_immutable_row();

CREATE FUNCTION guard_hris_per004_version_owner_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE k TEXT; r hris_reference_configuration_owner_receipts; receipt UUID; evidence TEXT; old_receipt UUID;
    changed_bindings INTEGER := 0;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.owner_preparation_receipt_id IS NOT NULL
        AND ROW(NEW.scope_type, NEW.scope_id, NEW.owner_scope_digest)
            IS DISTINCT FROM ROW(OLD.scope_type, OLD.scope_id, OLD.owner_scope_digest) THEN
        RAISE EXCEPTION 'PER004_OWNER_SCOPE_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    FOR k IN SELECT unnest(ARRAY['preparation','finalization','validation','simulation']) LOOP
        receipt := (to_jsonb(NEW)->>('owner_' || k || '_receipt_id'))::UUID;
        evidence := to_jsonb(NEW)->>('owner_' || k || '_evidence_digest');
        IF TG_OP = 'UPDATE' THEN
            old_receipt := (to_jsonb(OLD)->>('owner_' || k || '_receipt_id'))::UUID;
            IF old_receipt IS DISTINCT FROM receipt THEN changed_bindings := changed_bindings + 1; END IF;
            IF old_receipt IS NOT NULL AND ROW(old_receipt, to_jsonb(OLD)->>('owner_' || k || '_evidence_digest'))
                IS DISTINCT FROM ROW(receipt, evidence) THEN
                RAISE EXCEPTION 'PER004_OWNER_BINDING_IMMUTABLE' USING ERRCODE = '23514';
            END IF;
            IF old_receipt IS DISTINCT FROM receipt AND NEW.aggregate_version <> OLD.aggregate_version + 1 THEN
                RAISE EXCEPTION 'PER004_OWNER_BINDING_CAS_REQUIRED' USING ERRCODE = '23514';
            END IF;
        END IF;
        IF (receipt IS NULL) <> (evidence IS NULL) THEN
            RAISE EXCEPTION 'PER004_OWNER_EVIDENCE_PAIR_REQUIRED' USING ERRCODE = '23514';
        END IF;
        IF receipt IS NOT NULL THEN
            SELECT * INTO r FROM hris_reference_configuration_owner_receipts
                WHERE tenant_id = NEW.tenant_id AND owner_receipt_id = receipt;
            IF NOT FOUND OR r.kind <> upper(k) OR ROW(r.configuration_id, r.configuration_version,
                r.domain, r.schema_key, r.schema_version, r.payload_reference, r.payload_digest, r.scope_digest, r.evidence_digest)
                IS DISTINCT FROM ROW(NEW.configuration_id, NEW.version, NEW.domain, NEW.schema_key,
                NEW.schema_version, NEW.payload_reference, NEW.payload_digest, NEW.owner_scope_digest, evidence) THEN
                RAISE EXCEPTION 'PER004_OWNER_TUPLE_MISMATCH' USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    IF changed_bindings > 1 OR TG_OP = 'INSERT' AND
        (NEW.owner_preparation_receipt_id IS NOT NULL OR NEW.owner_finalization_receipt_id IS NOT NULL
         OR NEW.owner_validation_receipt_id IS NOT NULL OR NEW.owner_simulation_receipt_id IS NOT NULL) THEN
        RAISE EXCEPTION 'PER004_ONE_OWNER_BINDING_PER_REVISION' USING ERRCODE = '23514';
    END IF;
    IF (NEW.owner_preparation_receipt_id IS NULL) <> (NEW.owner_scope_digest IS NULL) THEN
        RAISE EXCEPTION 'PER004_OWNER_SCOPE_REQUIRED' USING ERRCODE = '23514';
    END IF;
    IF NEW.owner_validation_receipt_id IS NOT NULL AND NEW.owner_finalization_receipt_id IS NULL
        OR NEW.owner_simulation_receipt_id IS NOT NULL AND NEW.owner_validation_receipt_id IS NULL THEN
        RAISE EXCEPTION 'PER004_OWNER_ACKNOWLEDGEMENT_REQUIRED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_hris_per004_version_owner_binding BEFORE INSERT OR UPDATE
    ON hris_reference_configuration_versions FOR EACH ROW EXECUTE FUNCTION guard_hris_per004_version_owner_binding();

-- Each committed owner binding gets its own exact immutable aggregate snapshot.
CREATE FUNCTION guard_hris_per004_committed_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.domain = 'PER' AND NEW.schema_key IN ('dwp.hris.performance.review-form.v1',
        'dwp.hris.performance.rating-scale.v1', 'dwp.hris.performance.policy.v1') THEN
        IF NOT EXISTS(SELECT 1 FROM hris_reference_configuration_versions v
            WHERE v.tenant_id = NEW.tenant_id AND v.configuration_id = NEW.configuration_id
              AND v.version = NEW.version AND v.owner_preparation_receipt_id IS NOT NULL)
            OR NOT EXISTS(SELECT 1 FROM hris_reference_configuration_version_history h
            WHERE h.tenant_id = NEW.tenant_id AND h.configuration_id = NEW.configuration_id
              AND h.version = NEW.version AND h.aggregate_version = NEW.aggregate_version
              AND h.snapshot = to_jsonb(NEW)) THEN
            RAISE EXCEPTION 'PER004_EXACT_OWNER_HISTORY_REQUIRED' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_hris_per004_committed_history AFTER INSERT OR UPDATE
    ON hris_reference_configuration_versions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION guard_hris_per004_committed_history();

CREATE FUNCTION guard_hris_per004_committed_outbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM hris_reference_configuration_owner_receipts r
        JOIN hris_reference_configuration_versions v ON v.tenant_id = r.tenant_id
            AND v.configuration_id = r.configuration_id AND v.version = r.configuration_version
        JOIN hris_reference_configuration_command_receipts c ON c.tenant_id = v.tenant_id
            AND c.configuration_id = v.configuration_id AND c.configuration_version = v.version
        WHERE r.tenant_id = NEW.tenant_id AND r.owner_receipt_id = NEW.owner_preparation_receipt_id
            AND r.kind = 'PREPARATION' AND r.configuration_id = NEW.configuration_id
            AND r.configuration_version = NEW.configuration_version
            AND v.owner_preparation_receipt_id = r.owner_receipt_id
            AND c.receipt_id = NEW.platform_create_receipt_id AND c.command_type = 'CREATE_VERSION'
            AND c.originating_action = NEW.originating_action AND c.idempotency_key = NEW.create_idempotency_key
            AND c.request_digest = NEW.create_request_digest
            AND c.receipt_payload->>'subjectPrincipalPublicId' = NEW.create_subject_principal_public_id::TEXT
            AND (NEW.acknowledged_at IS NULL OR v.owner_finalization_receipt_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'PER004_EXACT_CREATE_RECEIPT_REQUIRED' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_hris_per004_committed_outbox AFTER INSERT OR UPDATE
    ON hris_reference_configuration_owner_outbox DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION guard_hris_per004_committed_outbox();

CREATE FUNCTION guard_hris_per004_owner_outbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - ARRAY['attempt_count','next_attempt_at','acknowledged_at','quarantined_at'])
        IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['attempt_count','next_attempt_at','acknowledged_at','quarantined_at'])
        OR NEW.attempt_count < OLD.attempt_count
        OR OLD.acknowledged_at IS NOT NULL AND NEW IS DISTINCT FROM OLD
        OR OLD.quarantined_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'PER004_OWNER_DELIVERY_FACT_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_hris_per004_owner_outbox_immutable BEFORE UPDATE OR DELETE
    ON hris_reference_configuration_owner_outbox FOR EACH ROW EXECUTE FUNCTION guard_hris_per004_owner_outbox();

ALTER TABLE hris_reference_configuration_owner_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_owner_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_owner_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_owner_outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_create_fences ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_create_fences FORCE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_workload_replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE hris_reference_configuration_workload_replays FORCE ROW LEVEL SECURITY;
CREATE POLICY hris_per004_owner_receipts_tenant ON hris_reference_configuration_owner_receipts
    USING (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_per004_owner_outbox_tenant ON hris_reference_configuration_owner_outbox
    USING (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_per004_create_fences_tenant ON hris_reference_configuration_create_fences
    USING (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
CREATE POLICY hris_per004_workload_replays_tenant ON hris_reference_configuration_workload_replays
    USING (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = nullif(current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
