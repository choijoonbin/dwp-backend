CREATE TABLE ntf_delivery_suppressions (
    suppression_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL
        CHECK (scope_type IN ('TENANT', 'APP', 'TYPE')),
    scope_key VARCHAR(200) NOT NULL,
    channel VARCHAR(30) NOT NULL
        CHECK (channel IN ('ALL', 'IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')),
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    critical_bypass BOOLEAN NOT NULL DEFAULT TRUE,
    reason VARCHAR(500) NOT NULL,
    created_by BIGINT NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT,
    revoke_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ntf_suppression_scope_key CHECK (
        (scope_type = 'TENANT' AND scope_key = '*')
        OR (scope_type IN ('APP', 'TYPE') AND length(trim(scope_key)) > 0)
    ),
    CONSTRAINT ck_ntf_suppression_ttl CHECK (
        expires_at > starts_at
        AND expires_at <= starts_at + INTERVAL '31 days'
    ),
    CONSTRAINT ck_ntf_suppression_revocation CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND length(trim(revoke_reason)) > 0)
    )
);

CREATE INDEX ix_ntf_suppression_effective
    ON ntf_delivery_suppressions
        (tenant_id, channel, starts_at, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_ntf_suppression_scope
    ON ntf_delivery_suppressions
        (tenant_id, scope_type, scope_key, channel, starts_at DESC);

CREATE TABLE ntf_delivery_admission_receipts (
    receipt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_event_id UUID NOT NULL,
    type_version_id UUID NOT NULL
        REFERENCES ntf_notification_type_versions(type_version_id),
    user_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL
        CHECK (channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')),
    decision VARCHAR(20) NOT NULL
        CHECK (decision IN ('PENDING', 'ADMITTED', 'SUPPRESSED', 'RATE_LIMITED')),
    reason_code VARCHAR(200),
    suppression_id UUID REFERENCES ntf_delivery_suppressions(suppression_id),
    window_started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    CONSTRAINT uq_ntf_admission_event_recipient UNIQUE
        (tenant_id, source_event_id, type_version_id, user_id, channel),
    CONSTRAINT ck_ntf_admission_decided CHECK (
        (decision = 'PENDING' AND decided_at IS NULL)
        OR (decision <> 'PENDING' AND decided_at IS NOT NULL)
    )
);

CREATE INDEX ix_ntf_admission_receipt_cleanup
    ON ntf_delivery_admission_receipts (tenant_id, created_at);
CREATE INDEX ix_ntf_admission_receipt_decision
    ON ntf_delivery_admission_receipts (tenant_id, decision, created_at DESC);

CREATE TABLE ntf_delivery_rate_windows (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    type_version_id UUID NOT NULL
        REFERENCES ntf_notification_type_versions(type_version_id),
    channel VARCHAR(30) NOT NULL
        CHECK (channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')),
    window_started_at TIMESTAMPTZ NOT NULL,
    window_seconds INTEGER NOT NULL CHECK (window_seconds BETWEEN 60 AND 86400),
    delivery_count INTEGER NOT NULL CHECK (delivery_count > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        tenant_id, user_id, type_version_id, channel,
        window_started_at, window_seconds)
);

CREATE INDEX ix_ntf_rate_window_cleanup
    ON ntf_delivery_rate_windows (tenant_id, window_started_at);

ALTER TABLE ntf_delivery_suppressions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_suppressions FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_suppression_worker_scope ON ntf_delivery_suppressions
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_delivery_admission_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_admission_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_admission_receipt_worker_scope ON ntf_delivery_admission_receipts
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_delivery_rate_windows ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_rate_windows FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_rate_window_worker_scope ON ntf_delivery_rate_windows
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON
    ntf_delivery_suppressions,
    ntf_delivery_admission_receipts,
    ntf_delivery_rate_windows
    TO dwp_notification_worker;

COMMENT ON TABLE ntf_delivery_suppressions IS
    'Time-bounded tenant operational suppression. It never enables a delivery channel.';
COMMENT ON COLUMN ntf_delivery_suppressions.critical_bypass IS
    'When true, URGENT or CRITICAL contracts bypass this suppression.';
COMMENT ON TABLE ntf_delivery_admission_receipts IS
    'Idempotent per-recipient admission decisions for suppression and rate enforcement.';
COMMENT ON TABLE ntf_delivery_rate_windows IS
    'Atomic fixed-window counters used to enforce policy max_per_window.';
