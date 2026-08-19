CREATE TABLE sys_tenant_media_cleanup_outbox (
    cleanup_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    storage_key VARCHAR(1000) NOT NULL,
    cleanup_reason VARCHAR(80) NOT NULL,
    cleanup_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(200),
    lease_expires_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_tenant_media_cleanup_key CHECK (
        length(trim(storage_key)) BETWEEN 3 AND 1000),
    CONSTRAINT ck_sys_tenant_media_cleanup_reason CHECK (
        cleanup_reason ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_sys_tenant_media_cleanup_status CHECK (
        cleanup_status IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'COMPLETED', 'DEAD')),
    CONSTRAINT ck_sys_tenant_media_cleanup_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_sys_tenant_media_cleanup_lease CHECK (
        (cleanup_status = 'LEASED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (cleanup_status <> 'LEASED' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_sys_tenant_media_cleanup_completion CHECK (
        (cleanup_status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (cleanup_status <> 'COMPLETED' AND completed_at IS NULL))
);

CREATE UNIQUE INDEX uk_sys_tenant_media_cleanup_active
    ON sys_tenant_media_cleanup_outbox (tenant_id, storage_key)
    WHERE cleanup_status IN ('PENDING', 'LEASED', 'RETRY_WAIT');

CREATE INDEX idx_sys_tenant_media_cleanup_delivery
    ON sys_tenant_media_cleanup_outbox (cleanup_status, next_attempt_at, created_at)
    WHERE cleanup_status IN ('PENDING', 'RETRY_WAIT');

COMMENT ON TABLE sys_tenant_media_cleanup_outbox IS
    'Transactional cleanup queue for tenant-scoped media. External storage failures never roll back committed domain changes.';
COMMENT ON COLUMN sys_tenant_media_cleanup_outbox.cleanup_status IS
    'PENDING and RETRY_WAIT are claimable; DEAD requires an explicit operator decision.';
