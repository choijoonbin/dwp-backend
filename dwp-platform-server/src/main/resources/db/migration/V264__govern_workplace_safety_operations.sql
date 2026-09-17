CREATE TABLE wp_safety_connector_truth (
    connector_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    connector_kind VARCHAR(24) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    configured BOOLEAN NOT NULL DEFAULT TRUE,
    configuration_version BIGINT NOT NULL,
    observed_configuration_version BIGINT,
    reported_state VARCHAR(24),
    evidence_reference VARCHAR(320),
    source_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    error_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, connector_kind),
    UNIQUE (tenant_id, connector_id),
    CONSTRAINT ck_wp_safety_connector_kind CHECK (connector_kind IN
        ('EMERGENCY_119','EBS','BLE_MESH','WORM','GOVERNMENT_LOG')),
    CONSTRAINT ck_wp_safety_connector_state CHECK
        (reported_state IS NULL OR reported_state IN ('READY','DEGRADED','UNAVAILABLE')),
    CONSTRAINT ck_wp_safety_connector_versions CHECK
        (configuration_version > 0 AND version > 0
         AND (observed_configuration_version IS NULL OR observed_configuration_version > 0)),
    CONSTRAINT ck_wp_safety_connector_evidence CHECK
        ((reported_state IS NULL AND observed_configuration_version IS NULL
          AND evidence_reference IS NULL AND source_at IS NULL AND received_at IS NULL)
         OR (reported_state IS NOT NULL AND observed_configuration_version IS NOT NULL
          AND evidence_reference IS NOT NULL AND source_at IS NOT NULL AND received_at IS NOT NULL))
);

CREATE TABLE wp_safety_presence_observations (
    observation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    subject_key_sha256 CHAR(64) NOT NULL,
    subject_user_id BIGINT,
    masked_label VARCHAR(160) NOT NULL,
    site_id UUID NOT NULL,
    floor_id UUID,
    zone_id UUID,
    presence_state VARCHAR(20) NOT NULL,
    source_reference VARCHAR(320) NOT NULL,
    evidence_reference VARCHAR(320) NOT NULL,
    source_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    sequence BIGINT NOT NULL,
    UNIQUE (tenant_id, observation_id),
    UNIQUE (tenant_id, source_reference, sequence),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, floor_id) REFERENCES wp_floors(tenant_id, floor_id),
    FOREIGN KEY (tenant_id, zone_id) REFERENCES wp_zones(tenant_id, zone_id),
    CONSTRAINT ck_wp_safety_presence_subject CHECK
        (subject_key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_safety_presence_state CHECK
        (presence_state IN ('PRESENT','DEPARTED','UNKNOWN')),
    CONSTRAINT ck_wp_safety_presence_clock CHECK (source_at <= received_at),
    CONSTRAINT ck_wp_safety_presence_sequence CHECK (sequence > 0)
);

CREATE INDEX idx_wp_safety_presence_latest
    ON wp_safety_presence_observations
    (tenant_id, site_id, floor_id, zone_id, subject_key_sha256, source_at DESC);

CREATE TABLE wp_safety_audience_snapshots (
    audience_snapshot_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    owner_type VARCHAR(32) NOT NULL,
    owner_id UUID NOT NULL,
    total_candidates INTEGER NOT NULL,
    deduplicated_count INTEGER NOT NULL,
    excluded_count INTEGER NOT NULL,
    unknown_count INTEGER NOT NULL,
    final_target_count INTEGER NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, audience_snapshot_id),
    UNIQUE (tenant_id, owner_type, owner_id),
    CONSTRAINT ck_wp_safety_snapshot_owner CHECK
        (owner_type IN ('ACTIVATION_PREVIEW','INCIDENT','SCOPE_REVISION')),
    CONSTRAINT ck_wp_safety_snapshot_counts CHECK
        (total_candidates >= 0 AND deduplicated_count >= 0 AND excluded_count >= 0
         AND unknown_count >= 0 AND final_target_count >= 0
         AND final_target_count <= deduplicated_count)
);

CREATE TABLE wp_safety_audience_sources (
    source_summary_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    audience_snapshot_id UUID NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    candidate_count INTEGER NOT NULL,
    included_count INTEGER NOT NULL,
    excluded_count INTEGER NOT NULL,
    unknown_count INTEGER NOT NULL,
    coverage_percent NUMERIC(5,2),
    freshness_state VARCHAR(20) NOT NULL,
    availability_state VARCHAR(24) NOT NULL,
    source_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    UNIQUE (tenant_id, audience_snapshot_id, source_kind),
    FOREIGN KEY (tenant_id, audience_snapshot_id)
        REFERENCES wp_safety_audience_snapshots(tenant_id, audience_snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_wp_safety_source_kind CHECK (source_kind IN
        ('RESERVATION','ACTUAL_PRESENCE','VISITOR','SCHEDULED_VISITOR')),
    CONSTRAINT ck_wp_safety_source_freshness CHECK
        (freshness_state IN ('FRESH','STALE','UNKNOWN')),
    CONSTRAINT ck_wp_safety_source_availability CHECK
        (availability_state IN ('AVAILABLE','PARTIAL','UNAVAILABLE')),
    CONSTRAINT ck_wp_safety_source_counts CHECK
        (candidate_count >= 0 AND included_count >= 0 AND excluded_count >= 0
         AND unknown_count >= 0
         AND (coverage_percent IS NULL OR coverage_percent BETWEEN 0 AND 100))
);

CREATE TABLE wp_safety_audience_members (
    audience_member_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    audience_snapshot_id UUID NOT NULL,
    subject_key_sha256 CHAR(64) NOT NULL,
    subject_user_id BIGINT,
    masked_label VARCHAR(160) NOT NULL,
    source_kinds JSONB NOT NULL,
    included BOOLEAN NOT NULL,
    exclusion_code VARCHAR(120),
    unknown_identity BOOLEAN NOT NULL,
    UNIQUE (tenant_id, audience_snapshot_id, subject_key_sha256),
    UNIQUE (tenant_id, audience_member_id),
    FOREIGN KEY (tenant_id, audience_snapshot_id)
        REFERENCES wp_safety_audience_snapshots(tenant_id, audience_snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_wp_safety_member_subject CHECK
        (subject_key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_safety_member_sources CHECK (jsonb_typeof(source_kinds)='array'),
    CONSTRAINT ck_wp_safety_member_exclusion CHECK
        ((included AND exclusion_code IS NULL) OR (NOT included AND exclusion_code IS NOT NULL))
);

CREATE TABLE wp_safety_activation_previews (
    activation_preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    incident_type VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    site_id UUID NOT NULL,
    floor_ids JSONB NOT NULL,
    zone_ids JSONB NOT NULL,
    message VARCHAR(1000) NOT NULL,
    safety_action VARCHAR(1000) NOT NULL,
    assembly_point VARCHAR(500),
    channels JSONB NOT NULL,
    excluded_subject_keys JSONB NOT NULL,
    audience_snapshot_id UUID NOT NULL,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, activation_preview_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, audience_snapshot_id)
        REFERENCES wp_safety_audience_snapshots(tenant_id, audience_snapshot_id),
    CONSTRAINT ck_wp_safety_preview_severity CHECK
        (severity IN ('ADVISORY','URGENT','CRITICAL')),
    CONSTRAINT ck_wp_safety_preview_json CHECK
        (jsonb_typeof(floor_ids)='array' AND jsonb_typeof(zone_ids)='array'
         AND jsonb_typeof(channels)='array'
         AND jsonb_typeof(excluded_subject_keys)='array'
         AND jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_safety_preview_expiry CHECK (expires_at > created_at)
);

CREATE TABLE wp_safety_incidents (
    incident_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    activation_preview_id UUID NOT NULL,
    incident_number VARCHAR(80) NOT NULL,
    incident_type VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    incident_state VARCHAR(24) NOT NULL,
    site_id UUID NOT NULL,
    floor_ids JSONB NOT NULL,
    zone_ids JSONB NOT NULL,
    message VARCHAR(1000) NOT NULL,
    safety_action VARCHAR(1000) NOT NULL,
    assembly_point VARCHAR(500),
    channels JSONB NOT NULL,
    audience_snapshot_id UUID NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    activated_by BIGINT NOT NULL,
    closed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, incident_id),
    UNIQUE (tenant_id, incident_number),
    FOREIGN KEY (tenant_id, activation_preview_id)
        REFERENCES wp_safety_activation_previews(tenant_id, activation_preview_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, audience_snapshot_id)
        REFERENCES wp_safety_audience_snapshots(tenant_id, audience_snapshot_id),
    CONSTRAINT ck_wp_safety_incident_state CHECK
        (incident_state IN ('ACTIVE','CLOSURE_PENDING','CLOSED','CANCELLED')),
    CONSTRAINT ck_wp_safety_incident_severity CHECK
        (severity IN ('ADVISORY','URGENT','CRITICAL')),
    CONSTRAINT ck_wp_safety_incident_json CHECK
        (jsonb_typeof(floor_ids)='array' AND jsonb_typeof(zone_ids)='array'
         AND jsonb_typeof(channels)='array'),
    CONSTRAINT ck_wp_safety_incident_version CHECK (version > 0),
    CONSTRAINT ck_wp_safety_incident_closed CHECK
        ((incident_state='CLOSED' AND closed_at IS NOT NULL)
         OR (incident_state<>'CLOSED' AND closed_at IS NULL))
);

CREATE TABLE wp_safety_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    incident_id UUID,
    command_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    status_href VARCHAR(500) NOT NULL,
    result_code VARCHAR(120),
    provider_operation_reference VARCHAR(320),
    version BIGINT NOT NULL DEFAULT 1,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, command_type, idempotency_key),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    CONSTRAINT ck_wp_safety_command_type CHECK (command_type IN
        ('PREVIEW_ACTIVATION','PREVIEW_SCOPE','PREVIEW_CLOSURE',
         'ACTIVATE','REVISE_SCOPE','RESEND','SEND_MESSAGE','RESPOND','CONFIRM_ASSEMBLY',
         'REQUEST_CLOSURE','APPROVE_CLOSURE','CREATE_EXPORT','CONFIGURE_CONNECTOR')),
    CONSTRAINT ck_wp_safety_command_state CHECK
        (command_state IN ('ACCEPTED','RUNNING','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_safety_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

ALTER TABLE wp_safety_activation_previews
    ADD CONSTRAINT fk_wp_safety_activation_preview_command
    FOREIGN KEY (tenant_id, command_id)
    REFERENCES wp_safety_commands(tenant_id, command_id);

CREATE TABLE wp_safety_scope_revisions (
    scope_revision_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    command_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    revision_state VARCHAR(16) NOT NULL,
    previous_floor_ids JSONB NOT NULL,
    previous_zone_ids JSONB NOT NULL,
    proposed_floor_ids JSONB NOT NULL,
    proposed_zone_ids JSONB NOT NULL,
    proposed_message VARCHAR(1000) NOT NULL,
    audience_snapshot_id UUID NOT NULL,
    incident_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, scope_revision_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, audience_snapshot_id)
        REFERENCES wp_safety_audience_snapshots(tenant_id, audience_snapshot_id),
    CONSTRAINT ck_wp_safety_scope_state CHECK
        (revision_state IN ('PREVIEW','APPLIED','EXPIRED')),
    CONSTRAINT ck_wp_safety_scope_json CHECK
        (jsonb_typeof(previous_floor_ids)='array'
         AND jsonb_typeof(previous_zone_ids)='array'
         AND jsonb_typeof(proposed_floor_ids)='array'
         AND jsonb_typeof(proposed_zone_ids)='array'),
    CONSTRAINT ck_wp_safety_scope_applied CHECK
        ((revision_state='APPLIED' AND applied_at IS NOT NULL)
         OR (revision_state<>'APPLIED' AND applied_at IS NULL))
);

CREATE TABLE wp_safety_dispatch_batches (
    dispatch_batch_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    command_id UUID NOT NULL,
    audience_snapshot_id UUID NOT NULL,
    batch_kind VARCHAR(24) NOT NULL,
    dispatch_state VARCHAR(24) NOT NULL,
    channels JSONB NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    delivered_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    unknown_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, dispatch_batch_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, audience_snapshot_id)
        REFERENCES wp_safety_audience_snapshots(tenant_id, audience_snapshot_id),
    CONSTRAINT ck_wp_safety_batch_kind CHECK
        (batch_kind IN ('ACTIVATION','SCOPE_REVISION','RESEND','MESSAGE')),
    CONSTRAINT ck_wp_safety_dispatch_state CHECK
        (dispatch_state IN ('ACCEPTED','DISPATCHING','PARTIAL','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_safety_batch_channels CHECK (jsonb_typeof(channels)='array'),
    CONSTRAINT ck_wp_safety_batch_counts CHECK
        (attempt_count >= 0 AND delivered_count >= 0 AND failed_count >= 0 AND unknown_count >= 0)
);

CREATE TABLE wp_safety_dispatch_attempts (
    dispatch_attempt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    dispatch_batch_id UUID NOT NULL,
    audience_member_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    attempt_state VARCHAR(24) NOT NULL,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    provider_operation_reference VARCHAR(320),
    result_code VARCHAR(120),
    offline_retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    reconcile_attempt_count INTEGER NOT NULL DEFAULT 0,
    next_reconcile_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, dispatch_batch_id, audience_member_id, channel, attempt_number),
    UNIQUE (tenant_id, dispatch_attempt_id),
    FOREIGN KEY (tenant_id, dispatch_batch_id)
        REFERENCES wp_safety_dispatch_batches(tenant_id, dispatch_batch_id),
    FOREIGN KEY (tenant_id, audience_member_id)
        REFERENCES wp_safety_audience_members(tenant_id, audience_member_id),
    CONSTRAINT ck_wp_safety_attempt_channel CHECK
        (channel IN ('APP_PUSH','SMS','EMAIL','EBS','BLE_MESH')),
    CONSTRAINT ck_wp_safety_attempt_state CHECK (attempt_state IN
        ('QUEUED','DISPATCHING','DELIVERED','DELIVERY_FAILED','OFFLINE_QUEUED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_safety_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_wp_safety_attempt_retries CHECK
        (offline_retry_count BETWEEN 0 AND 3 AND reconcile_attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_wp_safety_attempt_recovery CHECK
        ((attempt_state='OFFLINE_QUEUED' AND next_attempt_at IS NOT NULL)
         OR attempt_state<>'OFFLINE_QUEUED')
);

CREATE TABLE wp_safety_dispatch_receipts (
    dispatch_receipt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    dispatch_attempt_id UUID NOT NULL,
    receipt_state VARCHAR(24) NOT NULL,
    evidence_reference VARCHAR(320),
    source_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, dispatch_attempt_id),
    FOREIGN KEY (tenant_id, dispatch_attempt_id)
        REFERENCES wp_safety_dispatch_attempts(tenant_id, dispatch_attempt_id),
    CONSTRAINT ck_wp_safety_receipt_state CHECK
        (receipt_state IN ('DELIVERED','DELIVERY_FAILED','RESULT_UNKNOWN'))
);

CREATE TABLE wp_safety_responses (
    safety_response_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    subject_user_id BIGINT NOT NULL,
    response_state VARCHAR(20) NOT NULL,
    assistance_note VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 1,
    responded_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, incident_id, subject_user_id),
    UNIQUE (tenant_id, safety_response_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    CONSTRAINT ck_wp_safety_response_state CHECK
        (response_state IN ('SAFE','NEEDS_HELP')),
    CONSTRAINT ck_wp_safety_response_note CHECK
        (response_state <> 'NEEDS_HELP' OR assistance_note IS NOT NULL)
);

CREATE TABLE wp_safety_assembly_confirmations (
    assembly_confirmation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    subject_key_sha256 CHAR(64) NOT NULL,
    subject_user_id BIGINT,
    confirmed BOOLEAN NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    confirmed_by BIGINT NOT NULL,
    evidence_reference VARCHAR(320) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, incident_id, subject_key_sha256),
    UNIQUE (tenant_id, assembly_confirmation_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    CONSTRAINT ck_wp_safety_assembly_subject CHECK
        (subject_key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_safety_assembly_version CHECK (version > 0)
);

CREATE TABLE wp_safety_messages (
    message_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    command_id UUID NOT NULL,
    sender_user_id BIGINT NOT NULL,
    target_user_id BIGINT,
    direction VARCHAR(24) NOT NULL,
    masked_body VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, message_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_safety_message_direction CHECK
        (direction IN ('USER_TO_COMMAND','COMMAND_TO_USER','COMMAND_BROADCAST'))
);

CREATE TABLE wp_safety_closure_previews (
    closure_preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    command_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    incident_version BIGINT NOT NULL,
    needs_help_count INTEGER NOT NULL,
    no_response_count INTEGER NOT NULL,
    delivered_count INTEGER NOT NULL,
    failed_or_unknown_count INTEGER NOT NULL,
    eligible BOOLEAN NOT NULL,
    warnings JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, closure_preview_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_safety_closure_counts CHECK
        (needs_help_count >= 0 AND no_response_count >= 0 AND delivered_count >= 0
         AND failed_or_unknown_count >= 0),
    CONSTRAINT ck_wp_safety_closure_warnings CHECK (jsonb_typeof(warnings)='array')
);

CREATE TABLE wp_safety_closure_requests (
    closure_request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    command_id UUID NOT NULL,
    closure_preview_id UUID NOT NULL,
    requested_by BIGINT NOT NULL,
    designated_approver_id BIGINT NOT NULL,
    closure_reason VARCHAR(1000) NOT NULL,
    follow_up_actions VARCHAR(2000) NOT NULL,
    request_state VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, closure_request_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, closure_preview_id)
        REFERENCES wp_safety_closure_previews(tenant_id, closure_preview_id),
    CONSTRAINT ck_wp_safety_closure_request_state CHECK
        (request_state IN ('PENDING_APPROVAL','APPROVED','REJECTED')),
    CONSTRAINT ck_wp_safety_closure_approver CHECK
        (requested_by <> designated_approver_id)
);

CREATE UNIQUE INDEX uk_wp_safety_pending_closure
    ON wp_safety_closure_requests(tenant_id, incident_id)
    WHERE request_state='PENDING_APPROVAL';

CREATE TABLE wp_safety_closure_approvals (
    closure_approval_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    closure_request_id UUID NOT NULL,
    approver_user_id BIGINT NOT NULL,
    approved BOOLEAN NOT NULL,
    approval_reason VARCHAR(1000) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, closure_request_id),
    FOREIGN KEY (tenant_id, closure_request_id)
        REFERENCES wp_safety_closure_requests(tenant_id, closure_request_id)
);

CREATE TABLE wp_safety_post_incident_reports (
    report_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    summary JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    generated_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, incident_id),
    UNIQUE (tenant_id, report_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    CONSTRAINT ck_wp_safety_report_summary CHECK (jsonb_typeof(summary)='object')
);

CREATE TABLE wp_safety_guarded_exports (
    guarded_export_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID NOT NULL,
    command_id UUID NOT NULL,
    requested_by BIGINT NOT NULL,
    export_format VARCHAR(8) NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    step_up_evidence VARCHAR(120) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, guarded_export_id),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_safety_export_format CHECK (export_format IN ('PDF','CSV')),
    CONSTRAINT ck_wp_safety_export_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_safety_export_expiry CHECK (expires_at > created_at)
);

CREATE TABLE wp_safety_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_id UUID NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    deduplication_key VARCHAR(200) NOT NULL,
    delivery_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, deduplication_key),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_safety_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_safety_outbox_operation CHECK (operation_type IN
        ('DISPATCH_BATCH','SEND_MESSAGE','WRITE_WORM','WRITE_GOVERNMENT_LOG')),
    CONSTRAINT ck_wp_safety_outbox_state CHECK (delivery_state IN
        ('PENDING','PROCESSING','DELIVERED','RETRY','DEAD_LETTER','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_safety_outbox_json CHECK (jsonb_typeof(payload)='object'),
    CONSTRAINT ck_wp_safety_outbox_attempt CHECK (attempt_count >= 0)
);

CREATE TABLE wp_safety_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    incident_id UUID,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    correlation_id VARCHAR(160),
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id, incident_id)
        REFERENCES wp_safety_incidents(tenant_id, incident_id),
    CONSTRAINT ck_wp_safety_audit_snapshot CHECK (jsonb_typeof(snapshot)='object')
);

CREATE INDEX idx_wp_safety_incidents_operations
    ON wp_safety_incidents(tenant_id, incident_state, severity, activated_at DESC);
CREATE INDEX idx_wp_safety_dispatch_recovery
    ON wp_safety_dispatch_attempts(
        tenant_id, attempt_state, next_attempt_at, next_reconcile_at, updated_at);
CREATE INDEX idx_wp_safety_audit_timeline
    ON wp_safety_audit_events(tenant_id, incident_id, occurred_at, audit_event_id);
