CREATE TABLE ntf_user_attention_rules (
    rule_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    scope_kind VARCHAR(30) NOT NULL CHECK (
        scope_kind IN ('APP_TYPE', 'ACTOR', 'THREAD', 'RESOURCE', 'TOPIC_TOKEN')
    ),
    scope_key VARCHAR(300) NOT NULL CHECK (
        length(scope_key) BETWEEN 1 AND 300 AND scope_key = trim(scope_key)
    ),
    scope_key_hash CHAR(64) NOT NULL CHECK (
        scope_key_hash ~ '^[a-f0-9]{64}$'
    ),
    display_label VARCHAR(160),
    effect VARCHAR(20) NOT NULL CHECK (
        effect IN ('FOLLOW', 'PRIORITIZE', 'MUTE')
    ),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    starts_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    source VARCHAR(30) NOT NULL DEFAULT 'USER' CHECK (
        source IN ('USER', 'TENANT_POLICY', 'SYSTEM_DEFAULT')
    ),
    managed BOOLEAN NOT NULL DEFAULT FALSE,
    exception_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_attention_rule_owner_id
        UNIQUE (tenant_id, user_id, rule_id),
    CONSTRAINT uq_ntf_attention_rule_owner_scope
        UNIQUE (tenant_id, user_id, scope_kind, scope_key_hash),
    CONSTRAINT ck_ntf_attention_rule_window CHECK (
        expires_at IS NULL OR starts_at IS NULL OR expires_at > starts_at
    ),
    CONSTRAINT ck_ntf_attention_rule_display_label CHECK (
        display_label IS NULL OR (
            length(display_label) BETWEEN 1 AND 160
            AND display_label = trim(display_label)
        )
    ),
    CONSTRAINT ck_ntf_attention_rule_management CHECK (
        source <> 'USER' OR (NOT managed AND NOT exception_allowed)
    )
);

CREATE TABLE ntf_user_attention_rule_channels (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rule_id UUID NOT NULL,
    channel VARCHAR(30) NOT NULL CHECK (
        channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')
    ),
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (rule_id, channel),
    CONSTRAINT fk_ntf_attention_channel_owner_rule
        FOREIGN KEY (tenant_id, user_id, rule_id)
        REFERENCES ntf_user_attention_rules (tenant_id, user_id, rule_id)
        ON DELETE CASCADE
);

CREATE TABLE ntf_recipient_notification_contexts (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notification_id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL CHECK (
        kind IN ('PERSON', 'CONVERSATION', 'THREAD', 'CHANNEL',
                 'PROJECT', 'WORK_ITEM', 'TOPIC')
    ),
    context_key VARCHAR(300) NOT NULL CHECK (
        length(context_key) BETWEEN 1 AND 300 AND context_key = trim(context_key)
    ),
    context_key_hash CHAR(64) NOT NULL CHECK (
        context_key_hash ~ '^[a-f0-9]{64}$'
    ),
    display_hint VARCHAR(160),
    matchable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, notification_id, kind, context_key_hash),
    CONSTRAINT fk_ntf_recipient_context_notification
        FOREIGN KEY (tenant_id, user_id, notification_id)
        REFERENCES ntf_user_notifications (tenant_id, user_id, notification_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_ntf_recipient_context_hint CHECK (
        display_hint IS NULL OR (
            length(display_hint) BETWEEN 1 AND 160
            AND display_hint = trim(display_hint)
        )
    )
);

-- This outbox intentionally has no payload, scope key, label, or notification content.
CREATE TABLE ntf_attention_rule_audit_outbox (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    subject_type VARCHAR(30) NOT NULL CHECK (
        subject_type IN ('ATTENTION_RULE', 'TEST_DELIVERY')
    ),
    subject_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL CHECK (
        event_type IN (
            'RULE_CREATED', 'RULE_UPDATED', 'RULE_DELETED',
            'POLICY_LOCKED', 'TEST_DELIVERY_CREATED'
        )
    ),
    scope_kind VARCHAR(30) CHECK (
        scope_kind IS NULL OR
        scope_kind IN ('APP_TYPE', 'ACTOR', 'THREAD', 'RESOURCE', 'TOPIC_TOKEN')
    ),
    effect VARCHAR(20) CHECK (
        effect IS NULL OR effect IN ('FOLLOW', 'PRIORITIZE', 'MUTE')
    ),
    subject_version BIGINT NOT NULL CHECK (subject_version >= 0),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE TABLE ntf_test_delivery_receipts (
    test_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    requested_channels JSONB NOT NULL CHECK (
        jsonb_typeof(requested_channels) = 'array'
        AND jsonb_array_length(requested_channels) BETWEEN 1 AND 6
    ),
    state VARCHAR(30) NOT NULL CHECK (
        state IN ('PENDING', 'COMPLETED', 'PARTIAL', 'FAILED', 'EXPIRED')
    ),
    stage_results JSONB NOT NULL CHECK (jsonb_typeof(stage_results) = 'array'),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ntf_test_delivery_owner
        UNIQUE (tenant_id, user_id, test_id),
    CONSTRAINT ck_ntf_test_delivery_ttl CHECK (
        expires_at = created_at + INTERVAL '24 hours'
    )
);

CREATE INDEX ix_ntf_attention_rule_active_owner
    ON ntf_user_attention_rules (
        tenant_id, user_id, scope_kind, starts_at, expires_at, updated_at DESC
    );
CREATE INDEX ix_ntf_attention_rule_expiry
    ON ntf_user_attention_rules (expires_at, tenant_id, user_id)
    WHERE expires_at IS NOT NULL;
CREATE INDEX ix_ntf_attention_rule_revision
    ON ntf_user_attention_rules (tenant_id, user_id, version, rule_id);
CREATE INDEX ix_ntf_recipient_context_match
    ON ntf_recipient_notification_contexts (
        tenant_id, user_id, kind, context_key_hash, notification_id
    )
    WHERE matchable;
CREATE INDEX ix_ntf_attention_audit_unpublished
    ON ntf_attention_rule_audit_outbox (occurred_at, event_id)
    WHERE published_at IS NULL;
CREATE INDEX ix_ntf_test_delivery_rate_window
    ON ntf_test_delivery_receipts (tenant_id, user_id, created_at DESC);
CREATE INDEX ix_ntf_test_delivery_expiry
    ON ntf_test_delivery_receipts (expires_at, test_id);

ALTER TABLE ntf_user_attention_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_attention_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_attention_rule_scope ON ntf_user_attention_rules
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (
                ntf_is_api()
                AND user_id = ntf_current_user_id()
                AND source = 'USER'
                AND NOT managed
                AND NOT exception_allowed
            )
        )
    );

ALTER TABLE ntf_user_attention_rule_channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_attention_rule_channels FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_attention_channel_scope ON ntf_user_attention_rule_channels
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

ALTER TABLE ntf_recipient_notification_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_recipient_notification_contexts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_recipient_context_read_scope ON ntf_recipient_notification_contexts
    FOR SELECT
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );
CREATE POLICY ntf_recipient_context_worker_scope ON ntf_recipient_notification_contexts
    FOR ALL
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_attention_rule_audit_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_attention_rule_audit_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_attention_audit_api_insert_scope
    ON ntf_attention_rule_audit_outbox FOR INSERT
    WITH CHECK (
        ntf_is_api()
        AND tenant_id = ntf_current_tenant_id()
        AND user_id = ntf_current_user_id()
    );
CREATE POLICY ntf_attention_audit_worker_scope ON ntf_attention_rule_audit_outbox
    FOR ALL
    USING (ntf_is_worker() AND tenant_id = ntf_current_tenant_id())
    WITH CHECK (ntf_is_worker() AND tenant_id = ntf_current_tenant_id());

ALTER TABLE ntf_test_delivery_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_test_delivery_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY ntf_test_delivery_scope ON ntf_test_delivery_receipts
    USING (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    )
    WITH CHECK (
        tenant_id = ntf_current_tenant_id()
        AND (
            ntf_is_worker()
            OR (ntf_is_api() AND user_id = ntf_current_user_id())
        )
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_user_attention_rules,
    ntf_user_attention_rule_channels TO dwp_notification_api;
GRANT SELECT ON ntf_recipient_notification_contexts TO dwp_notification_api;
GRANT INSERT ON ntf_attention_rule_audit_outbox TO dwp_notification_api;
GRANT SELECT, INSERT ON ntf_test_delivery_receipts TO dwp_notification_api;

GRANT SELECT, INSERT, UPDATE, DELETE ON ntf_user_attention_rules,
    ntf_user_attention_rule_channels, ntf_recipient_notification_contexts,
    ntf_attention_rule_audit_outbox, ntf_test_delivery_receipts
    TO dwp_notification_worker;

COMMENT ON TABLE ntf_user_attention_rules IS
    'Exact-match recipient attention rules. Scope keys are opaque owner references, never free text.';
COMMENT ON COLUMN ntf_user_attention_rules.scope_key_hash IS
    'SHA-256 comparison key used for exact owner-scoped lookup and uniqueness.';
COMMENT ON TABLE ntf_recipient_notification_contexts IS
    'Recipient-scoped, contract-governed context references with optional safe display hints.';
COMMENT ON TABLE ntf_attention_rule_audit_outbox IS
    'Content-free audit facts. Scope keys, display labels, and notification content are prohibited.';
COMMENT ON TABLE ntf_test_delivery_receipts IS
    'Short-lived diagnostic-only receipts; never projected into inbox, KPI, or business action state.';
