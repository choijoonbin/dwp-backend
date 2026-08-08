ALTER TABLE com_users
    ADD COLUMN access_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE sys_identity_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    correlation_id VARCHAR(128),
    before_snapshot TEXT,
    after_snapshot TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sys_identity_audit_tenant_time
    ON sys_identity_audit_events (tenant_id, occurred_at DESC);

