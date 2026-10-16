-- BASE-TFR-SYS-009 / SYS-API-033 / SYS-API-034 / SYS-EVT-008.
-- Stores only version-pinned metadata and immutable evidence. Object bytes,
-- credentials, signed URLs and arbitrary filesystem paths are prohibited.

CREATE TABLE sys_controlled_file_transfers (
    controlled_transfer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    transfer_kind VARCHAR(16) NOT NULL,
    owner_module VARCHAR(8) NOT NULL,
    purpose_code VARCHAR(64) NOT NULL,
    recipient_policy_ref UUID,
    population_kind VARCHAR(24) NOT NULL,
    population_scope_digest CHAR(64) NOT NULL,
    authority_population_scope_digest CHAR(64) NOT NULL,
    access_authority_digest CHAR(64) NOT NULL,
    access_authority_fresh_until TIMESTAMPTZ NOT NULL,
    egress_policy_revision BIGINT,
    classification VARCHAR(24) NOT NULL,
    object_ref VARCHAR(132) NOT NULL,
    object_version VARCHAR(128) NOT NULL,
    storage_etag VARCHAR(256) NOT NULL,
    upload_session_id UUID NOT NULL,
    object_digest CHAR(64) NOT NULL,
    media_type VARCHAR(96) NOT NULL,
    byte_size BIGINT NOT NULL,
    metadata_authority VARCHAR(120) NOT NULL,
    metadata_snapshot_digest CHAR(64) NOT NULL,
    metadata_authority_digest CHAR(64) NOT NULL,
    metadata_authority_fresh_until TIMESTAMPTZ NOT NULL,
    scanner_key VARCHAR(64) NOT NULL,
    scanner_version VARCHAR(64) NOT NULL,
    scanned_at TIMESTAMPTZ NOT NULL,
    scan_object_digest CHAR(64) NOT NULL,
    scan_byte_size BIGINT NOT NULL,
    scan_authority_digest CHAR(64) NOT NULL,
    scan_authority_fresh_until TIMESTAMPTZ NOT NULL,
    egress_authority_digest CHAR(64),
    egress_authority_fresh_until TIMESTAMPTZ,
    malware_state VARCHAR(16) NOT NULL,
    state VARCHAR(24) NOT NULL,
    lease_owner VARCHAR(120),
    lease_token_digest CHAR(64),
    lease_expires_at TIMESTAMPTZ,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    receipt_public_id UUID NOT NULL,
    receipt_digest CHAR(64) NOT NULL,
    failure_code VARCHAR(80),
    failure_safe_detail VARCHAR(500),
    requested_by UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_sys_controlled_file_transfer_public
        UNIQUE (tenant_id, public_id),
    CONSTRAINT uq_sys_controlled_file_transfer_idempotency
        UNIQUE (tenant_id, owner_module, idempotency_key),
    CONSTRAINT ck_sys_controlled_file_transfer_tenant
        CHECK (tenant_id > 0),
    CONSTRAINT ck_sys_controlled_file_transfer_kind
        CHECK (transfer_kind IN ('INGEST', 'EXPORT', 'DELIVERY', 'EXCHANGE')),
    CONSTRAINT ck_sys_controlled_file_transfer_owner
        CHECK (owner_module IN ('HRM', 'PER', 'TIM', 'PAY', 'SYS')),
    CONSTRAINT ck_sys_controlled_file_transfer_purpose
        CHECK (purpose_code = 'HRIS_INTEGRATION_ADMIN'),
    CONSTRAINT ck_sys_controlled_file_transfer_population_kind
        CHECK (population_kind IN ('CONNECTOR_SET', 'LEGAL_ENTITY')),
    CONSTRAINT ck_sys_controlled_file_transfer_direction_policy
        CHECK ((transfer_kind = 'INGEST'
                AND recipient_policy_ref IS NULL
                AND egress_policy_revision IS NULL
                AND egress_authority_digest IS NULL
                AND egress_authority_fresh_until IS NULL)
            OR (transfer_kind IN ('EXPORT', 'DELIVERY', 'EXCHANGE')
                AND recipient_policy_ref IS NOT NULL
                AND egress_policy_revision > 0
                AND egress_authority_digest IS NOT NULL
                AND egress_authority_fresh_until IS NOT NULL)),
    CONSTRAINT ck_sys_controlled_file_transfer_classification
        CHECK (classification IN ('CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_sys_controlled_file_transfer_object_ref
        CHECK (object_ref ~ '^obj_[A-Za-z0-9_-]{16,128}$'
            AND position('/' IN object_ref) = 0
            AND position('\\' IN object_ref) = 0),
    CONSTRAINT ck_sys_controlled_file_transfer_media
        CHECK (media_type IN ('text/csv', 'application/json', 'application/pdf',
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')),
    CONSTRAINT ck_sys_controlled_file_transfer_digests
        CHECK (population_scope_digest ~ '^[a-f0-9]{64}$'
            AND authority_population_scope_digest ~ '^[a-f0-9]{64}$'
            AND access_authority_digest ~ '^[a-f0-9]{64}$'
            AND object_digest ~ '^[a-f0-9]{64}$'
            AND metadata_snapshot_digest ~ '^[a-f0-9]{64}$'
            AND metadata_authority_digest ~ '^[a-f0-9]{64}$'
            AND scan_object_digest ~ '^[a-f0-9]{64}$'
            AND scan_authority_digest ~ '^[a-f0-9]{64}$'
            AND (egress_authority_digest IS NULL
                OR egress_authority_digest ~ '^[a-f0-9]{64}$')
            AND request_digest ~ '^[a-f0-9]{64}$'
            AND receipt_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_sys_controlled_file_transfer_snapshot_binding
        CHECK (object_digest = scan_object_digest
            AND byte_size = scan_byte_size
            AND byte_size BETWEEN 1 AND 5368709120),
    CONSTRAINT ck_sys_controlled_file_transfer_malware
        CHECK (malware_state = 'CLEAN'),
    CONSTRAINT ck_sys_controlled_file_transfer_state
        CHECK (state IN ('REQUESTED', 'SCANNING', 'READY', 'IN_PROGRESS',
            'COMPLETED', 'QUARANTINED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_sys_controlled_file_transfer_lease
        CHECK ((state IN ('SCANNING', 'IN_PROGRESS')
                AND lease_owner IS NOT NULL
                AND length(lease_owner) BETWEEN 1 AND 120
                AND lease_token_digest ~ '^[a-f0-9]{64}$'
                AND lease_expires_at > requested_at)
            OR (state NOT IN ('SCANNING', 'IN_PROGRESS')
                AND lease_owner IS NULL
                AND lease_token_digest IS NULL
                AND lease_expires_at IS NULL)),
    CONSTRAINT ck_sys_controlled_file_transfer_lengths
        CHECK (length(purpose_code) BETWEEN 3 AND 64
            AND length(object_version) BETWEEN 1 AND 128
            AND length(storage_etag) BETWEEN 1 AND 256
            AND length(metadata_authority) BETWEEN 1 AND 120
            AND length(scanner_key) BETWEEN 1 AND 64
            AND length(scanner_version) BETWEEN 1 AND 64
            AND length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_sys_controlled_file_transfer_times
        CHECK (updated_at >= requested_at
            AND expires_at > requested_at
            AND access_authority_fresh_until > requested_at
            AND metadata_authority_fresh_until > requested_at
            AND scan_authority_fresh_until > requested_at
            AND scanned_at <= requested_at
            AND (egress_authority_fresh_until IS NULL
                OR egress_authority_fresh_until > requested_at)
            AND ((state = 'COMPLETED' AND completed_at IS NOT NULL
                    AND completed_at >= requested_at)
                OR (state <> 'COMPLETED' AND completed_at IS NULL))),
    CONSTRAINT ck_sys_controlled_file_transfer_version
        CHECK (version > 0),
    CONSTRAINT ck_sys_controlled_file_transfer_failure
        CHECK ((state IN ('FAILED', 'QUARANTINED')
                AND failure_code IS NOT NULL)
            OR (state NOT IN ('FAILED', 'QUARANTINED')
                AND failure_code IS NULL AND failure_safe_detail IS NULL))
);

CREATE INDEX ix_sys_controlled_file_transfer_catalog
    ON sys_controlled_file_transfers
        (tenant_id, population_kind, population_scope_digest,
         updated_at DESC, public_id DESC);
CREATE INDEX ix_sys_controlled_file_transfer_state
    ON sys_controlled_file_transfers
        (tenant_id, state, updated_at DESC);
CREATE INDEX ix_sys_controlled_file_transfer_expiry
    ON sys_controlled_file_transfers (expires_at)
    WHERE state NOT IN (
        'COMPLETED', 'QUARANTINED', 'FAILED', 'EXPIRED', 'CANCELLED');
CREATE INDEX ix_sys_controlled_file_transfer_lease_expiry
    ON sys_controlled_file_transfers (lease_expires_at)
    WHERE state IN ('SCANNING', 'IN_PROGRESS');

CREATE OR REPLACE FUNCTION sys_guard_controlled_file_transfer_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'REQUESTED' OR NEW.version <> 1
           OR NEW.updated_at IS DISTINCT FROM NEW.requested_at
           OR NEW.completed_at IS NOT NULL OR NEW.failure_code IS NOT NULL
           OR NEW.failure_safe_detail IS NOT NULL OR NEW.lease_owner IS NOT NULL
           OR NEW.lease_token_digest IS NOT NULL OR NEW.lease_expires_at IS NOT NULL THEN
            RAISE EXCEPTION 'Controlled-transfer must start as REQUESTED version 1'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Controlled-transfer evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := GREATEST(
        statement_timestamp(), OLD.updated_at + INTERVAL '1 microsecond');
    IF ROW(NEW.controlled_transfer_id, NEW.public_id, NEW.tenant_id,
           NEW.transfer_kind, NEW.owner_module, NEW.purpose_code,
           NEW.recipient_policy_ref, NEW.population_kind,
           NEW.population_scope_digest, NEW.authority_population_scope_digest,
           NEW.access_authority_digest,
           NEW.access_authority_fresh_until, NEW.egress_policy_revision, NEW.classification,
           NEW.object_ref, NEW.object_version, NEW.storage_etag,
           NEW.upload_session_id, NEW.object_digest, NEW.media_type, NEW.byte_size,
           NEW.metadata_authority, NEW.metadata_snapshot_digest,
           NEW.metadata_authority_digest, NEW.metadata_authority_fresh_until,
           NEW.scanner_key, NEW.scanner_version, NEW.scanned_at,
           NEW.scan_object_digest, NEW.scan_byte_size, NEW.scan_authority_digest,
           NEW.scan_authority_fresh_until, NEW.egress_authority_digest,
           NEW.egress_authority_fresh_until,
           NEW.idempotency_key, NEW.request_digest, NEW.receipt_public_id,
           NEW.receipt_digest, NEW.requested_by, NEW.requested_at, NEW.expires_at)
       IS DISTINCT FROM
       ROW(OLD.controlled_transfer_id, OLD.public_id, OLD.tenant_id,
           OLD.transfer_kind, OLD.owner_module, OLD.purpose_code,
           OLD.recipient_policy_ref, OLD.population_kind,
           OLD.population_scope_digest, OLD.authority_population_scope_digest,
           OLD.access_authority_digest,
           OLD.access_authority_fresh_until, OLD.egress_policy_revision, OLD.classification,
           OLD.object_ref, OLD.object_version, OLD.storage_etag,
           OLD.upload_session_id, OLD.object_digest, OLD.media_type, OLD.byte_size,
           OLD.metadata_authority, OLD.metadata_snapshot_digest,
           OLD.metadata_authority_digest, OLD.metadata_authority_fresh_until,
           OLD.scanner_key, OLD.scanner_version, OLD.scanned_at,
           OLD.scan_object_digest, OLD.scan_byte_size, OLD.scan_authority_digest,
           OLD.scan_authority_fresh_until, OLD.egress_authority_digest,
           OLD.egress_authority_fresh_until,
           OLD.idempotency_key, OLD.request_digest, OLD.receipt_public_id,
           OLD.receipt_digest, OLD.requested_by, OLD.requested_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'Controlled-transfer object and authority evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('COMPLETED', 'QUARANTINED', 'FAILED', 'EXPIRED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Controlled-transfer terminal evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Controlled-transfer transition requires exact CAS version increment'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'EXPIRED' AND CURRENT_TIMESTAMP < OLD.expires_at THEN
        RAISE EXCEPTION 'Controlled-transfer cannot expire before expires_at'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state IN ('SCANNING', 'IN_PROGRESS')
       AND (NEW.lease_expires_at IS NULL
            OR NEW.lease_expires_at <= CURRENT_TIMESTAMP) THEN
        RAISE EXCEPTION 'Controlled-transfer active state requires a fresh lease'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
           (OLD.state = 'REQUESTED' AND NEW.state IN ('SCANNING', 'EXPIRED', 'CANCELLED'))
        OR (OLD.state = 'SCANNING' AND NEW.state IN
            ('READY', 'QUARANTINED', 'FAILED', 'EXPIRED', 'CANCELLED'))
        OR (OLD.state = 'READY' AND NEW.state IN
            ('IN_PROGRESS', 'EXPIRED', 'CANCELLED'))
        OR (OLD.state = 'IN_PROGRESS' AND NEW.state IN
            ('COMPLETED', 'QUARANTINED', 'FAILED', 'EXPIRED', 'CANCELLED'))
        OR (OLD.state = NEW.state
            AND OLD.state IN ('SCANNING', 'IN_PROGRESS')
            AND OLD.lease_expires_at <= CURRENT_TIMESTAMP
            AND ROW(NEW.lease_owner, NEW.lease_token_digest, NEW.lease_expires_at)
                IS DISTINCT FROM
                ROW(OLD.lease_owner, OLD.lease_token_digest, OLD.lease_expires_at))
    ) THEN
        RAISE EXCEPTION 'Illegal controlled-transfer state transition'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_controlled_file_transfer_evidence
BEFORE INSERT OR UPDATE OR DELETE ON sys_controlled_file_transfers
FOR EACH ROW EXECUTE FUNCTION sys_guard_controlled_file_transfer_evidence();

ALTER TABLE sys_controlled_file_transfers ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_controlled_file_transfers FORCE ROW LEVEL SECURITY;

CREATE POLICY sys_controlled_file_transfers_tenant_policy
    ON sys_controlled_file_transfers
    USING (tenant_id = NULLIF(
        current_setting('dwp.tenant_id', TRUE), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(
        current_setting('dwp.tenant_id', TRUE), '')::BIGINT);
