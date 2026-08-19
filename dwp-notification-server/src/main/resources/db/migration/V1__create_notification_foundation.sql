CREATE TABLE ntf_notification_types (
    type_id UUID PRIMARY KEY,
    tenant_id BIGINT,
    scope_type VARCHAR(20) NOT NULL CHECK (scope_type IN ('PROVIDER', 'TENANT')),
    scope_id VARCHAR(160) NOT NULL,
    type_key VARCHAR(160) NOT NULL,
    owner_app_key VARCHAR(100) NOT NULL,
    owner_team VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ntf_type_scope_tenant CHECK (
        (scope_type = 'PROVIDER' AND tenant_id IS NULL)
        OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT uq_ntf_type_scope UNIQUE (scope_type, scope_id, type_key),
    CONSTRAINT uq_ntf_type_tenant_identity UNIQUE (tenant_id, type_id)
);

CREATE TABLE ntf_notification_type_versions (
    type_version_id UUID PRIMARY KEY,
    tenant_id BIGINT,
    type_id UUID NOT NULL REFERENCES ntf_notification_types(type_id),
    version INTEGER NOT NULL CHECK (version > 0),
    source_event_type VARCHAR(200) NOT NULL,
    min_schema_version INTEGER NOT NULL CHECK (min_schema_version > 0),
    max_schema_version INTEGER NOT NULL CHECK (max_schema_version >= min_schema_version),
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    urgency VARCHAR(20) NOT NULL CHECK (urgency IN ('INFORMATIONAL', 'ACTIONABLE', 'CRITICAL')),
    data_classification VARCHAR(30) NOT NULL DEFAULT 'INTERNAL',
    contract_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'DEPRECATED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_type_version UNIQUE (type_id, version),
    CONSTRAINT uq_ntf_type_version_tenant_identity
        UNIQUE (tenant_id, type_version_id),
    CONSTRAINT fk_ntf_type_version_tenant_type
        FOREIGN KEY (tenant_id, type_id)
        REFERENCES ntf_notification_types (tenant_id, type_id)
);

CREATE TABLE ntf_template_versions (
    template_version_id UUID PRIMARY KEY,
    tenant_id BIGINT,
    type_version_id UUID NOT NULL REFERENCES ntf_notification_type_versions(type_version_id),
    channel VARCHAR(30) NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')),
    locale VARCHAR(35) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    title_template VARCHAR(300) NOT NULL,
    preview_template VARCHAR(600) NOT NULL,
    body_template TEXT NOT NULL,
    action_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    state VARCHAR(20) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    checksum VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_template_version UNIQUE (type_version_id, channel, locale, version),
    CONSTRAINT uq_ntf_template_tenant_identity
        UNIQUE (tenant_id, template_version_id),
    CONSTRAINT fk_ntf_template_tenant_type_version
        FOREIGN KEY (tenant_id, type_version_id)
        REFERENCES ntf_notification_type_versions (tenant_id, type_version_id)
);

CREATE TABLE ntf_routing_policies (
    policy_id UUID PRIMARY KEY,
    tenant_id BIGINT,
    scope_type VARCHAR(20) NOT NULL CHECK (scope_type IN ('PROVIDER', 'TENANT', 'APP', 'TYPE')),
    scope_key VARCHAR(200) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    state VARCHAR(20) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    quiet_hours_bypass BOOLEAN NOT NULL DEFAULT FALSE,
    digest_mode VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE',
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_policy_version UNIQUE (tenant_id, scope_type, scope_key, version),
    CONSTRAINT uq_ntf_policy_tenant_identity UNIQUE (tenant_id, policy_id),
    CONSTRAINT ck_ntf_policy_effective_range CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from
    )
);

CREATE TABLE ntf_policy_channel_rules (
    policy_channel_rule_id UUID PRIMARY KEY,
    tenant_id BIGINT,
    policy_id UUID NOT NULL REFERENCES ntf_routing_policies(policy_id),
    channel VARCHAR(30) NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')),
    enabled BOOLEAN NOT NULL,
    default_mode VARCHAR(20) NOT NULL CHECK (default_mode IN ('IMMEDIATE', 'DIGEST', 'MUTED')),
    user_overridable BOOLEAN NOT NULL,
    max_per_window INTEGER CHECK (max_per_window IS NULL OR max_per_window > 0),
    provider_route_key VARCHAR(160),
    CONSTRAINT uq_ntf_policy_channel UNIQUE (policy_id, channel),
    CONSTRAINT fk_ntf_policy_channel_tenant_policy
        FOREIGN KEY (tenant_id, policy_id)
        REFERENCES ntf_routing_policies (tenant_id, policy_id)
);

CREATE TABLE ntf_notification_intents (
    intent_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_event_id UUID NOT NULL,
    source_event_type VARCHAR(200) NOT NULL,
    source_schema_version INTEGER NOT NULL CHECK (source_schema_version > 0),
    type_version_id UUID NOT NULL REFERENCES ntf_notification_type_versions(type_version_id),
    notification_id UUID,
    source_payload_hash VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(160),
    decision VARCHAR(30) NOT NULL CHECK (decision IN ('MATERIALIZED', 'DUPLICATE', 'QUARANTINED')),
    reason_code VARCHAR(200),
    sanitized_variables JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_intent_event_type UNIQUE (tenant_id, source_event_id, type_version_id)
);

CREATE TABLE ntf_notifications (
    notification_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    type_version_id UUID NOT NULL REFERENCES ntf_notification_type_versions(type_version_id),
    thread_key VARCHAR(200) NOT NULL,
    actor_ref VARCHAR(300),
    subject_ref VARCHAR(300),
    target_ref VARCHAR(300),
    safe_body TEXT NOT NULL,
    action_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    sanitized_template_variables JSONB NOT NULL DEFAULT '{}'::jsonb,
    first_activity_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    occurrence_count BIGINT NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    closed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_notification_tenant_identity
        UNIQUE (tenant_id, notification_id)
);

ALTER TABLE ntf_notification_intents
    ADD CONSTRAINT fk_ntf_intent_notification
    FOREIGN KEY (tenant_id, notification_id)
    REFERENCES ntf_notifications(tenant_id, notification_id);

CREATE UNIQUE INDEX uq_ntf_active_thread
    ON ntf_notifications (tenant_id, type_version_id, thread_key)
    WHERE closed_at IS NULL;

CREATE TABLE ntf_user_notifications (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    notification_id UUID NOT NULL,
    reason_code VARCHAR(200) NOT NULL,
    effective_priority VARCHAR(20) NOT NULL CHECK (effective_priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    action_required BOOLEAN NOT NULL DEFAULT FALSE,
    due_at TIMESTAMPTZ,
    locale VARCHAR(35) NOT NULL,
    in_app_template_version_id UUID NOT NULL REFERENCES ntf_template_versions(template_version_id),
    safe_title VARCHAR(300) NOT NULL,
    safe_preview VARCHAR(600) NOT NULL,
    search_text TEXT NOT NULL,
    inbox_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (inbox_state IN ('ACTIVE', 'DONE')),
    read_at TIMESTAMPTZ,
    saved_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    snoozed_until TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ NOT NULL,
    change_version BIGINT NOT NULL CHECK (change_version > 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, notification_id),
    CONSTRAINT fk_ntf_user_notification_tenant_notification
        FOREIGN KEY (tenant_id, notification_id)
        REFERENCES ntf_notifications (tenant_id, notification_id)
);

CREATE TABLE ntf_user_counters (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    unread_count BIGINT NOT NULL DEFAULT 0 CHECK (unread_count >= 0),
    actionable_unread_count BIGINT NOT NULL DEFAULT 0 CHECK (actionable_unread_count >= 0),
    urgent_count BIGINT NOT NULL DEFAULT 0 CHECK (urgent_count >= 0),
    counter_version BIGINT NOT NULL DEFAULT 0 CHECK (counter_version >= 0),
    min_available_change_version BIGINT NOT NULL DEFAULT 0 CHECK (min_available_change_version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_ntf_counter_watermark CHECK (min_available_change_version <= counter_version)
);

CREATE TABLE ntf_user_delivery_profiles (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    quiet_schedule JSONB NOT NULL DEFAULT '{}'::jsonb,
    default_channels JSONB NOT NULL DEFAULT '["IN_APP"]'::jsonb,
    digest_frequency VARCHAR(20) NOT NULL DEFAULT 'IMMEDIATE' CHECK (digest_frequency IN ('IMMEDIATE', 'DAILY', 'WEEKLY', 'NONE')),
    digest_local_time TIME NOT NULL DEFAULT TIME '09:00',
    digest_day_of_week SMALLINT CHECK (digest_day_of_week BETWEEN 1 AND 7),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE ntf_user_subscription_rules (
    rule_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    app_key VARCHAR(100) NOT NULL,
    type_key VARCHAR(160) NOT NULL,
    delivery_mode VARCHAR(30) NOT NULL CHECK (
        delivery_mode IN ('IMMEDIATE', 'DAILY_DIGEST', 'WEEKLY_DIGEST', 'MUTED')
    ),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_subscription_scope UNIQUE (tenant_id, user_id, app_key, type_key),
    CONSTRAINT uq_ntf_subscription_tenant_identity
        UNIQUE (tenant_id, user_id, rule_id)
);

CREATE TABLE ntf_user_subscription_rule_channels (
    rule_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL CHECK (
        channel IN ('IN_APP', 'EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')
    ),
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (rule_id, channel),
    CONSTRAINT fk_ntf_subscription_channel_tenant_rule
        FOREIGN KEY (tenant_id, user_id, rule_id)
        REFERENCES ntf_user_subscription_rules (tenant_id, user_id, rule_id)
        ON DELETE CASCADE
);

CREATE TABLE ntf_delivery_jobs (
    job_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    notification_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL CHECK (channel IN ('EMAIL', 'WEB_PUSH', 'MOBILE_PUSH', 'TEAMS', 'SLACK')),
    qos_lane VARCHAR(20) NOT NULL CHECK (qos_lane IN ('CRITICAL', 'INTERACTIVE', 'BULK')),
    template_version_id UUID NOT NULL REFERENCES ntf_template_versions(template_version_id),
    state VARCHAR(30) NOT NULL CHECK (state IN ('DISABLED', 'QUEUED', 'LEASED', 'SENT', 'FAILED', 'UNKNOWN')),
    scheduled_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    dispatch_version BIGINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    idempotency_key VARCHAR(200) NOT NULL,
    rendered_content_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_delivery_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_ntf_delivery_tenant_notification
        FOREIGN KEY (tenant_id, notification_id)
        REFERENCES ntf_notifications (tenant_id, notification_id)
);

CREATE TABLE ntf_outbox_events (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    event_key VARCHAR(300) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ntf_outbox_event_key UNIQUE (tenant_id, event_key)
);

CREATE TABLE ntf_idempotency_receipts (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, user_id, idempotency_key)
);

CREATE INDEX ix_ntf_user_feed
    ON ntf_user_notifications (tenant_id, user_id, inbox_state, last_activity_at DESC, notification_id DESC);
CREATE INDEX ix_ntf_user_unread
    ON ntf_user_notifications (tenant_id, user_id, last_activity_at DESC)
    WHERE read_at IS NULL AND inbox_state = 'ACTIVE';
CREATE INDEX ix_ntf_user_sync
    ON ntf_user_notifications (tenant_id, user_id, change_version, notification_id);
CREATE INDEX ix_ntf_user_search
    ON ntf_user_notifications USING GIN (to_tsvector('simple', search_text));
CREATE INDEX ix_ntf_subscription_owner
    ON ntf_user_subscription_rules (tenant_id, user_id, app_key, type_key);
CREATE INDEX ix_ntf_user_snooze_due
    ON ntf_user_notifications (snoozed_until, tenant_id, user_id)
    WHERE snoozed_until IS NOT NULL AND inbox_state = 'ACTIVE';
CREATE INDEX ix_ntf_intent_source_event
    ON ntf_notification_intents (tenant_id, source_event_id);
CREATE INDEX ix_ntf_jobs_fair_due
    ON ntf_delivery_jobs (qos_lane, state, next_attempt_at, tenant_id, scheduled_at)
    WHERE state IN ('QUEUED', 'LEASED');
CREATE INDEX ix_ntf_outbox_due
    ON ntf_outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION ntf_reject_published_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.state = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published notification artifacts are immutable';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.state = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published notification artifacts are immutable';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_ntf_template_immutable
BEFORE UPDATE OR DELETE ON ntf_template_versions
FOR EACH ROW EXECUTE FUNCTION ntf_reject_published_mutation();

CREATE TRIGGER trg_ntf_policy_immutable
BEFORE UPDATE OR DELETE ON ntf_routing_policies
FOR EACH ROW EXECUTE FUNCTION ntf_reject_published_mutation();
