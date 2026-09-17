CREATE TABLE wp_facility_closure_impact_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    site_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    reservation_owner VARCHAR(16) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    resource_version BIGINT NOT NULL,
    preview_version BIGINT NOT NULL DEFAULT 1,
    confirmation_token VARCHAR(160) NOT NULL,
    affected_booking_count INTEGER NOT NULL,
    affected_recipient_count INTEGER NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_facility_closure_preview_key UNIQUE
        (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT uk_wp_facility_closure_preview_tenant UNIQUE
        (tenant_id, preview_id),
    CONSTRAINT uk_wp_facility_closure_preview_token UNIQUE
        (tenant_id, confirmation_token),
    CONSTRAINT fk_wp_facility_closure_preview_resource FOREIGN KEY
        (tenant_id, resource_id) REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT fk_wp_facility_closure_preview_site FOREIGN KEY
        (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_facility_closure_preview_owner CHECK
        (reservation_owner IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_facility_closure_preview_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_facility_closure_preview_version CHECK
        (resource_version >= 0 AND preview_version > 0),
    CONSTRAINT ck_wp_facility_closure_preview_counts CHECK
        (affected_booking_count >= 0 AND affected_booking_count <= 1000
         AND affected_recipient_count >= 0),
    CONSTRAINT ck_wp_facility_closure_preview_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_facility_closure_preview_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_facility_closure_preview_expiry CHECK (expires_at > created_at)
);

CREATE TABLE wp_facility_closure_impact_items (
    preview_item_id UUID PRIMARY KEY,
    preview_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    reservation_owner VARCHAR(16) NOT NULL,
    booking_id UUID NOT NULL,
    event_id UUID,
    source_workplace_resource_id UUID NOT NULL,
    source_owner_resource_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    booking_status VARCHAR(24) NOT NULL,
    booking_version BIGINT NOT NULL,
    recipient_user_ids JSONB NOT NULL,
    replacement_block_reason VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_facility_closure_impact_booking UNIQUE
        (preview_id, reservation_owner, booking_id),
    CONSTRAINT uk_wp_facility_closure_impact_item_tenant UNIQUE
        (tenant_id, preview_item_id),
    CONSTRAINT fk_wp_facility_closure_item_preview_tenant FOREIGN KEY
        (tenant_id, preview_id)
        REFERENCES wp_facility_closure_impact_previews(tenant_id, preview_id) ON DELETE CASCADE,
    CONSTRAINT fk_wp_facility_closure_item_resource FOREIGN KEY
        (tenant_id, source_workplace_resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_facility_closure_item_owner CHECK
        (reservation_owner IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_facility_closure_item_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_facility_closure_item_version CHECK (booking_version >= 0),
    CONSTRAINT ck_wp_facility_closure_item_recipients CHECK
        (jsonb_typeof(recipient_user_ids) = 'array'),
    CONSTRAINT ck_wp_facility_closure_item_replacement_block CHECK
        (replacement_block_reason IS NULL
         OR replacement_block_reason ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

CREATE TABLE wp_facility_closure_replacement_candidates (
    preview_item_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    workplace_resource_id UUID NOT NULL,
    owner_resource_id UUID NOT NULL,
    resource_name VARCHAR(160) NOT NULL,
    floor_id UUID NOT NULL,
    resource_version BIGINT NOT NULL,
    rank INTEGER NOT NULL,
    PRIMARY KEY (preview_item_id, workplace_resource_id),
    CONSTRAINT fk_wp_facility_closure_candidate_item FOREIGN KEY
        (tenant_id, preview_item_id)
        REFERENCES wp_facility_closure_impact_items(tenant_id, preview_item_id) ON DELETE CASCADE,
    CONSTRAINT fk_wp_facility_closure_candidate_resource FOREIGN KEY
        (tenant_id, workplace_resource_id) REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_facility_closure_candidate_rank CHECK (rank BETWEEN 1 AND 20),
    CONSTRAINT ck_wp_facility_closure_candidate_version CHECK (resource_version >= 0)
);

CREATE TABLE wp_facility_closure_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    site_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    preview_id UUID NOT NULL,
    closure_id UUID REFERENCES wp_experience_facility_closures(closure_id),
    command_state VARCHAR(24) NOT NULL,
    expected_preview_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    explicit_confirmation BOOLEAN NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    kept_count INTEGER NOT NULL DEFAULT 0,
    cancelled_count INTEGER NOT NULL DEFAULT 0,
    replaced_count INTEGER NOT NULL DEFAULT 0,
    notification_recipient_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_facility_closure_command_key UNIQUE
        (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT uk_wp_facility_closure_command_tenant UNIQUE
        (tenant_id, command_id),
    CONSTRAINT uk_wp_facility_closure_command_closure UNIQUE (tenant_id, closure_id),
    CONSTRAINT fk_wp_facility_closure_command_resource FOREIGN KEY
        (tenant_id, resource_id) REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT fk_wp_facility_closure_command_preview FOREIGN KEY
        (tenant_id, preview_id)
        REFERENCES wp_facility_closure_impact_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_facility_closure_command_state CHECK
        (command_state IN ('ACCEPTED','EXECUTING','SUCCEEDED','PARTIAL','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_facility_closure_command_version CHECK
        (expected_preview_version > 0 AND version > 0),
    CONSTRAINT ck_wp_facility_closure_command_reason CHECK
        (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_facility_closure_command_confirmation CHECK (explicit_confirmation),
    CONSTRAINT ck_wp_facility_closure_command_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_facility_closure_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_facility_closure_command_counts CHECK
        (kept_count >= 0 AND cancelled_count >= 0 AND replaced_count >= 0
         AND notification_recipient_count >= 0)
);

CREATE TABLE wp_facility_closure_command_items (
    command_item_id UUID PRIMARY KEY,
    command_id UUID NOT NULL,
    preview_item_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    reservation_owner VARCHAR(16) NOT NULL,
    booking_id UUID NOT NULL,
    selected_action VARCHAR(16) NOT NULL,
    expected_booking_version BIGINT NOT NULL,
    replacement_workplace_resource_id UUID,
    replacement_owner_resource_id UUID,
    replacement_resource_version BIGINT,
    result_state VARCHAR(24) NOT NULL,
    result_code VARCHAR(80) NOT NULL,
    resulting_booking_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_facility_closure_command_item UNIQUE (command_id, preview_item_id),
    CONSTRAINT fk_wp_facility_closure_command_item_command FOREIGN KEY
        (tenant_id, command_id)
        REFERENCES wp_facility_closure_commands(tenant_id, command_id) ON DELETE CASCADE,
    CONSTRAINT fk_wp_facility_closure_command_item_preview FOREIGN KEY
        (tenant_id, preview_item_id)
        REFERENCES wp_facility_closure_impact_items(tenant_id, preview_item_id),
    CONSTRAINT ck_wp_facility_closure_command_item_owner CHECK
        (reservation_owner IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_facility_closure_command_item_action CHECK
        (selected_action IN ('KEEP','CANCEL','REPLACE')),
    CONSTRAINT ck_wp_facility_closure_command_item_result CHECK
        (result_state IN ('PENDING','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_facility_closure_command_item_version CHECK
        (expected_booking_version >= 0
         AND (resulting_booking_version IS NULL OR resulting_booking_version >= 0)),
    CONSTRAINT ck_wp_facility_closure_command_item_replacement CHECK
        ((selected_action = 'REPLACE'
          AND replacement_workplace_resource_id IS NOT NULL
          AND replacement_owner_resource_id IS NOT NULL
          AND replacement_resource_version IS NOT NULL
          AND replacement_resource_version >= 0)
         OR (selected_action <> 'REPLACE'
          AND replacement_workplace_resource_id IS NULL
          AND replacement_owner_resource_id IS NULL
          AND replacement_resource_version IS NULL))
);

CREATE TABLE wp_facility_closure_notification_events (
    command_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    booking_id UUID NOT NULL,
    impact_action VARCHAR(16) NOT NULL,
    event_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (command_id, booking_id, recipient_user_id),
    CONSTRAINT fk_wp_facility_closure_notification_command FOREIGN KEY
        (tenant_id, command_id)
        REFERENCES wp_facility_closure_commands(tenant_id, command_id) ON DELETE CASCADE,
    CONSTRAINT uk_wp_facility_closure_notification_event UNIQUE (event_id),
    CONSTRAINT ck_wp_facility_closure_notification_recipient CHECK (recipient_user_id > 0),
    CONSTRAINT ck_wp_facility_closure_notification_action CHECK
        (impact_action IN ('KEEP','CANCEL','REPLACE'))
);

CREATE TABLE wp_facility_closure_command_events (
    command_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    evidence JSONB NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wp_facility_closure_event_command FOREIGN KEY
        (tenant_id, command_id)
        REFERENCES wp_facility_closure_commands(tenant_id, command_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_facility_closure_command_event_evidence CHECK
        (jsonb_typeof(evidence) = 'object')
);

CREATE TABLE wp_facility_closure_notification_operations (
    operation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    expected_command_version BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    affected_event_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wp_facility_closure_notification_operation_key UNIQUE
        (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_wp_facility_closure_operation_command FOREIGN KEY
        (tenant_id, command_id)
        REFERENCES wp_facility_closure_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_facility_closure_notification_operation_type CHECK
        (operation_type IN ('RECONCILE','RETRY')),
    CONSTRAINT ck_wp_facility_closure_notification_operation_version CHECK
        (expected_command_version > 0),
    CONSTRAINT ck_wp_facility_closure_notification_operation_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_facility_closure_notification_operation_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_facility_closure_notification_operation_count CHECK
        (affected_event_count >= 0)
);

CREATE INDEX idx_wp_facility_closure_preview_expiry
    ON wp_facility_closure_impact_previews(tenant_id, expires_at, preview_id);
CREATE INDEX idx_wp_facility_closure_command_status
    ON wp_facility_closure_commands(tenant_id, site_id, created_at DESC, command_id);
CREATE INDEX idx_wp_facility_closure_notification_status
    ON wp_facility_closure_notification_events(tenant_id, command_id, event_id);
CREATE INDEX idx_wp_facility_closure_command_audit
    ON wp_facility_closure_command_events(tenant_id, command_id, occurred_at, command_event_id);
CREATE INDEX idx_wp_facility_closure_notification_operation_command
    ON wp_facility_closure_notification_operations(tenant_id, command_id, created_at DESC);

COMMENT ON TABLE wp_facility_closure_impact_previews IS
    'Versioned, expiring snapshot of Workplace or canonical Calendar bookings affected by a proposed closure.';
COMMENT ON TABLE wp_facility_closure_commands IS
    'Durable receipt for an explicitly confirmed facility closure impact command.';
COMMENT ON TABLE wp_facility_closure_notification_events IS
    'Links impacted recipients to service-local sys_domain_event_outbox events; it is not external delivery proof.';
