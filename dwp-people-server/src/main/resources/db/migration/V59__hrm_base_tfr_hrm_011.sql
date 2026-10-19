-- BASE-TFR-HRM-011: relationship-grain compensation and employment-contract
-- durability, plus purpose-bound restricted worker/dependent source records.
-- Receipt, audit and outbox durability reuse the existing People stores.

CREATE TABLE ppl_compensation_basis (
    compensation_basis_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    assignment_id BIGINT NOT NULL,
    basis_type VARCHAR(40) NOT NULL,
    amount NUMERIC(19,6),
    currency_code CHAR(3),
    frequency_code VARCHAR(40),
    grade_public_id UUID,
    source_event_public_id UUID,
    valid_from DATE NOT NULL,
    valid_to DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_compensation_basis_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_compensation_basis_id
        UNIQUE (tenant_id, compensation_basis_id),
    CONSTRAINT fk_ppl_compensation_basis_assignment
        FOREIGN KEY (tenant_id, assignment_id)
        REFERENCES ppl_assignments(tenant_id, assignment_id),
    CONSTRAINT ck_ppl_compensation_basis_value CHECK (
        (amount IS NULL AND currency_code IS NULL)
        OR (amount IS NOT NULL AND amount >= 0
            AND currency_code ~ '^[A-Z]{3}$')),
    CONSTRAINT ck_ppl_compensation_basis_period CHECK (
        valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_ppl_compensation_basis_version CHECK (version >= 0),
    CONSTRAINT ex_ppl_compensation_basis_period EXCLUDE USING gist (
        tenant_id WITH =,
        assignment_id WITH =,
        basis_type WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&
    )
);

CREATE TABLE ppl_employment_contracts (
    employment_contract_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    work_relationship_id BIGINT NOT NULL,
    relationship_version BIGINT NOT NULL,
    assignment_id BIGINT NOT NULL,
    assignment_version BIGINT NOT NULL,
    compensation_basis_id BIGINT NOT NULL,
    compensation_basis_version BIGINT NOT NULL,
    contract_type VARCHAR(40) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    locale VARCHAR(35) NOT NULL,
    template_public_id UUID NOT NULL,
    template_version BIGINT NOT NULL,
    template_content_schema_version TEXT NOT NULL,
    template_source_revision BIGINT NOT NULL,
    template_published_at TIMESTAMPTZ NOT NULL,
    template_payload_digest CHAR(64) NOT NULL,
    template_content_object_public_id UUID NOT NULL,
    template_content_object_version BIGINT NOT NULL,
    template_content_sha256 CHAR(64) NOT NULL,
    template_approval_receipt_id UUID NOT NULL,
    snapshot_basis_type VARCHAR(40) NOT NULL,
    snapshot_amount NUMERIC(19,6) NOT NULL,
    snapshot_currency_code CHAR(3) NOT NULL,
    snapshot_frequency_code VARCHAR(40),
    generation_snapshot_digest CHAR(64) NOT NULL,
    artifact_request_id UUID,
    artifact_provider_receipt_id UUID,
    document_object_public_id UUID,
    document_object_version BIGINT,
    document_content_sha256 CHAR(64),
    signature_case_public_id UUID,
    valid_from DATE NOT NULL,
    valid_to DATE,
    supersedes_contract_id BIGINT,
    retention_class VARCHAR(40) NOT NULL DEFAULT 'EMPLOYMENT_CONTRACT',
    retention_until DATE,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_id UUID NOT NULL,
    version BIGINT NOT NULL,
    issued_at TIMESTAMPTZ,
    signed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_employment_contracts_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_employment_contracts_id
        UNIQUE (tenant_id, employment_contract_id),
    CONSTRAINT uk_ppl_employment_contracts_correlation
        UNIQUE (tenant_id, correlation_id),
    CONSTRAINT uk_ppl_employment_contracts_artifact_request
        UNIQUE (tenant_id, artifact_request_id),
    CONSTRAINT fk_ppl_employment_contracts_relationship
        FOREIGN KEY (tenant_id, work_relationship_id)
        REFERENCES ppl_work_relationships(tenant_id, work_relationship_id),
    CONSTRAINT fk_ppl_employment_contracts_assignment
        FOREIGN KEY (tenant_id, assignment_id)
        REFERENCES ppl_assignments(tenant_id, assignment_id),
    CONSTRAINT fk_ppl_employment_contracts_compensation_basis
        FOREIGN KEY (tenant_id, compensation_basis_id)
        REFERENCES ppl_compensation_basis(tenant_id, compensation_basis_id),
    CONSTRAINT fk_ppl_employment_contracts_supersedes
        FOREIGN KEY (tenant_id, supersedes_contract_id)
        REFERENCES ppl_employment_contracts(tenant_id, employment_contract_id),
    CONSTRAINT ck_ppl_employment_contracts_versions CHECK (
        relationship_version >= 0
        AND assignment_version > 0
        AND compensation_basis_version > 0
        AND template_version > 0
        AND template_source_revision > 0
        AND template_content_object_version > 0
        AND version > 0
        AND (document_object_version IS NULL OR document_object_version > 0)),
    CONSTRAINT ck_ppl_employment_contracts_template_schema CHECK (
        template_content_schema_version
            ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}\.v[1-9][0-9]*$'),
    CONSTRAINT ck_ppl_employment_contracts_hashes CHECK (
        template_payload_digest ~ '^[0-9a-f]{64}$'
        AND template_content_sha256 ~ '^[0-9a-f]{64}$'
        AND generation_snapshot_digest ~ '^[0-9a-f]{64}$'
        AND (document_content_sha256 IS NULL
             OR document_content_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ppl_employment_contracts_snapshot CHECK (
        snapshot_amount >= 0
        AND snapshot_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_ppl_employment_contracts_period CHECK (
        valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_ppl_employment_contracts_state CHECK (
        lifecycle_state IN (
            'DRAFT','ISSUED','SIGNED','ACTIVE','DECLINED',
            'SUPERSEDED','EXPIRED','REVOKED')),
    CONSTRAINT ck_ppl_employment_contracts_artifact_shape CHECK (
        (artifact_request_id IS NULL
         AND artifact_provider_receipt_id IS NULL
         AND document_object_public_id IS NULL
         AND document_object_version IS NULL
         AND document_content_sha256 IS NULL
         AND issued_at IS NULL)
        OR
        (artifact_request_id IS NOT NULL
         AND artifact_provider_receipt_id IS NOT NULL
         AND document_object_public_id IS NOT NULL
         AND document_object_version IS NOT NULL
         AND document_content_sha256 IS NOT NULL
         AND issued_at IS NOT NULL)),
    CONSTRAINT ck_ppl_employment_contracts_issued_artifact CHECK (
        lifecycle_state NOT IN (
            'ISSUED','SIGNED','ACTIVE','SUPERSEDED','EXPIRED','REVOKED')
        OR (artifact_request_id IS NOT NULL
            AND artifact_provider_receipt_id IS NOT NULL
            AND document_object_public_id IS NOT NULL
            AND document_object_version IS NOT NULL
            AND document_content_sha256 IS NOT NULL
            AND issued_at IS NOT NULL)),
    CONSTRAINT ck_ppl_employment_contracts_signature CHECK (
        lifecycle_state NOT IN ('SIGNED','ACTIVE') OR signed_at IS NOT NULL)
);

CREATE TABLE ppl_worker_restricted_tokens (
    worker_restricted_token_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    token_kind VARCHAR(40) NOT NULL,
    purpose_code VARCHAR(60) NOT NULL,
    jurisdiction_code VARCHAR(40) NOT NULL,
    vault_provider_key VARCHAR(80) NOT NULL,
    vault_token_ref VARCHAR(255) NOT NULL,
    token_revision BIGINT NOT NULL,
    masked_display VARCHAR(32),
    currency_code CHAR(3),
    payment_priority SMALLINT,
    residency_status VARCHAR(40),
    verification_state VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    primary_for_purpose BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from DATE NOT NULL,
    valid_to DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_worker_restricted_tokens_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_worker_restricted_tokens_id
        UNIQUE (tenant_id, worker_restricted_token_id),
    CONSTRAINT uk_ppl_worker_restricted_tokens_vault UNIQUE (
        tenant_id, vault_provider_key, vault_token_ref, token_revision),
    CONSTRAINT fk_ppl_worker_restricted_tokens_worker
        FOREIGN KEY (tenant_id, worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT ck_ppl_worker_restricted_tokens_kind CHECK (
        token_kind IN ('TAX_IDENTITY','PAYMENT_DESTINATION')),
    CONSTRAINT ck_ppl_worker_restricted_tokens_revision CHECK (
        token_revision > 0 AND version >= 0),
    CONSTRAINT ck_ppl_worker_restricted_tokens_verify CHECK (
        verification_state IN (
            'UNVERIFIED','PENDING','VERIFIED','REJECTED','REVOKED')),
    CONSTRAINT ck_ppl_worker_restricted_tokens_shape CHECK (
        (token_kind = 'TAX_IDENTITY'
         AND currency_code IS NULL
         AND payment_priority IS NULL)
        OR
        (token_kind = 'PAYMENT_DESTINATION'
         AND currency_code ~ '^[A-Z]{3}$'
         AND payment_priority IS NOT NULL
         AND payment_priority > 0)),
    CONSTRAINT ck_ppl_worker_restricted_tokens_period CHECK (
        valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ex_ppl_worker_restricted_tokens_primary EXCLUDE USING gist (
        tenant_id WITH =,
        worker_id WITH =,
        token_kind WITH =,
        purpose_code WITH =,
        daterange(valid_from, valid_to, '[)') WITH &&
    ) WHERE (primary_for_purpose)
);

CREATE TABLE ppl_dependents (
    dependent_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    related_person_public_id UUID,
    relationship_type VARCHAR(40) NOT NULL,
    encrypted_profile BYTEA NOT NULL,
    profile_schema_version INTEGER NOT NULL,
    key_reference VARCHAR(255) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_dependents_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_dependents_id UNIQUE (tenant_id, dependent_id),
    CONSTRAINT fk_ppl_dependents_person
        FOREIGN KEY (tenant_id, person_id)
        REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT ck_ppl_dependents_schema CHECK (profile_schema_version > 0),
    CONSTRAINT ck_ppl_dependents_period CHECK (
        valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_ppl_dependents_version CHECK (version >= 0)
);

CREATE INDEX idx_ppl_compensation_basis_assignment_period
    ON ppl_compensation_basis(tenant_id, assignment_id, valid_from, valid_to);
CREATE INDEX idx_ppl_compensation_basis_tenant_public
    ON ppl_compensation_basis(tenant_id, public_id);

CREATE INDEX idx_ppl_employment_contracts_relationship_period
    ON ppl_employment_contracts(
        tenant_id, work_relationship_id, valid_from, valid_to);
CREATE INDEX idx_ppl_employment_contracts_assignment
    ON ppl_employment_contracts(tenant_id, assignment_id);
CREATE INDEX idx_ppl_employment_contracts_compensation_basis
    ON ppl_employment_contracts(tenant_id, compensation_basis_id);
CREATE INDEX idx_ppl_employment_contracts_lifecycle_issued
    ON ppl_employment_contracts(tenant_id, lifecycle_state, issued_at);
CREATE INDEX idx_ppl_employment_contracts_tenant_public
    ON ppl_employment_contracts(tenant_id, public_id);

CREATE INDEX idx_ppl_worker_restricted_tokens_worker_period
    ON ppl_worker_restricted_tokens(
        tenant_id, worker_id, token_kind, valid_from, valid_to);
CREATE INDEX idx_ppl_worker_restricted_tokens_tenant_public
    ON ppl_worker_restricted_tokens(tenant_id, public_id);

CREATE INDEX idx_ppl_dependents_person_period
    ON ppl_dependents(tenant_id, person_id, valid_from, valid_to);
CREATE INDEX idx_ppl_dependents_tenant_public
    ON ppl_dependents(tenant_id, public_id);

CREATE FUNCTION public.ppl_guard_compensation_basis()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'compensation basis history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    IF ROW(NEW.compensation_basis_id, NEW.public_id, NEW.tenant_id,
           NEW.assignment_id, NEW.basis_type, NEW.valid_from,
           NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.compensation_basis_id, OLD.public_id, OLD.tenant_id,
           OLD.assignment_id, OLD.basis_type, OLD.valid_from,
           OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'compensation basis identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.version <= 0 THEN
        RAISE EXCEPTION 'compensation basis version must advance to a positive value exactly once'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_compensation_basis() FROM PUBLIC;

CREATE TRIGGER trg_ppl_compensation_basis_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_compensation_basis
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_compensation_basis();

CREATE FUNCTION public.ppl_guard_employment_contract()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'employment contract history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state NOT IN ('DRAFT','ISSUED') THEN
            RAISE EXCEPTION 'employment contract must begin DRAFT or ISSUED'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.lifecycle_state = 'ISSUED'
           AND (NEW.version <= 0 OR NEW.issued_at IS NULL) THEN
            RAISE EXCEPTION 'initial ISSUED employment contract requires a positive persisted version and artifact evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.employment_contract_id, NEW.public_id, NEW.tenant_id,
           NEW.work_relationship_id, NEW.relationship_version,
           NEW.assignment_id, NEW.assignment_version,
           NEW.compensation_basis_id, NEW.compensation_basis_version,
           NEW.contract_type, NEW.locale,
           NEW.template_public_id, NEW.template_version,
           NEW.template_content_schema_version,
           NEW.template_source_revision, NEW.template_published_at,
           NEW.template_payload_digest,
           NEW.template_content_object_public_id,
           NEW.template_content_object_version,
           NEW.template_content_sha256,
           NEW.template_approval_receipt_id,
           NEW.snapshot_basis_type, NEW.snapshot_amount,
           NEW.snapshot_currency_code, NEW.snapshot_frequency_code,
           NEW.generation_snapshot_digest,
           NEW.artifact_request_id, NEW.artifact_provider_receipt_id,
           NEW.document_object_public_id, NEW.document_object_version,
           NEW.document_content_sha256,
           NEW.valid_from, NEW.valid_to, NEW.supersedes_contract_id,
           NEW.correlation_id, NEW.issued_at,
           NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.employment_contract_id, OLD.public_id, OLD.tenant_id,
           OLD.work_relationship_id, OLD.relationship_version,
           OLD.assignment_id, OLD.assignment_version,
           OLD.compensation_basis_id, OLD.compensation_basis_version,
           OLD.contract_type, OLD.locale,
           OLD.template_public_id, OLD.template_version,
           OLD.template_content_schema_version,
           OLD.template_source_revision, OLD.template_published_at,
           OLD.template_payload_digest,
           OLD.template_content_object_public_id,
           OLD.template_content_object_version,
           OLD.template_content_sha256,
           OLD.template_approval_receipt_id,
           OLD.snapshot_basis_type, OLD.snapshot_amount,
           OLD.snapshot_currency_code, OLD.snapshot_frequency_code,
           OLD.generation_snapshot_digest,
           OLD.artifact_request_id, OLD.artifact_provider_receipt_id,
           OLD.document_object_public_id, OLD.document_object_version,
           OLD.document_content_sha256,
           OLD.valid_from, OLD.valid_to, OLD.supersedes_contract_id,
           OLD.correlation_id, OLD.issued_at,
           OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'employment contract snapshot, template, artifact and document evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.lifecycle_state = 'DRAFT' AND NEW.lifecycle_state = 'ISSUED')
        OR (OLD.lifecycle_state = 'ISSUED'
            AND NEW.lifecycle_state IN ('SIGNED','DECLINED'))
        OR (OLD.lifecycle_state = 'SIGNED'
            AND NEW.lifecycle_state IN ('ACTIVE','REVOKED'))
        OR (OLD.lifecycle_state = 'ACTIVE'
            AND NEW.lifecycle_state IN ('SUPERSEDED','EXPIRED'))
    ) THEN
        RAISE EXCEPTION 'illegal employment contract lifecycle transition'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'employment contract version must advance exactly once'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.lifecycle_state = 'ISSUED' AND NEW.lifecycle_state = 'SIGNED' THEN
        IF NEW.signature_case_public_id IS NULL OR NEW.signed_at IS NULL THEN
            RAISE EXCEPTION 'SIGNED employment contract requires signature evidence'
                USING ERRCODE = '23514';
        END IF;
    ELSIF ROW(NEW.signature_case_public_id, NEW.signed_at)
          IS DISTINCT FROM
          ROW(OLD.signature_case_public_id, OLD.signed_at) THEN
        RAISE EXCEPTION 'employment contract signature evidence can change only when signing'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_employment_contract() FROM PUBLIC;

CREATE TRIGGER trg_ppl_employment_contract_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_employment_contracts
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_employment_contract();

CREATE FUNCTION public.ppl_guard_worker_restricted_token()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'restricted worker token history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    IF ROW(NEW.worker_restricted_token_id, NEW.public_id, NEW.tenant_id,
           NEW.worker_id, NEW.token_kind, NEW.purpose_code,
           NEW.jurisdiction_code, NEW.vault_provider_key,
           NEW.vault_token_ref, NEW.token_revision,
           NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.worker_restricted_token_id, OLD.public_id, OLD.tenant_id,
           OLD.worker_id, OLD.token_kind, OLD.purpose_code,
           OLD.jurisdiction_code, OLD.vault_provider_key,
           OLD.vault_token_ref, OLD.token_revision,
           OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'restricted worker token vault identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.version <= 0 THEN
        RAISE EXCEPTION 'restricted worker token version must advance to a positive value exactly once'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_worker_restricted_token() FROM PUBLIC;

CREATE TRIGGER trg_ppl_worker_restricted_token_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_worker_restricted_tokens
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_worker_restricted_token();

CREATE FUNCTION public.ppl_guard_dependent()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'dependent history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    IF ROW(NEW.dependent_id, NEW.public_id, NEW.tenant_id,
           NEW.person_id, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.dependent_id, OLD.public_id, OLD.tenant_id,
           OLD.person_id, OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'dependent owner identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.version <= 0 THEN
        RAISE EXCEPTION 'dependent version must advance to a positive value exactly once'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_dependent() FROM PUBLIC;

CREATE TRIGGER trg_ppl_dependent_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ppl_dependents
    FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_dependent();

ALTER TABLE ppl_compensation_basis ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_compensation_basis FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_compensation_basis_tenant_policy
    ON ppl_compensation_basis
    USING (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_employment_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_employment_contracts FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_employment_contracts_tenant_policy
    ON ppl_employment_contracts
    USING (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_worker_restricted_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_worker_restricted_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_worker_restricted_tokens_tenant_policy
    ON ppl_worker_restricted_tokens
    USING (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

ALTER TABLE ppl_dependents ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_dependents FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_dependents_tenant_policy
    ON ppl_dependents
    USING (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(
            pg_catalog.current_setting('dwp.tenant_id', true), '')::BIGINT);

COMMENT ON TABLE ppl_compensation_basis IS
    'Tenant-scoped effective-dated assignment compensation basis; never a payroll result.';
COMMENT ON TABLE ppl_employment_contracts IS
    'Immutable employment-contract template, input snapshot, artifact and document evidence.';
COMMENT ON TABLE ppl_worker_restricted_tokens IS
    'Opaque purpose-bound vault references; clear tax and payment values are forbidden.';
COMMENT ON TABLE ppl_dependents IS
    'Envelope-encrypted dependent profile with no clear profile payload.';
