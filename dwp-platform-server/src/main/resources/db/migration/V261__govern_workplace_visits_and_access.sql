CREATE TABLE wp_visit_policies (
    policy_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES wp_tenant_policies(tenant_id),
    visit_type VARCHAR(80) NOT NULL,
    approval_required BOOLEAN NOT NULL,
    nda_required BOOLEAN NOT NULL,
    identity_verification_required BOOLEAN NOT NULL,
    allowed_from TIME NOT NULL,
    allowed_until TIME NOT NULL,
    minimum_collection_fields JSONB NOT NULL,
    retention_days INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, visit_type),
    UNIQUE (tenant_id, policy_id),
    CONSTRAINT ck_wp_visit_policy_window CHECK (allowed_until > allowed_from),
    CONSTRAINT ck_wp_visit_policy_fields CHECK
        (jsonb_typeof(minimum_collection_fields) = 'array'),
    CONSTRAINT ck_wp_visit_policy_retention CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_wp_visit_policy_version CHECK (version > 0)
);

CREATE TABLE wp_visit_access_zones (
    zone_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    site_id UUID NOT NULL,
    zone_code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    access_level VARCHAR(40) NOT NULL,
    provider_mapping_reference VARCHAR(120) NOT NULL,
    allowed_visit_types JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, zone_code),
    UNIQUE (tenant_id, zone_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_visit_zone_types CHECK (jsonb_typeof(allowed_visit_types) = 'array'),
    CONSTRAINT ck_wp_visit_zone_version CHECK (version > 0)
);

CREATE TABLE wp_visit_provider_bindings (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES wp_tenant_policies(tenant_id),
    provider_kind VARCHAR(16) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    configuration_version BIGINT NOT NULL,
    observed_configuration_version BIGINT,
    reported_state VARCHAR(32),
    evidence_reference VARCHAR(320),
    last_success_at TIMESTAMPTZ,
    source_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    manual_owner VARCHAR(160) NOT NULL,
    manual_procedure VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, provider_kind),
    UNIQUE (tenant_id, binding_id),
    CONSTRAINT ck_wp_visit_provider_kind CHECK (provider_kind IN ('VISITOR', 'ACCESS')),
    CONSTRAINT ck_wp_visit_provider_state CHECK
        (reported_state IS NULL OR reported_state IN
         ('CONFIGURED_UNVERIFIED', 'READY', 'DEGRADED', 'STALE')),
    CONSTRAINT ck_wp_visit_provider_versions CHECK
        (configuration_version > 0 AND version > 0
         AND (observed_configuration_version IS NULL OR observed_configuration_version > 0)),
    CONSTRAINT ck_wp_visit_provider_evidence CHECK
        ((reported_state IS NULL AND observed_configuration_version IS NULL
          AND evidence_reference IS NULL AND source_at IS NULL AND received_at IS NULL)
         OR (reported_state IS NOT NULL AND observed_configuration_version IS NOT NULL
          AND evidence_reference IS NOT NULL AND source_at IS NOT NULL AND received_at IS NOT NULL))
);

CREATE TABLE wp_visit_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    reservation_authority VARCHAR(20) NOT NULL,
    reservation_id UUID NOT NULL,
    reservation_version BIGINT NOT NULL,
    visit_type VARCHAR(80) NOT NULL,
    site_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    zone_ids JSONB NOT NULL,
    guest_fingerprint CHAR(64) NOT NULL,
    approval_required BOOLEAN NOT NULL,
    nda_required BOOLEAN NOT NULL,
    identity_verification_required BOOLEAN NOT NULL,
    minimum_collection_fields JSONB NOT NULL,
    visitor_truth_snapshot JSONB NOT NULL,
    access_truth_snapshot JSONB NOT NULL,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, preview_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_visit_preview_authority CHECK
        (reservation_authority IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_visit_preview_period CHECK
        (ends_at > starts_at AND expires_at > created_at),
    CONSTRAINT ck_wp_visit_preview_json CHECK
        (jsonb_typeof(zone_ids) = 'array'
         AND jsonb_typeof(minimum_collection_fields) = 'array'
         AND jsonb_typeof(visitor_truth_snapshot) = 'object'
         AND jsonb_typeof(access_truth_snapshot) = 'object'
         AND jsonb_typeof(limitations) = 'array'),
    CONSTRAINT ck_wp_visit_preview_guest_fingerprint CHECK
        (guest_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_visit_preview_version CHECK
        (reservation_version >= 0 AND version > 0)
);

CREATE TABLE wp_visit_preview_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    preview_id UUID NOT NULL,
    response_snapshot JSONB NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_visit_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_visit_preview_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_visit_preview_command_response CHECK
        (jsonb_typeof(response_snapshot) = 'object')
);

CREATE TABLE wp_visits (
    visit_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    preview_id UUID NOT NULL,
    reservation_authority VARCHAR(20) NOT NULL,
    reservation_id UUID NOT NULL,
    reservation_version BIGINT NOT NULL,
    visit_type VARCHAR(80) NOT NULL,
    site_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    visit_state VARCHAR(32) NOT NULL,
    approval_required BOOLEAN NOT NULL,
    provider_operation_evidence_reference VARCHAR(320),
    limitation_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, visit_id),
    FOREIGN KEY (tenant_id, preview_id) REFERENCES wp_visit_previews(tenant_id, preview_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_visit_authority CHECK
        (reservation_authority IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_visit_state CHECK (visit_state IN
        ('DRAFT', 'PREVIEWED', 'INVITED', 'APPROVAL_PENDING', 'APPROVED',
         'ACCESS_PENDING', 'READY', 'ARRIVED', 'CHECKED_OUT', 'REJECTED',
         'CANCELLED', 'ACCESS_FAILED', 'OVERSTAY', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_visit_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_visit_versions CHECK (reservation_version >= 0 AND version > 0)
);

CREATE INDEX idx_wp_visits_requester
    ON wp_visits(tenant_id, requester_user_id, starts_at DESC);
CREATE INDEX idx_wp_visits_exceptions
    ON wp_visits(tenant_id, visit_state, starts_at, updated_at);
CREATE INDEX idx_wp_visits_reservation
    ON wp_visits(tenant_id, reservation_authority, reservation_id);

CREATE TABLE wp_visit_guests (
    guest_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    visit_id UUID NOT NULL,
    opaque_guest_ref VARCHAR(320),
    masked_label VARCHAR(160) NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    field_retention_expires_at JSONB NOT NULL,
    search_token_sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, visit_id, opaque_guest_ref),
    UNIQUE (tenant_id, guest_id),
    FOREIGN KEY (tenant_id, visit_id)
        REFERENCES wp_visits(tenant_id, visit_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_visit_guest_retention CHECK
        (jsonb_typeof(field_retention_expires_at) = 'object'),
    CONSTRAINT ck_wp_visit_guest_search CHECK
        (search_token_sha256 IS NULL OR search_token_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_visit_zone_selections (
    tenant_id BIGINT NOT NULL,
    visit_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, visit_id, zone_id),
    FOREIGN KEY (tenant_id, visit_id)
        REFERENCES wp_visits(tenant_id, visit_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, zone_id)
        REFERENCES wp_visit_access_zones(tenant_id, zone_id)
);

CREATE TABLE wp_visit_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    visit_id UUID NOT NULL,
    command_scope VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(20) NOT NULL,
    status_href VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, actor_user_id, command_scope, idempotency_key),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, visit_id) REFERENCES wp_visits(tenant_id, visit_id),
    CONSTRAINT ck_wp_visit_command_state CHECK
        (command_state IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_visit_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_visit_timeline (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    visit_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    visit_state VARCHAR(32) NOT NULL,
    detail_code VARCHAR(120),
    actor_user_id BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, event_id),
    FOREIGN KEY (tenant_id, visit_id)
        REFERENCES wp_visits(tenant_id, visit_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_visit_timeline_state CHECK (visit_state IN
        ('DRAFT', 'PREVIEWED', 'INVITED', 'APPROVAL_PENDING', 'APPROVED',
         'ACCESS_PENDING', 'READY', 'ARRIVED', 'CHECKED_OUT', 'REJECTED',
         'CANCELLED', 'ACCESS_FAILED', 'OVERSTAY', 'RESULT_UNKNOWN'))
);

CREATE TABLE wp_visit_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    visit_id UUID NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    deduplication_key VARCHAR(200) NOT NULL,
    evidence_reference VARCHAR(320),
    delivery_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, operation_type, deduplication_key),
    UNIQUE (tenant_id, outbox_id),
    FOREIGN KEY (tenant_id, visit_id) REFERENCES wp_visits(tenant_id, visit_id),
    CONSTRAINT ck_wp_visit_outbox_operation CHECK
        (operation_type IN ('SEND_INVITATION', 'REQUEST_ACCESS', 'REVOKE_ACCESS',
         'NOTIFY_HOST', 'CHECK_PROVIDER_STATUS')),
    CONSTRAINT ck_wp_visit_outbox_state CHECK
        (delivery_state IN ('PENDING', 'PROCESSING', 'DELIVERED', 'RETRY',
         'DEAD_LETTER', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_visit_outbox_attempts CHECK (attempt_count >= 0)
);

ALTER TABLE wp_visit_outbox
    ADD COLUMN related_outbox_id UUID,
    ADD CONSTRAINT fk_wp_visit_outbox_related
        FOREIGN KEY (tenant_id, related_outbox_id)
        REFERENCES wp_visit_outbox(tenant_id, outbox_id);

CREATE TABLE wp_visit_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES wp_tenant_policies(tenant_id),
    visit_id UUID,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(160),
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, visit_id) REFERENCES wp_visits(tenant_id, visit_id),
    CONSTRAINT ck_wp_visit_audit_snapshot CHECK (jsonb_typeof(snapshot) = 'object')
);

CREATE TABLE wp_visit_management_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES wp_tenant_policies(tenant_id),
    actor_user_id BIGINT NOT NULL,
    command_scope VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    resource_version BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, actor_user_id, command_scope, idempotency_key),
    CONSTRAINT ck_wp_visit_management_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_visit_management_version CHECK (resource_version > 0),
    CONSTRAINT ck_wp_visit_management_type CHECK
        (resource_type IN ('POLICY', 'ZONE', 'PROVIDER', 'DEVICE'))
);

CREATE TABLE wp_visit_kiosk_devices (
    device_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    device_identity_sha256 CHAR(64) NOT NULL,
    site_id UUID NOT NULL,
    policy_id UUID,
    privacy_notice_version VARCHAR(80) NOT NULL,
    privacy_notice_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    last_heartbeat_at TIMESTAMPTZ,
    help_requested BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, device_identity_sha256),
    UNIQUE (tenant_id, device_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, policy_id) REFERENCES wp_visit_policies(tenant_id, policy_id),
    CONSTRAINT ck_wp_visit_device_identity CHECK
        (device_identity_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_visit_device_version CHECK (version > 0)
);

COMMENT ON COLUMN wp_visit_guests.opaque_guest_ref IS
    'Opaque Visitor Provider or privacy-vault reference; raw identity data is forbidden.';
COMMENT ON TABLE wp_visit_outbox IS
    'Contains operation and evidence references only; credentials, QR, NFC, badge data and secrets are forbidden.';
