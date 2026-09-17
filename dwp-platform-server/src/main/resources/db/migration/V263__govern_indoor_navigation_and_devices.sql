CREATE TABLE wp_navigation_graph_revisions (
    graph_revision_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    site_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    content_hash CHAR(64) NOT NULL,
    change_summary VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    submitted_at TIMESTAMPTZ,
    submitted_by BIGINT,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    UNIQUE (tenant_id, graph_revision_id),
    UNIQUE (tenant_id, site_id, revision_number),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    CONSTRAINT ck_wp_navigation_graph_state CHECK
        (lifecycle_state IN ('DRAFT','REVIEW','PUBLISHED','ARCHIVED')),
    CONSTRAINT ck_wp_navigation_graph_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_navigation_graph_version CHECK (revision_number > 0 AND version > 0),
    CONSTRAINT ck_wp_navigation_graph_publication CHECK
        (lifecycle_state <> 'PUBLISHED'
         OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL
             AND published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE UNIQUE INDEX uk_wp_navigation_published_graph
    ON wp_navigation_graph_revisions(tenant_id, site_id)
    WHERE lifecycle_state = 'PUBLISHED';

CREATE TABLE wp_navigation_nodes (
    node_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    graph_revision_id UUID NOT NULL,
    floor_id UUID NOT NULL,
    node_code VARCHAR(80) NOT NULL,
    node_kind VARCHAR(24) NOT NULL,
    position_x NUMERIC(7,3) NOT NULL,
    position_y NUMERIC(7,3) NOT NULL,
    accessible BOOLEAN NOT NULL DEFAULT TRUE,
    restricted_zone_id UUID,
    UNIQUE (tenant_id, graph_revision_id, node_id),
    UNIQUE (tenant_id, graph_revision_id, node_code),
    FOREIGN KEY (tenant_id, graph_revision_id)
        REFERENCES wp_navigation_graph_revisions(tenant_id, graph_revision_id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, floor_id) REFERENCES wp_floors(tenant_id, floor_id),
    FOREIGN KEY (tenant_id, floor_id, restricted_zone_id)
        REFERENCES wp_zones(tenant_id, floor_id, zone_id),
    CONSTRAINT ck_wp_navigation_node_kind CHECK
        (node_kind IN ('ENTRY','JUNCTION','POI','ELEVATOR','STAIR','RAMP','HELP_DESK')),
    CONSTRAINT ck_wp_navigation_node_position CHECK
        (position_x BETWEEN 0 AND 100 AND position_y BETWEEN 0 AND 100)
);

CREATE TABLE wp_navigation_edges (
    edge_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    graph_revision_id UUID NOT NULL,
    from_node_id UUID NOT NULL,
    to_node_id UUID NOT NULL,
    travel_seconds INTEGER NOT NULL,
    travel_mode VARCHAR(20) NOT NULL,
    bidirectional BOOLEAN NOT NULL DEFAULT TRUE,
    accessible BOOLEAN NOT NULL DEFAULT TRUE,
    required_permission VARCHAR(120),
    UNIQUE (tenant_id, graph_revision_id, edge_id),
    FOREIGN KEY (tenant_id, graph_revision_id)
        REFERENCES wp_navigation_graph_revisions(tenant_id, graph_revision_id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, graph_revision_id, from_node_id)
        REFERENCES wp_navigation_nodes(tenant_id, graph_revision_id, node_id),
    FOREIGN KEY (tenant_id, graph_revision_id, to_node_id)
        REFERENCES wp_navigation_nodes(tenant_id, graph_revision_id, node_id),
    CONSTRAINT ck_wp_navigation_edge_mode CHECK
        (travel_mode IN ('WALK','STAIR','ELEVATOR','RAMP')),
    CONSTRAINT ck_wp_navigation_edge_duration CHECK (travel_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_wp_navigation_edge_nodes CHECK (from_node_id <> to_node_id)
);

CREATE TABLE wp_navigation_pois (
    poi_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    graph_revision_id UUID NOT NULL,
    node_id UUID NOT NULL,
    site_id UUID NOT NULL,
    floor_id UUID NOT NULL,
    resource_id UUID,
    category VARCHAR(32) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    direction_hint_ko VARCHAR(300),
    direction_hint_en VARCHAR(300),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, graph_revision_id, poi_id),
    FOREIGN KEY (tenant_id, graph_revision_id, node_id)
        REFERENCES wp_navigation_nodes(tenant_id, graph_revision_id, node_id)
        ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    FOREIGN KEY (tenant_id, floor_id, resource_id)
        REFERENCES wp_resources(tenant_id, floor_id, resource_id),
    CONSTRAINT ck_wp_navigation_poi_category CHECK (category IN
        ('ROOM','ELEVATOR','STAIR','RESTROOM','PRINTER','HELP_DESK','AED',
         'EMERGENCY_EXIT','ASSEMBLY_POINT','ENTRY','OTHER'))
);

CREATE INDEX idx_wp_navigation_pois_scope
    ON wp_navigation_pois(tenant_id, site_id, floor_id, category) WHERE active;

CREATE TABLE wp_navigation_graph_commands (
    graph_command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    graph_revision_id UUID NOT NULL,
    command_type VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, graph_revision_id)
        REFERENCES wp_navigation_graph_revisions(tenant_id, graph_revision_id),
    CONSTRAINT ck_wp_navigation_graph_command_type CHECK
        (command_type IN ('CREATE','REVIEW','PUBLISH','ARCHIVE')),
    CONSTRAINT ck_wp_navigation_graph_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_navigation_provider_truth (
    provider_truth_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    capability VARCHAR(24) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    configuration_version BIGINT NOT NULL,
    observed_configuration_version BIGINT,
    reported_state VARCHAR(24),
    evidence_reference VARCHAR(320),
    source_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    error_code VARCHAR(120),
    configured BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, capability),
    UNIQUE (tenant_id, provider_truth_id),
    CONSTRAINT ck_wp_navigation_provider_capability CHECK
        (capability IN ('MDM','GRAPH','BLE','NFC','SPEED_GATE','SENSOR','MTLS','TPM')),
    CONSTRAINT ck_wp_navigation_provider_state CHECK
        (reported_state IS NULL OR reported_state IN ('HEALTHY','DEGRADED','UNAVAILABLE')),
    CONSTRAINT ck_wp_navigation_provider_versions CHECK
        (configuration_version > 0 AND version > 0
         AND (observed_configuration_version IS NULL OR observed_configuration_version > 0)),
    CONSTRAINT ck_wp_navigation_provider_evidence CHECK
        ((reported_state IS NULL AND observed_configuration_version IS NULL
          AND evidence_reference IS NULL AND source_at IS NULL AND received_at IS NULL)
         OR (reported_state IS NOT NULL AND observed_configuration_version IS NOT NULL
          AND evidence_reference IS NOT NULL AND source_at IS NOT NULL AND received_at IS NOT NULL))
);

CREATE TABLE wp_navigation_devices (
    device_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    device_identity_sha256 CHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    device_type VARCHAR(24) NOT NULL,
    registration_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    site_id UUID,
    floor_id UUID,
    resource_id UUID,
    hardware_model VARCHAR(160) NOT NULL,
    os_version VARCHAR(80) NOT NULL,
    app_version VARCHAR(80),
    policy_version VARCHAR(80),
    heartbeat_at TIMESTAMPTZ,
    schedule_source_at TIMESTAMPTZ,
    schedule_received_at TIMESTAMPTZ,
    recent_error_code VARCHAR(120),
    safety_offline_fallback BOOLEAN NOT NULL DEFAULT TRUE,
    approved_at TIMESTAMPTZ,
    approved_by BIGINT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, device_identity_sha256),
    UNIQUE (tenant_id, device_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    FOREIGN KEY (tenant_id, floor_id, resource_id)
        REFERENCES wp_resources(tenant_id, floor_id, resource_id),
    CONSTRAINT ck_wp_navigation_device_identity CHECK
        (device_identity_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_navigation_device_type CHECK
        (device_type IN ('ROOM_PANEL','STATUS_BOARD')),
    CONSTRAINT ck_wp_navigation_device_state CHECK
        (registration_state IN ('PENDING','APPROVED','BOUND','SUSPENDED','RETIRED')),
    CONSTRAINT ck_wp_navigation_device_binding CHECK
        ((registration_state IN ('PENDING','APPROVED') AND site_id IS NULL
          AND floor_id IS NULL AND resource_id IS NULL)
         OR (registration_state='BOUND' AND site_id IS NOT NULL AND floor_id IS NOT NULL
          AND (device_type='STATUS_BOARD' OR resource_id IS NOT NULL))
         OR registration_state IN ('SUSPENDED','RETIRED')),
    CONSTRAINT ck_wp_navigation_room_binding CHECK
        (device_type <> 'ROOM_PANEL' OR registration_state <> 'BOUND' OR resource_id IS NOT NULL),
    CONSTRAINT ck_wp_navigation_schedule_clock CHECK
        (schedule_source_at IS NULL OR schedule_received_at IS NULL
         OR schedule_source_at <= schedule_received_at)
);

CREATE INDEX idx_wp_navigation_devices_scope
    ON wp_navigation_devices(tenant_id, site_id, floor_id, registration_state);

CREATE TABLE wp_navigation_command_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    device_id UUID NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    expected_device_version BIGINT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    impact JSONB NOT NULL,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, preview_id),
    FOREIGN KEY (tenant_id, device_id)
        REFERENCES wp_navigation_devices(tenant_id, device_id),
    CONSTRAINT ck_wp_navigation_preview_command CHECK (command_type IN
        ('FORCE_SYNC','CLEAR_CACHE','REBOOT','SAFETY_TAKEOVER','CLEAR_SAFETY','UNBIND')),
    CONSTRAINT ck_wp_navigation_preview_json CHECK
        (jsonb_typeof(payload)='object' AND jsonb_typeof(impact)='array'
         AND jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_navigation_preview_version CHECK (expected_device_version > 0)
);

CREATE TABLE wp_navigation_device_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    device_id UUID NOT NULL,
    preview_id UUID NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',
    reason VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    provider_operation_reference VARCHAR(320),
    result_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, device_id)
        REFERENCES wp_navigation_devices(tenant_id, device_id),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_navigation_command_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_navigation_command_state CHECK
        (command_state IN ('ACCEPTED','RUNNING','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_navigation_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_navigation_safety_frames (
    safety_frame_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    device_id UUID NOT NULL,
    source_command_id UUID NOT NULL,
    frame_state VARCHAR(20) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    direction VARCHAR(500) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    issued_by_actor_id BIGINT NOT NULL,
    offline_fallback BOOLEAN NOT NULL,
    cleared_at TIMESTAMPTZ,
    cleared_by BIGINT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, safety_frame_id),
    FOREIGN KEY (tenant_id, device_id)
        REFERENCES wp_navigation_devices(tenant_id, device_id),
    FOREIGN KEY (tenant_id, source_command_id)
        REFERENCES wp_navigation_device_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_navigation_safety_state CHECK
        (frame_state IN ('ACTIVE','CLEARED')),
    CONSTRAINT ck_wp_navigation_safety_clear CHECK
        ((frame_state='ACTIVE' AND cleared_at IS NULL AND cleared_by IS NULL)
         OR (frame_state='CLEARED' AND cleared_at IS NOT NULL AND cleared_by IS NOT NULL))
);

CREATE UNIQUE INDEX uk_wp_navigation_active_safety_frame
    ON wp_navigation_safety_frames(tenant_id, device_id) WHERE frame_state='ACTIVE';

CREATE TABLE wp_navigation_device_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_id UUID NOT NULL,
    device_id UUID NOT NULL,
    deduplication_key VARCHAR(200) NOT NULL,
    delivery_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, deduplication_key),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_navigation_device_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, device_id)
        REFERENCES wp_navigation_devices(tenant_id, device_id),
    CONSTRAINT ck_wp_navigation_outbox_state CHECK (delivery_state IN
        ('PENDING','PROCESSING','DELIVERED','RETRY','DEAD_LETTER','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_navigation_outbox_attempt CHECK (attempt_count >= 0)
);

CREATE TABLE wp_navigation_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    correlation_id VARCHAR(160),
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_wp_navigation_audit_snapshot CHECK (jsonb_typeof(snapshot)='object')
);

CREATE INDEX idx_wp_navigation_audit_resource
    ON wp_navigation_audit_events(tenant_id, resource_type, resource_id, occurred_at DESC);

COMMENT ON COLUMN wp_navigation_devices.device_identity_sha256 IS
    'One-way device identity fingerprint; reusable credentials, tokens and PINs are prohibited.';
COMMENT ON TABLE wp_navigation_device_commands IS
    'Durable receipt for previewed commands; RESULT_UNKNOWN is recovered by GET and reconciliation.';
