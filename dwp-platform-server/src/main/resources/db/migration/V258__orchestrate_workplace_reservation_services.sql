CREATE TABLE wp_service_provider_truth (
    tenant_id BIGINT NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    configured BOOLEAN NOT NULL DEFAULT FALSE,
    configuration_version BIGINT NOT NULL DEFAULT 0,
    observed_configuration_version BIGINT,
    reported_state VARCHAR(24),
    evidence_reference VARCHAR(320),
    observed_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    error_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, provider_code),
    CONSTRAINT ck_wp_service_provider_code CHECK
        (provider_code ~ '^[A-Za-z0-9._-]{1,80}$'),
    CONSTRAINT ck_wp_service_provider_state CHECK
        (reported_state IS NULL OR reported_state IN ('HEALTHY', 'DEGRADED', 'UNAVAILABLE')),
    CONSTRAINT ck_wp_service_provider_versions CHECK
        (configuration_version >= 0
         AND (observed_configuration_version IS NULL OR observed_configuration_version >= 0)
         AND version > 0),
    CONSTRAINT ck_wp_service_provider_evidence CHECK (
        (reported_state IS NULL AND evidence_reference IS NULL AND observed_at IS NULL AND received_at IS NULL)
        OR (reported_state IS NOT NULL AND evidence_reference IS NOT NULL
            AND observed_at IS NOT NULL AND received_at IS NOT NULL))
);

CREATE TABLE wp_service_catalog_items (
    catalog_item_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_code VARCHAR(80) NOT NULL,
    category VARCHAR(24) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    description_ko VARCHAR(1000),
    description_en VARCHAR(1000),
    provider_code VARCHAR(80) NOT NULL,
    site_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    option_schema JSONB NOT NULL DEFAULT '[]'::jsonb,
    supported_resource_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    unit_price NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'KRW',
    minimum_quantity INTEGER NOT NULL DEFAULT 1,
    maximum_quantity INTEGER NOT NULL DEFAULT 1000,
    order_cutoff_minutes INTEGER NOT NULL DEFAULT 60,
    cancellation_cutoff_minutes INTEGER NOT NULL DEFAULT 60,
    cancellation_policy_ko VARCHAR(1000) NOT NULL,
    cancellation_policy_en VARCHAR(1000) NOT NULL,
    requires_attendee_count BOOLEAN NOT NULL DEFAULT FALSE,
    requires_cost_center BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, service_code),
    UNIQUE (tenant_id, catalog_item_id),
    FOREIGN KEY (tenant_id, provider_code)
        REFERENCES wp_service_provider_truth(tenant_id, provider_code),
    CONSTRAINT ck_wp_service_catalog_category CHECK
        (category IN ('CATERING', 'AV', 'ROOM_LAYOUT', 'IT_SUPPORT', 'CLEANING')),
    CONSTRAINT ck_wp_service_catalog_json CHECK
        (jsonb_typeof(site_scope) = 'array'
         AND jsonb_typeof(option_schema) = 'array'
         AND jsonb_typeof(supported_resource_types) = 'array'),
    CONSTRAINT ck_wp_service_catalog_amount CHECK
        (unit_price >= 0 AND minimum_quantity > 0 AND maximum_quantity >= minimum_quantity),
    CONSTRAINT ck_wp_service_catalog_cutoff CHECK
        (order_cutoff_minutes >= 0 AND cancellation_cutoff_minutes >= 0),
    CONSTRAINT ck_wp_service_catalog_state CHECK
        (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE wp_service_catalog_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_scope VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    catalog_item_id UUID NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    status_href VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, actor_user_id, command_scope, idempotency_key),
    FOREIGN KEY (tenant_id, catalog_item_id)
        REFERENCES wp_service_catalog_items(tenant_id, catalog_item_id),
    CONSTRAINT ck_wp_service_catalog_command_state CHECK
        (command_state IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_catalog_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_service_order_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    reservation_authority VARCHAR(20) NOT NULL,
    reservation_id UUID NOT NULL,
    reservation_version BIGINT NOT NULL,
    reservation_starts_at TIMESTAMPTZ NOT NULL,
    reservation_ends_at TIMESTAMPTZ NOT NULL,
    site_reference VARCHAR(160),
    resource_reference VARCHAR(160),
    attendee_count INTEGER NOT NULL,
    cost_center VARCHAR(80),
    special_request VARCHAR(2000),
    estimated_cost NUMERIC(18,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    request_snapshot JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, preview_id),
    CONSTRAINT ck_wp_service_preview_authority CHECK
        (reservation_authority IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_service_preview_period CHECK
        (reservation_ends_at > reservation_starts_at AND expires_at > created_at),
    CONSTRAINT ck_wp_service_preview_values CHECK
        (reservation_version >= 0 AND attendee_count > 0 AND estimated_cost >= 0),
    CONSTRAINT ck_wp_service_preview_json CHECK
        (jsonb_typeof(limitations) = 'array' AND jsonb_typeof(request_snapshot) = 'object')
);

CREATE TABLE wp_service_orders (
    service_order_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    preview_id UUID NOT NULL,
    reservation_authority VARCHAR(20) NOT NULL,
    reservation_id UUID NOT NULL,
    reservation_version BIGINT NOT NULL,
    reservation_starts_at TIMESTAMPTZ NOT NULL,
    reservation_ends_at TIMESTAMPTZ NOT NULL,
    site_reference VARCHAR(160),
    resource_reference VARCHAR(160),
    attendee_count INTEGER NOT NULL,
    cost_center VARCHAR(80),
    estimated_cost NUMERIC(18,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    special_request VARCHAR(2000),
    order_state VARCHAR(32) NOT NULL,
    reservation_impact VARCHAR(32) NOT NULL DEFAULT 'NONE',
    reconfirmation_required BOOLEAN NOT NULL DEFAULT FALSE,
    provider_operation_reference VARCHAR(320),
    result_detail VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 1,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, service_order_id),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_service_order_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_service_order_authority CHECK
        (reservation_authority IN ('WORKPLACE', 'CALENDAR')),
    CONSTRAINT ck_wp_service_order_state CHECK
        (order_state IN ('SUBMITTED', 'ACCEPTED', 'IN_PREPARATION',
         'PARTIALLY_FULFILLED', 'FULFILLED', 'BLOCKED', 'DELAYED',
         'CANCELLED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_order_impact CHECK
        (reservation_impact IN ('NONE', 'RECONFIRMATION_REQUIRED', 'CANCELLATION_REVIEW')),
    CONSTRAINT ck_wp_service_order_period CHECK
        (reservation_ends_at > reservation_starts_at),
    CONSTRAINT ck_wp_service_order_values CHECK
        (reservation_version >= 0 AND attendee_count > 0 AND estimated_cost >= 0 AND version > 0),
    CONSTRAINT ck_wp_service_order_cancelled CHECK
        ((order_state = 'CANCELLED' AND cancelled_at IS NOT NULL)
         OR (order_state <> 'CANCELLED' AND cancelled_at IS NULL))
);

CREATE INDEX idx_wp_service_orders_requester
    ON wp_service_orders(tenant_id, requester_user_id, created_at DESC);
CREATE INDEX idx_wp_service_orders_fulfillment
    ON wp_service_orders(tenant_id, order_state, reservation_starts_at, created_at);

CREATE TABLE wp_service_order_lines (
    service_order_line_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    catalog_item_id UUID NOT NULL,
    service_code VARCHAR(80) NOT NULL,
    category VARCHAR(24) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    quantity INTEGER NOT NULL,
    options JSONB NOT NULL DEFAULT '{}'::jsonb,
    unit_price NUMERIC(18,2) NOT NULL,
    estimated_cost NUMERIC(18,2) NOT NULL,
    line_state VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    fulfilled_quantity INTEGER NOT NULL DEFAULT 0,
    blocker_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, service_order_line_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, catalog_item_id)
        REFERENCES wp_service_catalog_items(tenant_id, catalog_item_id),
    FOREIGN KEY (tenant_id, provider_code)
        REFERENCES wp_service_provider_truth(tenant_id, provider_code),
    CONSTRAINT ck_wp_service_line_category CHECK
        (category IN ('CATERING', 'AV', 'ROOM_LAYOUT', 'IT_SUPPORT', 'CLEANING')),
    CONSTRAINT ck_wp_service_line_state CHECK
        (line_state IN ('SUBMITTED', 'ACCEPTED', 'IN_PREPARATION',
         'PARTIALLY_FULFILLED', 'FULFILLED', 'BLOCKED', 'DELAYED',
         'CANCELLED', 'NOT_CONFIGURED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_line_quantity CHECK
        (quantity > 0 AND fulfilled_quantity BETWEEN 0 AND quantity
         AND unit_price >= 0 AND estimated_cost >= 0),
    CONSTRAINT ck_wp_service_line_options CHECK (jsonb_typeof(options) = 'object')
);

CREATE TABLE wp_service_fulfillment_tasks (
    fulfillment_task_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    service_order_line_id UUID NOT NULL,
    task_state VARCHAR(32) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    assignee_user_id BIGINT,
    due_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    external_fulfillment_reference VARCHAR(320),
    blocker_code VARCHAR(120),
    blocker_detail VARCHAR(1000),
    result_detail VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, fulfillment_task_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, service_order_line_id)
        REFERENCES wp_service_order_lines(tenant_id, service_order_line_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, provider_code)
        REFERENCES wp_service_provider_truth(tenant_id, provider_code),
    CONSTRAINT ck_wp_service_task_state CHECK
        (task_state IN ('SUBMITTED', 'ACCEPTED', 'IN_PREPARATION',
         'PARTIALLY_FULFILLED', 'FULFILLED', 'BLOCKED', 'DELAYED',
         'CANCELLED', 'NOT_CONFIGURED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_task_timestamps CHECK
        (completed_at IS NULL OR completed_at >= created_at)
);

CREATE INDEX idx_wp_service_tasks_queue
    ON wp_service_fulfillment_tasks(tenant_id, task_state, due_at, created_at);

CREATE TABLE wp_service_order_events (
    service_order_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, service_order_event_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_service_event_type CHECK
        (event_type IN ('SUBMITTED', 'ACCEPTED', 'PREPARATION_STARTED',
         'PARTIALLY_FULFILLED', 'FULFILLED', 'BLOCKED', 'DELAYED',
         'CANCELLED', 'RESULT_UNKNOWN', 'RESERVATION_IMPACT_CHANGED',
         'MESSAGE_ADDED', 'ATTACHMENT_LINKED')),
    CONSTRAINT ck_wp_service_event_detail CHECK (jsonb_typeof(detail) = 'object')
);

CREATE TABLE wp_service_order_messages (
    message_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    author_user_id BIGINT NOT NULL,
    author_display_name VARCHAR(160),
    author_role VARCHAR(20) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, message_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_service_message CHECK (length(trim(message)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_wp_service_message_author_role CHECK
        (author_role IN ('REQUESTER', 'OPERATOR'))
);

CREATE TABLE wp_service_order_attachments (
    attachment_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_order_id UUID NOT NULL,
    uploader_user_id BIGINT NOT NULL,
    storage_reference VARCHAR(500) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    byte_size BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, attachment_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id) ON DELETE CASCADE,
    CONSTRAINT ck_wp_service_attachment_size CHECK (byte_size BETWEEN 1 AND 26214400),
    CONSTRAINT ck_wp_service_attachment_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_service_order_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_scope VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    service_order_id UUID,
    command_state VARCHAR(24) NOT NULL,
    status_href VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, actor_user_id, command_scope, idempotency_key),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, service_order_id)
        REFERENCES wp_service_orders(tenant_id, service_order_id),
    CONSTRAINT ck_wp_service_command_state CHECK
        (command_state IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_service_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE wp_service_order_outbox (
    outbox_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(160),
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, outbox_event_id),
    CONSTRAINT ck_wp_service_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_wp_service_outbox_attempts CHECK (publish_attempts >= 0)
);

CREATE INDEX idx_wp_service_outbox_pending
    ON wp_service_order_outbox(created_at, outbox_event_id) WHERE published_at IS NULL;

INSERT INTO wp_service_provider_truth (
    tenant_id, provider_code, configured, configuration_version,
    observed_configuration_version, reported_state, evidence_reference,
    observed_at, received_at)
SELECT tenant_id, 'DWP_NATIVE_FULFILLMENT', TRUE, 1, 1, 'HEALTHY',
       'dwp-native:manual-fulfillment:v1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM sys_service_tenants
ON CONFLICT (tenant_id, provider_code) DO NOTHING;

INSERT INTO wp_service_catalog_items (
    catalog_item_id, tenant_id, service_code, category, name_ko, name_en,
    description_ko, description_en, provider_code, site_scope, option_schema,
    supported_resource_types, unit_price, currency, minimum_quantity,
    maximum_quantity, order_cutoff_minutes, cancellation_cutoff_minutes,
    cancellation_policy_ko, cancellation_policy_en,
    requires_attendee_count, requires_cost_center)
SELECT md5('workplace-service:' || tenant_id || ':' || seed.service_code)::uuid,
       tenant_id, seed.service_code, seed.category, seed.name_ko, seed.name_en,
       seed.description_ko, seed.description_en, 'DWP_NATIVE_FULFILLMENT',
       '[]'::jsonb, seed.option_schema::jsonb, seed.resource_types::jsonb, seed.unit_price,
       'KRW', 1, seed.maximum_quantity, seed.order_cutoff_minutes,
       seed.cancellation_cutoff_minutes, seed.cancel_ko, seed.cancel_en,
       seed.attendee_count, seed.cost_center
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
   ('CATERING_STANDARD', 'CATERING', '케이터링', 'Catering',
    '참석 인원과 식이 옵션에 맞춘 다과 서비스', 'Refreshments with dietary options for attendees',
    '[{"key":"dietary","type":"MULTI_SELECT","values":["STANDARD","VEGAN","HALAL","ALLERGY_NOTE"]}]',
    '["ROOM"]', 15000.00, 500, 240, 180,
    '시작 3시간 전까지 무료 취소할 수 있습니다.', 'Free cancellation until three hours before start.', TRUE, TRUE),
   ('AV_ASSIST', 'AV', 'AV 사전 점검', 'AV readiness',
    '회의 전 영상·음향·화상회의 장비 점검', 'Pre-meeting video, audio, and conference equipment check',
    '[{"key":"system","type":"MULTI_SELECT","values":["DISPLAY","MICROPHONE","VIDEO_CONFERENCE"]}]',
    '["ROOM"]', 0.00, 10, 90, 60,
    '시작 1시간 전까지 취소할 수 있습니다.', 'Cancellation is available until one hour before start.', FALSE, FALSE),
   ('ROOM_LAYOUT', 'ROOM_LAYOUT', '공간 배치', 'Room layout',
    '좌석과 테이블 배치를 미리 준비합니다.', 'Prepare seating and table layout in advance.',
    '[{"key":"layout","type":"SINGLE_SELECT","values":["BOARDROOM","CLASSROOM","THEATER","U_SHAPE"]}]',
    '["ROOM"]', 0.00, 1, 180, 120,
    '시작 2시간 전까지 취소할 수 있습니다.', 'Cancellation is available until two hours before start.', TRUE, FALSE),
   ('IT_ONSITE', 'IT_SUPPORT', '현장 IT 지원', 'On-site IT support',
    '회의 시작 전후 현장 기술 지원을 요청합니다.', 'Request on-site technical support around meeting start.',
    '[{"key":"durationMinutes","type":"NUMBER","minimum":15,"maximum":240}]',
    '["ROOM","DESK"]', 0.00, 8, 120, 90,
    '시작 90분 전까지 취소할 수 있습니다.', 'Cancellation is available until 90 minutes before start.', FALSE, TRUE),
   ('CLEANING_RESET', 'CLEANING', '공간 정리', 'Cleaning reset',
    '예약 전후 공간 정리와 소모품 보충을 요청합니다.', 'Request cleaning and consumable replenishment before or after use.',
    '[{"key":"timing","type":"SINGLE_SELECT","values":["BEFORE","AFTER"]}]',
    '["ROOM","DESK"]', 0.00, 4, 120, 60,
    '시작 1시간 전까지 취소할 수 있습니다.', 'Cancellation is available until one hour before start.', FALSE, FALSE)
 ) AS seed(service_code, category, name_ko, name_en, description_ko, description_en,
           option_schema, resource_types, unit_price, maximum_quantity,
           order_cutoff_minutes, cancellation_cutoff_minutes, cancel_ko, cancel_en,
           attendee_count, cost_center)
ON CONFLICT (tenant_id, service_code) DO NOTHING;

COMMENT ON TABLE wp_service_orders IS
    'Reservation-linked Workplace service orders. Cancelling a reservation never implicitly cancels a service order.';
COMMENT ON TABLE wp_service_provider_truth IS
    'Provider observations. READY is derived only from matching, fresh evidence and is never inferred from configuration alone.';
COMMENT ON TABLE wp_service_order_commands IS
    'Tenant and actor scoped command receipts. A reused key with another fingerprint is rejected.';
COMMENT ON TABLE wp_service_order_outbox IS
    'Transactional outbox dedicated to the independent Workplace Services domain.';
