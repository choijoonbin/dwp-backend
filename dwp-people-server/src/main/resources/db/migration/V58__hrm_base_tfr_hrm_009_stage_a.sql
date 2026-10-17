-- BASE-TFR-HRM-009 Stage A: certificate request and issued metadata only.
-- Document content, download, revoke execution, verification transport, and
-- access/download audit are deliberately outside this migration.

CREATE TABLE ppl_certificate_requests (
    certificate_request_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    requester_person_id BIGINT NOT NULL,
    subject_person_id BIGINT NOT NULL,
    certificate_type VARCHAR(80) NOT NULL,
    locale VARCHAR(35) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    template_public_id UUID NOT NULL,
    template_version INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_certificate_requests_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_certificate_requests_id
        UNIQUE (tenant_id, certificate_request_id),
    CONSTRAINT uk_ppl_certificate_requests_correlation
        UNIQUE (tenant_id, correlation_id),
    CONSTRAINT fk_ppl_certificate_requests_requester
        FOREIGN KEY (tenant_id, requester_person_id)
        REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT fk_ppl_certificate_requests_subject
        FOREIGN KEY (tenant_id, subject_person_id)
        REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT ck_ppl_certificate_requests_state CHECK (
        lifecycle_state IN ('REQUESTED','VALIDATING','ISSUED','FAILED','CANCELLED'))
);

CREATE TABLE ppl_issued_certificates (
    issued_certificate_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    certificate_request_id BIGINT NOT NULL,
    object_public_id UUID NOT NULL,
    object_version INTEGER NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    verification_token_hash CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoke_reason_code VARCHAR(80),
    retention_until DATE,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_issued_certificates_public UNIQUE (public_id),
    CONSTRAINT uk_ppl_issued_certificates_request
        UNIQUE (tenant_id, certificate_request_id),
    CONSTRAINT uk_ppl_issued_certificates_verify
        UNIQUE (tenant_id, verification_token_hash),
    CONSTRAINT fk_ppl_issued_certificates_request
        FOREIGN KEY (tenant_id, certificate_request_id)
        REFERENCES ppl_certificate_requests(tenant_id, certificate_request_id),
    CONSTRAINT ck_ppl_issued_certificates_state CHECK (
        lifecycle_state IN ('ISSUED','REVOKED','EXPIRED')),
    CONSTRAINT ck_ppl_issued_certificates_expiry CHECK (
        expires_at IS NULL OR expires_at > issued_at),
    CONSTRAINT ck_ppl_issued_certificates_revoke CHECK (
        lifecycle_state <> 'REVOKED'
        OR (revoked_at IS NOT NULL AND revoke_reason_code IS NOT NULL))
);

CREATE INDEX idx_ppl_certificate_requests_work_queue
    ON ppl_certificate_requests(tenant_id, lifecycle_state, requested_at)
    WHERE lifecycle_state IN ('REQUESTED','VALIDATING');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ppl_command_receipts
         GROUP BY tenant_id, command_type, idempotency_key
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'duplicate tenant/command/idempotency receipt prevents HRM-009 V58'
            USING ERRCODE = '23505';
    END IF;
END;
$$;

CREATE UNIQUE INDEX uidx_ppl_command_receipts_tenant_command_idempotency
    ON ppl_command_receipts(tenant_id, command_type, idempotency_key);

ALTER TABLE ppl_certificate_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_certificate_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_certificate_requests_tenant_policy
    ON ppl_certificate_requests
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT
    );

ALTER TABLE ppl_issued_certificates ENABLE ROW LEVEL SECURITY;
ALTER TABLE ppl_issued_certificates FORCE ROW LEVEL SECURITY;
CREATE POLICY ppl_issued_certificates_tenant_policy
    ON ppl_issued_certificates
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT
    );

CREATE FUNCTION public.ppl_guard_certificate_request_state()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'REQUESTED' OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'certificate requests must begin REQUESTED at version zero'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'certificate request history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.certificate_request_id, NEW.public_id, NEW.tenant_id,
           NEW.requester_person_id, NEW.subject_person_id,
           NEW.certificate_type, NEW.locale, NEW.purpose_code,
           NEW.template_public_id, NEW.template_version,
           NEW.correlation_id, NEW.requested_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.certificate_request_id, OLD.public_id, OLD.tenant_id,
           OLD.requester_person_id, OLD.subject_person_id,
           OLD.certificate_type, OLD.locale, OLD.purpose_code,
           OLD.template_public_id, OLD.template_version,
           OLD.correlation_id, OLD.requested_at, OLD.created_by) THEN
        RAISE EXCEPTION 'certificate request originating metadata is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF NOT (
        (OLD.lifecycle_state = 'REQUESTED'
            AND NEW.lifecycle_state IN ('VALIDATING','CANCELLED'))
        OR (OLD.lifecycle_state = 'VALIDATING'
            AND NEW.lifecycle_state IN ('ISSUED','FAILED'))
    ) THEN
        RAISE EXCEPTION 'illegal certificate request state transition'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'certificate request version must advance exactly once'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.lifecycle_state IN ('ISSUED','FAILED','CANCELLED')
       AND NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'terminal certificate request requires completed_at'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_certificate_request_state() FROM PUBLIC;

CREATE TRIGGER trg_ppl_certificate_request_state
BEFORE INSERT OR UPDATE OR DELETE ON ppl_certificate_requests
FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_certificate_request_state();

CREATE FUNCTION public.ppl_guard_issued_certificate_state()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'ISSUED' OR NOT EXISTS (
            SELECT 1
              FROM public.ppl_certificate_requests request
             WHERE request.tenant_id = NEW.tenant_id
               AND request.certificate_request_id = NEW.certificate_request_id
               AND request.lifecycle_state = 'VALIDATING'
        ) THEN
            RAISE EXCEPTION
                'issued certificate requires same-tenant VALIDATING request'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'issued certificate history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF ROW(NEW.issued_certificate_id, NEW.public_id, NEW.tenant_id,
           NEW.certificate_request_id, NEW.object_public_id,
           NEW.object_version, NEW.content_sha256,
           NEW.verification_token_hash, NEW.issued_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.issued_certificate_id, OLD.public_id, OLD.tenant_id,
           OLD.certificate_request_id, OLD.object_public_id,
           OLD.object_version, OLD.content_sha256,
           OLD.verification_token_hash, OLD.issued_at, OLD.created_by) THEN
        RAISE EXCEPTION 'issued certificate immutable metadata changed'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.lifecycle_state <> 'ISSUED'
       OR NEW.lifecycle_state NOT IN ('REVOKED','EXPIRED') THEN
        RAISE EXCEPTION 'illegal issued certificate state transition'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.lifecycle_state = 'REVOKED'
       AND (NEW.revoked_at IS NULL OR NEW.revoke_reason_code IS NULL
            OR (NEW.expires_at IS NOT NULL AND NEW.revoked_at >= NEW.expires_at)) THEN
        RAISE EXCEPTION 'revocation metadata or timing is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.ppl_guard_issued_certificate_state() FROM PUBLIC;

CREATE TRIGGER trg_ppl_issued_certificate_state
BEFORE INSERT OR UPDATE OR DELETE ON ppl_issued_certificates
FOR EACH ROW EXECUTE FUNCTION public.ppl_guard_issued_certificate_state();
