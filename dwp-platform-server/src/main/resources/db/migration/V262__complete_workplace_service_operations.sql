CREATE TABLE wp_service_provider_profiles (
    provider_profile_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    display_name_ko VARCHAR(160) NOT NULL,
    display_name_en VARCHAR(160) NOT NULL,
    adapter_type VARCHAR(80) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    site_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    support_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    credential_binding_reference VARCHAR(320),
    configuration_version BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, provider_profile_id),
    UNIQUE (tenant_id, provider_code),
    FOREIGN KEY (tenant_id, provider_code)
        REFERENCES wp_service_provider_truth(tenant_id, provider_code),
    CONSTRAINT ck_wp_service_provider_profile_code CHECK
        (provider_code ~ '^[A-Za-z0-9._-]{1,80}$'),
    CONSTRAINT ck_wp_service_provider_profile_state CHECK
        (lifecycle_state IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_wp_service_provider_profile_json CHECK
        (jsonb_typeof(site_scope) = 'array'
         AND jsonb_typeof(capabilities) = 'array'
         AND jsonb_typeof(support_metadata) = 'object'),
    CONSTRAINT ck_wp_service_provider_profile_versions CHECK
        (configuration_version > 0 AND version > 0)
);

INSERT INTO wp_service_provider_profiles (
    provider_profile_id, tenant_id, provider_code, display_name_ko, display_name_en,
    adapter_type, lifecycle_state, site_scope, capabilities,
    support_metadata, credential_binding_reference, configuration_version)
SELECT gen_random_uuid(), tenant_id, provider_code, provider_code, provider_code,
       CASE WHEN provider_code LIKE 'DWP_NATIVE_%' THEN 'DWP_NATIVE' ELSE 'LEGACY' END,
       CASE WHEN configured THEN 'ACTIVE' ELSE 'DRAFT' END,
       '[]'::jsonb,
       CASE WHEN provider_code LIKE 'DWP_NATIVE_%'
            THEN '["WORKPLACE_SERVICE_FULFILLMENT"]'::jsonb ELSE '[]'::jsonb END,
       '{}'::jsonb,
       CASE WHEN provider_code LIKE 'DWP_NATIVE_%'
            THEN 'internal://workplace-service-native' ELSE NULL END,
       GREATEST(configuration_version, 1)
  FROM wp_service_provider_truth;

CREATE TABLE wp_service_provider_verifications (
    provider_verification_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    provider_profile_id UUID NOT NULL,
    configuration_version BIGINT NOT NULL,
    reported_state VARCHAR(24) NOT NULL,
    evidence_reference VARCHAR(320) NOT NULL,
    capability_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(120),
    verified_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, provider_verification_id),
    FOREIGN KEY (tenant_id, provider_profile_id)
        REFERENCES wp_service_provider_profiles(tenant_id, provider_profile_id),
    CONSTRAINT ck_wp_service_provider_verification_state CHECK
        (reported_state IN ('HEALTHY', 'DEGRADED', 'UNAVAILABLE')),
    CONSTRAINT ck_wp_service_provider_verification_json CHECK
        (jsonb_typeof(capability_evidence) = 'array'),
    CONSTRAINT ck_wp_service_provider_verification_version CHECK
        (configuration_version > 0)
);

CREATE INDEX idx_wp_service_provider_verifications_latest
    ON wp_service_provider_verifications(
        tenant_id, provider_profile_id, received_at DESC, provider_verification_id DESC);

ALTER TABLE wp_service_catalog_items
    ADD COLUMN capacity_mode VARCHAR(20) NOT NULL DEFAULT 'UNBOUNDED',
    ADD COLUMN capacity_freshness_seconds INTEGER NOT NULL DEFAULT 900,
    ADD COLUMN inspection_mode VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN inspection_checklist_schema JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_wp_service_catalog_capacity_mode CHECK
        (capacity_mode IN ('UNBOUNDED', 'BUCKETED')),
    ADD CONSTRAINT ck_wp_service_catalog_capacity_freshness CHECK
        (capacity_freshness_seconds BETWEEN 30 AND 86400),
    ADD CONSTRAINT ck_wp_service_catalog_inspection_mode CHECK
        (inspection_mode IN ('NONE', 'OPERATOR', 'REQUESTER')),
    ADD CONSTRAINT ck_wp_service_catalog_inspection_schema CHECK
        (jsonb_typeof(inspection_checklist_schema) = 'array');

ALTER TABLE wp_service_order_lines
    ADD COLUMN option_schema_snapshot JSONB,
    ADD COLUMN site_scope_snapshot JSONB,
    ADD COLUMN supported_resource_types_snapshot JSONB,
    ADD COLUMN minimum_quantity INTEGER,
    ADD COLUMN maximum_quantity INTEGER,
    ADD COLUMN order_cutoff_minutes INTEGER,
    ADD COLUMN inspection_mode VARCHAR(24),
    ADD COLUMN inspection_checklist_schema JSONB;

UPDATE wp_service_order_lines line
   SET option_schema_snapshot = item.option_schema,
       site_scope_snapshot = item.site_scope,
       supported_resource_types_snapshot = item.supported_resource_types,
       minimum_quantity = item.minimum_quantity,
       maximum_quantity = item.maximum_quantity,
       order_cutoff_minutes = item.order_cutoff_minutes,
       inspection_mode = item.inspection_mode,
       inspection_checklist_schema = item.inspection_checklist_schema
  FROM wp_service_catalog_items item
 WHERE item.tenant_id = line.tenant_id
   AND item.catalog_item_id = line.catalog_item_id;

ALTER TABLE wp_service_order_lines
    ALTER COLUMN option_schema_snapshot SET NOT NULL,
    ALTER COLUMN site_scope_snapshot SET NOT NULL,
    ALTER COLUMN supported_resource_types_snapshot SET NOT NULL,
    ALTER COLUMN minimum_quantity SET NOT NULL,
    ALTER COLUMN maximum_quantity SET NOT NULL,
    ALTER COLUMN order_cutoff_minutes SET NOT NULL,
    ALTER COLUMN inspection_mode SET NOT NULL,
    ALTER COLUMN inspection_checklist_schema SET NOT NULL,
    ADD CONSTRAINT ck_wp_service_line_operation_snapshot CHECK
        (jsonb_typeof(option_schema_snapshot) = 'array'
         AND jsonb_typeof(site_scope_snapshot) = 'array'
         AND jsonb_typeof(supported_resource_types_snapshot) = 'array'
         AND minimum_quantity BETWEEN 1 AND 1000
         AND maximum_quantity BETWEEN minimum_quantity AND 1000
         AND order_cutoff_minutes BETWEEN 0 AND 525600
         AND inspection_mode IN ('NONE', 'OPERATOR', 'REQUESTER')
         AND jsonb_typeof(inspection_checklist_schema) = 'array');

CREATE TABLE wp_service_capacity_buckets (
    capacity_bucket_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    catalog_item_id UUID NOT NULL,
    site_reference VARCHAR(160) NOT NULL,
    bucket_starts_at TIMESTAMPTZ NOT NULL,
    bucket_ends_at TIMESTAMPTZ NOT NULL,
    capacity_limit INTEGER NOT NULL,
    committed_quantity INTEGER NOT NULL DEFAULT 0,
    source_version VARCHAR(160) NOT NULL,
    source_observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, capacity_bucket_id),
    UNIQUE (tenant_id, catalog_item_id, site_reference,
        bucket_starts_at, bucket_ends_at),
    FOREIGN KEY (tenant_id, catalog_item_id)
        REFERENCES wp_service_catalog_items(tenant_id, catalog_item_id),
    CONSTRAINT ck_wp_service_capacity_bucket_period CHECK
        (bucket_ends_at > bucket_starts_at),
    CONSTRAINT ck_wp_service_capacity_bucket_values CHECK
        (capacity_limit >= 0 AND committed_quantity >= 0
         AND committed_quantity <= capacity_limit AND version > 0),
    CONSTRAINT ck_wp_service_capacity_bucket_source CHECK
        (length(trim(source_version)) BETWEEN 1 AND 160)
);

CREATE INDEX idx_wp_service_capacity_bucket_range
    ON wp_service_capacity_buckets(
        tenant_id, catalog_item_id, site_reference, bucket_starts_at, bucket_ends_at);

CREATE TABLE wp_service_capacity_holds (
    capacity_hold_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    preview_id UUID NOT NULL,
    catalog_item_id UUID NOT NULL,
    capacity_bucket_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    hold_state VARCHAR(20) NOT NULL DEFAULT 'HELD',
    bucket_version BIGINT NOT NULL,
    service_order_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    committed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, capacity_hold_id),
    UNIQUE (tenant_id, preview_id, capacity_bucket_id),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_service_order_previews(tenant_id, preview_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, catalog_item_id)
        REFERENCES wp_service_catalog_items(tenant_id, catalog_item_id),
    FOREIGN KEY (tenant_id, capacity_bucket_id)
        REFERENCES wp_service_capacity_buckets(tenant_id, capacity_bucket_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id),
    CONSTRAINT ck_wp_service_capacity_hold_state CHECK
        (hold_state IN ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_wp_service_capacity_hold_values CHECK
        (quantity > 0 AND bucket_version > 0 AND expires_at > created_at),
    CONSTRAINT ck_wp_service_capacity_hold_terminal CHECK
        ((hold_state = 'COMMITTED' AND service_order_id IS NOT NULL AND committed_at IS NOT NULL)
         OR (hold_state <> 'COMMITTED' AND committed_at IS NULL))
);

CREATE INDEX idx_wp_service_capacity_holds_active
    ON wp_service_capacity_holds(tenant_id, capacity_bucket_id, expires_at)
    WHERE hold_state = 'HELD';

CREATE TABLE wp_service_assignee_directory_entries (
    tenant_id BIGINT NOT NULL,
    directory_subject_id VARCHAR(160) NOT NULL,
    public_display_name VARCHAR(160) NOT NULL,
    provider_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    site_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    contact_available BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    directory_version VARCHAR(160) NOT NULL,
    source_observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    fresh_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, directory_subject_id),
    CONSTRAINT ck_wp_service_assignee_directory_json CHECK
        (jsonb_typeof(provider_codes) = 'array'
         AND jsonb_typeof(site_scope) = 'array'
         AND jsonb_typeof(capabilities) = 'array'),
    CONSTRAINT ck_wp_service_assignee_directory_freshness CHECK
        (fresh_until >= source_observed_at AND fresh_until >= received_at)
);

ALTER TABLE wp_service_fulfillment_tasks
    ADD COLUMN assignee_directory_subject_id VARCHAR(160),
    ADD COLUMN assignee_display_name VARCHAR(160),
    ADD COLUMN assignee_directory_version VARCHAR(160),
    ADD COLUMN assignee_verified_at TIMESTAMPTZ,
    ADD COLUMN assignee_capabilities_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT fk_wp_service_task_assignee_directory
        FOREIGN KEY (tenant_id, assignee_directory_subject_id)
        REFERENCES wp_service_assignee_directory_entries(tenant_id, directory_subject_id),
    ADD CONSTRAINT ck_wp_service_task_assignee_snapshot CHECK
        (jsonb_typeof(assignee_capabilities_snapshot) = 'array'
         AND ((assignee_directory_subject_id IS NULL
               AND assignee_display_name IS NULL
               AND assignee_directory_version IS NULL
               AND assignee_verified_at IS NULL)
              OR (assignee_directory_subject_id IS NOT NULL
               AND assignee_display_name IS NOT NULL
               AND assignee_directory_version IS NOT NULL
               AND assignee_verified_at IS NOT NULL)));

CREATE TABLE wp_service_inspection_attempts (
    inspection_attempt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    service_order_line_id UUID NOT NULL,
    fulfillment_task_id UUID NOT NULL,
    inspection_mode VARCHAR(24) NOT NULL,
    inspector_role VARCHAR(24) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    checklist_schema_snapshot JSONB NOT NULL,
    checklist_responses JSONB NOT NULL,
    evidence_attachment_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason VARCHAR(500) NOT NULL,
    remediation_required BOOLEAN NOT NULL,
    actor_user_id BIGINT NOT NULL,
    order_version BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, inspection_attempt_id),
    FOREIGN KEY (tenant_id, service_order_id, service_order_line_id)
        REFERENCES wp_service_order_lines(
            tenant_id, service_order_id, service_order_line_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, fulfillment_task_id)
        REFERENCES wp_service_fulfillment_tasks(tenant_id, fulfillment_task_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_service_inspection_mode CHECK
        (inspection_mode IN ('OPERATOR', 'REQUESTER')),
    CONSTRAINT ck_wp_service_inspection_role CHECK
        (inspector_role IN ('OPERATOR', 'REQUESTER')),
    CONSTRAINT ck_wp_service_inspection_decision CHECK
        (decision IN ('PASSED', 'FAILED')),
    CONSTRAINT ck_wp_service_inspection_json CHECK
        (jsonb_typeof(checklist_schema_snapshot) = 'array'
         AND jsonb_typeof(checklist_responses) = 'object'
         AND jsonb_typeof(evidence_attachment_ids) = 'array'),
    CONSTRAINT ck_wp_service_inspection_versions CHECK
        (order_version > 0 AND task_version > 0)
);

CREATE INDEX idx_wp_service_inspection_latest
    ON wp_service_inspection_attempts(
        tenant_id, service_order_line_id, created_at DESC, inspection_attempt_id DESC);

CREATE TABLE wp_service_ephemeral_access_grants (
    access_grant_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    service_order_line_id UUID NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    requester_user_id BIGINT NOT NULL,
    provider_grant_reference VARCHAR(320) NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL,
    grant_state VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, access_grant_id),
    UNIQUE (tenant_id, provider_grant_reference),
    FOREIGN KEY (tenant_id, service_order_id, service_order_line_id)
        REFERENCES wp_service_order_lines(
            tenant_id, service_order_id, service_order_line_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, provider_code)
        REFERENCES wp_service_provider_truth(tenant_id, provider_code),
    CONSTRAINT ck_wp_service_access_grant_state CHECK
        (grant_state IN ('ISSUED', 'REVOKED', 'EXPIRED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_access_grant_fingerprint CHECK
        (credential_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_service_access_grant_period CHECK
        (expires_at > issued_at),
    CONSTRAINT ck_wp_service_access_grant_revoke CHECK
        ((grant_state = 'REVOKED' AND revoked_at IS NOT NULL)
         OR (grant_state <> 'REVOKED' AND revoked_at IS NULL))
);

CREATE UNIQUE INDEX uq_wp_service_active_access_grant
    ON wp_service_ephemeral_access_grants(
        tenant_id, service_order_id, service_order_line_id, requester_user_id)
    WHERE grant_state IN ('ISSUED', 'RESULT_UNKNOWN');

CREATE TABLE wp_service_contact_requests (
    contact_request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    service_order_line_id UUID,
    requested_by BIGINT NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    resolved_target_reference VARCHAR(320) NOT NULL,
    resolved_target_display_name VARCHAR(160) NOT NULL,
    message_id UUID NOT NULL,
    contact_state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, contact_request_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, message_id)
        REFERENCES wp_service_order_messages(tenant_id, message_id),
    CONSTRAINT ck_wp_service_contact_target CHECK
        (target_type IN ('ASSIGNEE', 'SERVICE_DESK')),
    CONSTRAINT ck_wp_service_contact_state CHECK
        (contact_state IN ('QUEUED', 'DELIVERED', 'FAILED', 'RESULT_UNKNOWN'))
);

CREATE TABLE wp_service_operations_commands (
    operations_command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_scope VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    status_href VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, actor_user_id, command_scope, idempotency_key),
    CONSTRAINT ck_wp_service_operations_command_state CHECK
        (command_state IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_operations_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

ALTER TABLE wp_service_order_events
    DROP CONSTRAINT ck_wp_service_event_type,
    ADD CONSTRAINT ck_wp_service_event_type CHECK
        (event_type IN ('SUBMITTED', 'ACCEPTED', 'PREPARATION_STARTED',
         'PARTIALLY_FULFILLED', 'FULFILLED', 'BLOCKED', 'DELAYED',
         'CANCELLED', 'RESULT_UNKNOWN', 'RESERVATION_IMPACT_CHANGED',
         'MESSAGE_ADDED', 'ATTACHMENT_LINKED', 'ATTACHMENT_SCAN_UPDATED',
         'LINE_CANCELLED', 'LINE_CANCELLATION_FAILED',
         'LINE_CANCELLATION_REQUIRES_REVIEW',
         'LINE_CANCELLATION_RESULT_UNKNOWN', 'LINE_ADJUSTMENT_RECONCILED',
         'ASSIGNEE_ASSIGNED', 'INSPECTION_PASSED', 'INSPECTION_FAILED',
         'ACCESS_CREDENTIAL_ISSUED', 'ACCESS_CREDENTIAL_REVOKED',
         'CONTACT_REQUESTED'));

COMMENT ON COLUMN wp_service_provider_profiles.credential_binding_reference IS
    'Opaque vault or connector binding identifier. Credential material must never be stored here.';
COMMENT ON COLUMN wp_service_ephemeral_access_grants.credential_fingerprint IS
    'One-way fingerprint for incident correlation. The one-time credential is never persisted.';
COMMENT ON TABLE wp_service_capacity_holds IS
    'TTL capacity reservations created by preview and atomically committed by order submission.';
COMMENT ON TABLE wp_service_inspection_attempts IS
    'Immutable operator/requester inspection evidence used as the fulfillment completion fence.';
