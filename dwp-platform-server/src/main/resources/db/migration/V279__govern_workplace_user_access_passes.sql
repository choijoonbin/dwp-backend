CREATE TABLE wp_navigation_access_passes (
    pass_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    owner_user_id BIGINT NOT NULL,
    source_booking_id UUID,
    site_id UUID NOT NULL,
    floor_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    destination_poi_id UUID NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    credential_sha256 CHAR(64) NOT NULL,
    credential_last_four CHAR(4) NOT NULL,
    pairing_code_hash CHAR(64),
    pairing_code_salt CHAR(32),
    pairing_attempt_count INTEGER NOT NULL DEFAULT 0,
    pairing_locked_at TIMESTAMPTZ,
    pairing_consumed_at TIMESTAMPTZ,
    pairing_device_id UUID,
    nfc_enabled BOOLEAN NOT NULL,
    qr_enabled BOOLEAN NOT NULL,
    nfc_provider_code VARCHAR(80),
    nfc_provider_configuration_version BIGINT,
    nfc_provider_evidence_reference VARCHAR(320),
    qr_provider_code VARCHAR(80),
    qr_provider_configuration_version BIGINT,
    qr_provider_evidence_reference VARCHAR(320),
    version BIGINT NOT NULL DEFAULT 1,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, pass_id),
    CONSTRAINT ck_wp_navigation_access_pass_state CHECK
        (lifecycle_state IN ('ACTIVE','REVOKED','EXPIRED')),
    CONSTRAINT ck_wp_navigation_access_pass_hashes CHECK
        (credential_sha256 ~ '^[0-9a-f]{64}$'
         AND (pairing_code_hash IS NULL OR pairing_code_hash ~ '^[0-9a-f]{64}$')
         AND (pairing_code_salt IS NULL OR pairing_code_salt ~ '^[0-9a-f]{32}$')),
    CONSTRAINT ck_wp_navigation_access_pass_pairing CHECK (
        (qr_enabled AND pairing_code_hash IS NOT NULL AND pairing_code_salt IS NOT NULL
         AND pairing_attempt_count BETWEEN 0 AND 4
         AND pairing_locked_at IS NULL AND pairing_consumed_at IS NULL
         AND pairing_device_id IS NULL)
        OR
        (NOT qr_enabled AND pairing_code_hash IS NULL AND pairing_code_salt IS NULL AND (
            (pairing_attempt_count=0 AND pairing_locked_at IS NULL
             AND pairing_consumed_at IS NULL AND pairing_device_id IS NULL)
            OR
            (pairing_attempt_count BETWEEN 0 AND 4 AND pairing_locked_at IS NULL
             AND pairing_consumed_at IS NOT NULL AND pairing_device_id IS NOT NULL)
            OR
            (pairing_attempt_count=5 AND pairing_locked_at IS NOT NULL
             AND pairing_consumed_at IS NULL AND pairing_device_id IS NULL)
        ))
    ),
    CONSTRAINT ck_wp_navigation_access_pass_window CHECK
        (expires_at > issued_at AND expires_at <= issued_at + INTERVAL '20 minutes'),
    CONSTRAINT ck_wp_navigation_access_pass_revocation CHECK
        ((lifecycle_state='REVOKED' AND revoked_at IS NOT NULL)
         OR (lifecycle_state<>'REVOKED' AND revoked_at IS NULL)),
    CONSTRAINT ck_wp_navigation_access_pass_nfc_provider CHECK
        ((nfc_enabled AND nfc_provider_code IS NOT NULL
          AND nfc_provider_configuration_version IS NOT NULL
          AND nfc_provider_evidence_reference IS NOT NULL)
         OR (NOT nfc_enabled AND nfc_provider_code IS NULL
          AND nfc_provider_configuration_version IS NULL
          AND nfc_provider_evidence_reference IS NULL)),
    CONSTRAINT ck_wp_navigation_access_pass_qr_provider CHECK
        ((qr_enabled AND qr_provider_code IS NOT NULL
          AND qr_provider_configuration_version IS NOT NULL
          AND qr_provider_evidence_reference IS NOT NULL)
         OR (NOT qr_enabled AND qr_provider_code IS NULL
          AND qr_provider_configuration_version IS NULL
          AND qr_provider_evidence_reference IS NULL)),
    CONSTRAINT fk_wp_navigation_access_pass_booking
        FOREIGN KEY (source_booking_id)
        REFERENCES wp_bookings(booking_id),
    CONSTRAINT fk_wp_navigation_access_pass_pairing_device
        FOREIGN KEY (tenant_id, pairing_device_id)
        REFERENCES wp_navigation_devices(tenant_id, device_id)
);

CREATE UNIQUE INDEX uk_wp_navigation_active_user_access_pass
    ON wp_navigation_access_passes(tenant_id, owner_user_id, resource_id)
    WHERE lifecycle_state='ACTIVE';

CREATE INDEX idx_wp_navigation_access_pass_owner
    ON wp_navigation_access_passes(tenant_id, owner_user_id, updated_at DESC);

CREATE TABLE wp_navigation_access_pass_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    pass_id UUID,
    site_id UUID NOT NULL,
    floor_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    destination_poi_id UUID NOT NULL,
    source_booking_id UUID,
    expected_pass_version BIGINT NOT NULL,
    nfc_enabled BOOLEAN NOT NULL,
    qr_enabled BOOLEAN NOT NULL,
    nfc_provider_code VARCHAR(80),
    nfc_provider_configuration_version BIGINT,
    nfc_provider_evidence_reference VARCHAR(320),
    qr_provider_code VARCHAR(80),
    qr_provider_configuration_version BIGINT,
    qr_provider_evidence_reference VARCHAR(320),
    eligible BOOLEAN NOT NULL,
    impact JSONB NOT NULL,
    limitations JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, preview_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_wp_navigation_access_pass_preview_command CHECK
        (command_type IN ('ISSUE','ROTATE','REVOKE')),
    CONSTRAINT ck_wp_navigation_access_pass_preview_hash CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_navigation_access_pass_preview_json CHECK
        (jsonb_typeof(impact)='array' AND jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_navigation_access_pass_preview_version CHECK
        (expected_pass_version >= 0),
    CONSTRAINT ck_wp_navigation_access_pass_preview_nfc_provider CHECK
        ((nfc_enabled AND nfc_provider_code IS NOT NULL
          AND nfc_provider_configuration_version IS NOT NULL
          AND nfc_provider_evidence_reference IS NOT NULL)
         OR (NOT nfc_enabled AND nfc_provider_code IS NULL
          AND nfc_provider_configuration_version IS NULL
          AND nfc_provider_evidence_reference IS NULL)),
    CONSTRAINT ck_wp_navigation_access_pass_preview_qr_provider CHECK
        ((qr_enabled AND qr_provider_code IS NOT NULL
          AND qr_provider_configuration_version IS NOT NULL
          AND qr_provider_evidence_reference IS NOT NULL)
         OR (NOT qr_enabled AND qr_provider_code IS NULL
          AND qr_provider_configuration_version IS NULL
          AND qr_provider_evidence_reference IS NULL)),
    CONSTRAINT fk_wp_navigation_access_pass_preview_booking
        FOREIGN KEY (source_booking_id)
        REFERENCES wp_bookings(booking_id)
);

CREATE TABLE wp_navigation_access_pass_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    pass_id UUID NOT NULL,
    preview_id UUID NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result_code VARCHAR(120),
    correlation_id VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 1,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, pass_id)
        REFERENCES wp_navigation_access_passes(tenant_id, pass_id),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_navigation_access_pass_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_navigation_access_pass_command_type CHECK
        (command_type IN ('ISSUE','ROTATE','REVOKE')),
    CONSTRAINT ck_wp_navigation_access_pass_command_state CHECK
        (command_state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_navigation_access_pass_command_hash CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_wp_navigation_access_pass_commands_owner
    ON wp_navigation_access_pass_commands(tenant_id, actor_user_id, updated_at DESC);

CREATE TABLE wp_navigation_access_pass_pairing_receipts (
    pairing_receipt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    pass_id UUID NOT NULL,
    device_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    correlation_id VARCHAR(160),
    request_fingerprint CHAR(64) NOT NULL,
    site_id UUID NOT NULL,
    floor_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    pass_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    paired_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, device_id, idempotency_key),
    UNIQUE (tenant_id, pass_id),
    CONSTRAINT fk_wp_navigation_access_pass_pairing_receipt_pass
        FOREIGN KEY (tenant_id, pass_id)
        REFERENCES wp_navigation_access_passes(tenant_id, pass_id),
    CONSTRAINT fk_wp_navigation_access_pass_pairing_receipt_device
        FOREIGN KEY (tenant_id, device_id)
        REFERENCES wp_navigation_devices(tenant_id, device_id),
    CONSTRAINT ck_wp_navigation_access_pass_pairing_receipt_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_navigation_access_pass_pairing_receipt_correlation CHECK
        (correlation_id IS NULL OR correlation_id ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_navigation_access_pass_pairing_receipt_hash CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_navigation_access_pass_pairing_receipt_version CHECK
        (pass_version >= 1 AND expires_at > paired_at)
);

CREATE FUNCTION wp_assert_navigation_access_pass_booking_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_booking_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
          FROM wp_bookings booking
         WHERE booking.booking_id=NEW.source_booking_id
           AND booking.tenant_id=NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'Access-pass booking must belong to the same tenant'
            USING ERRCODE='23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_navigation_access_pass_booking_tenant
BEFORE INSERT OR UPDATE OF tenant_id, source_booking_id
ON wp_navigation_access_passes
FOR EACH ROW EXECUTE FUNCTION wp_assert_navigation_access_pass_booking_tenant();

CREATE TRIGGER trg_wp_navigation_access_pass_preview_booking_tenant
BEFORE INSERT OR UPDATE OF tenant_id, source_booking_id
ON wp_navigation_access_pass_previews
FOR EACH ROW EXECUTE FUNCTION wp_assert_navigation_access_pass_booking_tenant();

COMMENT ON COLUMN wp_navigation_access_passes.credential_sha256 IS
    'One-way fingerprint only. The raw one-time credential is returned once and is never persisted.';
COMMENT ON COLUMN wp_navigation_access_passes.pairing_code_hash IS
    'PBKDF2-SHA256 verification value with a per-pass random salt. The high-entropy raw code is returned once and never persisted.';
COMMENT ON COLUMN wp_navigation_access_passes.pairing_code_salt IS
    'Per-pass random salt for the one-way kiosk pairing-code verification value; not credential material.';
COMMENT ON TABLE wp_navigation_access_pass_commands IS
    'Durable receipts only. Command replay and status GET never disclose one-time credential material.';
