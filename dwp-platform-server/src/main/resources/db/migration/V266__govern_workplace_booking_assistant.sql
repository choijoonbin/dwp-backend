CREATE TABLE wp_assistant_governance (
    tenant_id BIGINT PRIMARY KEY REFERENCES sys_service_tenants(tenant_id),
    tenant_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    kill_switch BOOLEAN NOT NULL DEFAULT TRUE,
    model_provider_reference VARCHAR(160),
    model_version VARCHAR(120),
    prompt_version VARCHAR(120),
    tool_version VARCHAR(120),
    retention_days INTEGER NOT NULL DEFAULT 30,
    feedback_use_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    redaction_state VARCHAR(20) NOT NULL DEFAULT 'BLOCKED',
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_wp_assistant_governance_retention
        CHECK (retention_days BETWEEN 1 AND 365),
    CONSTRAINT ck_wp_assistant_governance_redaction
        CHECK (redaction_state IN ('READY','BLOCKED')),
    CONSTRAINT ck_wp_assistant_governance_version CHECK (version > 0),
    CONSTRAINT ck_wp_assistant_governance_runtime CHECK (
        kill_switch OR NOT tenant_opt_in OR (
            redaction_state = 'READY'
            AND model_provider_reference IS NOT NULL
            AND length(btrim(model_provider_reference)) BETWEEN 1 AND 160
            AND model_version IS NOT NULL AND length(btrim(model_version)) BETWEEN 1 AND 120
            AND prompt_version IS NOT NULL AND length(btrim(prompt_version)) BETWEEN 1 AND 120
            AND tool_version IS NOT NULL AND length(btrim(tool_version)) BETWEEN 1 AND 120
        )
    )
);

CREATE TABLE wp_assistant_requests (
    request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    request_state VARCHAR(32) NOT NULL,
    redacted_request_text VARCHAR(4000),
    redaction_state VARCHAR(32) NOT NULL,
    request_processing_consent BOOLEAN NOT NULL,
    feedback_use_consent BOOLEAN NOT NULL DEFAULT FALSE,
    model_provider_reference VARCHAR(160) NOT NULL,
    model_version VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(120) NOT NULL,
    tool_version VARCHAR(120) NOT NULL,
    structured_proposal JSONB NOT NULL DEFAULT '[]'::jsonb,
    validation_snapshot JSONB,
    booking_intent_id UUID,
    booking_batch_id UUID,
    requery_required BOOLEAN NOT NULL DEFAULT FALSE,
    last_result_code VARCHAR(120),
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    retention_expires_at TIMESTAMPTZ NOT NULL,
    retention_deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, request_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, booking_intent_id)
        REFERENCES wp_booking_intents(tenant_id, intent_id),
    FOREIGN KEY (tenant_id, booking_batch_id)
        REFERENCES wp_booking_batches(tenant_id, batch_id),
    CONSTRAINT ck_wp_assistant_request_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_assistant_request_state CHECK (request_state IN (
        'SUGGESTED','VALIDATED','AWAITING_CONFIRMATION','PROCESSING',
        'SUCCEEDED','PARTIAL','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_assistant_request_redaction CHECK (redaction_state IN (
        'APPLIED','NOT_REQUIRED','RETAINED_CONTENT_DELETED')),
    CONSTRAINT ck_wp_assistant_request_consent CHECK (request_processing_consent),
    CONSTRAINT ck_wp_assistant_request_json CHECK (
        jsonb_typeof(structured_proposal) = 'array'
        AND (validation_snapshot IS NULL OR jsonb_typeof(validation_snapshot) = 'object')
        AND jsonb_typeof(limitations) = 'array'),
    CONSTRAINT ck_wp_assistant_request_authority_refs CHECK (
        (request_state = 'SUGGESTED' AND booking_intent_id IS NULL AND booking_batch_id IS NULL)
        OR (request_state IN ('VALIDATED','AWAITING_CONFIRMATION')
            AND booking_intent_id IS NOT NULL AND booking_batch_id IS NULL)
        OR (request_state IN ('PROCESSING','SUCCEEDED','PARTIAL','FAILED','RESULT_UNKNOWN')
            AND booking_intent_id IS NOT NULL AND booking_batch_id IS NOT NULL)),
    CONSTRAINT ck_wp_assistant_request_requery CHECK (
        NOT requery_required OR request_state IN ('PARTIAL','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_assistant_request_retention CHECK (
        (retention_deleted_at IS NULL AND redacted_request_text IS NOT NULL
            AND redaction_state IN ('APPLIED','NOT_REQUIRED'))
        OR (retention_deleted_at IS NOT NULL AND redacted_request_text IS NULL
            AND redaction_state = 'RETAINED_CONTENT_DELETED')),
    CONSTRAINT ck_wp_assistant_request_key CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_assistant_request_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_assistant_request_version CHECK (version > 0),
    CONSTRAINT ck_wp_assistant_request_expiry CHECK (retention_expires_at > created_at)
);

CREATE INDEX idx_wp_assistant_requests_actor
    ON wp_assistant_requests(tenant_id, actor_user_id, updated_at DESC);
CREATE INDEX idx_wp_assistant_requests_recovery
    ON wp_assistant_requests(tenant_id, request_state, updated_at)
    WHERE request_state IN ('PROCESSING','PARTIAL','FAILED','RESULT_UNKNOWN');
CREATE INDEX idx_wp_assistant_requests_retention
    ON wp_assistant_requests(retention_expires_at, request_id)
    WHERE retention_deleted_at IS NULL;

CREATE TABLE wp_assistant_proposal_items (
    proposal_item_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    request_id UUID NOT NULL,
    sort_order INTEGER NOT NULL,
    client_item_key VARCHAR(120) NOT NULL,
    requested_item JSONB NOT NULL,
    rationale VARCHAR(1000) NOT NULL,
    constraints_used JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclusions JSONB NOT NULL DEFAULT '[]'::jsonb,
    policy_result VARCHAR(24) NOT NULL DEFAULT 'UNVALIDATED',
    conflicts JSONB NOT NULL DEFAULT '[]'::jsonb,
    alternatives JSONB NOT NULL DEFAULT '[]'::jsonb,
    authoritative_intent_item_id UUID,
    authoritative_intent_item_version BIGINT,
    selected_resource_id UUID,
    selected_resource_version BIGINT,
    selected_resource_name VARCHAR(240),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, request_id, proposal_item_id),
    UNIQUE (tenant_id, request_id, client_item_key),
    UNIQUE (tenant_id, request_id, sort_order),
    FOREIGN KEY (tenant_id, request_id)
        REFERENCES wp_assistant_requests(tenant_id, request_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, selected_resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    FOREIGN KEY (tenant_id, authoritative_intent_item_id)
        REFERENCES wp_booking_intent_items(tenant_id, intent_item_id),
    CONSTRAINT ck_wp_assistant_proposal_sort CHECK (sort_order BETWEEN 0 AND 49),
    CONSTRAINT ck_wp_assistant_proposal_policy CHECK (policy_result IN (
        'UNVALIDATED','ALLOWED','DENIED','CONFLICT','UNAVAILABLE')),
    CONSTRAINT ck_wp_assistant_proposal_json CHECK (
        jsonb_typeof(requested_item) = 'object'
        AND jsonb_typeof(constraints_used) = 'array'
        AND jsonb_typeof(exclusions) = 'array'
        AND jsonb_typeof(conflicts) = 'array'
        AND jsonb_typeof(alternatives) = 'array'),
    CONSTRAINT ck_wp_assistant_proposal_authority CHECK (
        (authoritative_intent_item_id IS NULL AND authoritative_intent_item_version IS NULL
            AND selected_resource_id IS NULL
            AND selected_resource_version IS NULL AND selected_resource_name IS NULL)
        OR (authoritative_intent_item_id IS NOT NULL
            AND authoritative_intent_item_version IS NOT NULL
            AND authoritative_intent_item_version > 0
            AND ((selected_resource_id IS NULL AND selected_resource_version IS NULL)
                OR (selected_resource_id IS NOT NULL AND selected_resource_version IS NOT NULL
                    AND selected_resource_version >= 0)))),
    CONSTRAINT ck_wp_assistant_proposal_version CHECK (version > 0)
);

CREATE INDEX idx_wp_assistant_proposals_request
    ON wp_assistant_proposal_items(tenant_id, request_id, sort_order);

CREATE TABLE wp_assistant_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    request_id UUID,
    command_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    status_href VARCHAR(500) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    result_code VARCHAR(120),
    execution_claim_token UUID,
    execution_lease_until TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, command_type, idempotency_key),
    FOREIGN KEY (tenant_id, request_id)
        REFERENCES wp_assistant_requests(tenant_id, request_id),
    CONSTRAINT ck_wp_assistant_command_type CHECK (command_type IN (
        'CREATE_REQUEST','VALIDATE_REQUEST','CONFIRM_REQUEST','SUBMIT_FEEDBACK',
        'UPDATE_GOVERNANCE')),
    CONSTRAINT ck_wp_assistant_command_state CHECK (command_state IN (
        'ACCEPTED','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_assistant_command_key CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_assistant_command_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_assistant_command_reason CHECK
        (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_assistant_command_execution_claim CHECK (
        (execution_claim_token IS NULL AND execution_lease_until IS NULL)
        OR (command_type = 'CONFIRM_REQUEST' AND command_state = 'ACCEPTED'
            AND execution_claim_token IS NOT NULL AND execution_lease_until IS NOT NULL
            AND execution_lease_until > accepted_at))
);

CREATE UNIQUE INDEX uq_wp_assistant_active_confirmation
    ON wp_assistant_commands(tenant_id, request_id)
    WHERE command_type = 'CONFIRM_REQUEST' AND command_state = 'ACCEPTED';

CREATE TABLE wp_assistant_feedback (
    feedback_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    request_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    rating VARCHAR(24) NOT NULL,
    redacted_comment VARCHAR(2000),
    eligible_for_model_improvement BOOLEAN NOT NULL DEFAULT FALSE,
    audit_event_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, feedback_id),
    UNIQUE (tenant_id, request_id, actor_user_id),
    FOREIGN KEY (tenant_id, request_id)
        REFERENCES wp_assistant_requests(tenant_id, request_id),
    CONSTRAINT ck_wp_assistant_feedback_rating
        CHECK (rating IN ('HELPFUL','NOT_HELPFUL'))
);

CREATE TABLE wp_assistant_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    request_id UUID,
    actor_user_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, audit_event_id),
    FOREIGN KEY (tenant_id, request_id)
        REFERENCES wp_assistant_requests(tenant_id, request_id),
    CONSTRAINT ck_wp_assistant_audit_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

ALTER TABLE wp_assistant_feedback
    ADD CONSTRAINT fk_wp_assistant_feedback_audit
    FOREIGN KEY (tenant_id, audit_event_id)
    REFERENCES wp_assistant_audit_events(tenant_id, audit_event_id);

CREATE INDEX idx_wp_assistant_audit_events_scope
    ON wp_assistant_audit_events(tenant_id, request_id, created_at DESC, audit_event_id);

CREATE TABLE wp_assistant_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_wp_assistant_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_wp_assistant_outbox_version CHECK (aggregate_version > 0)
);

CREATE INDEX idx_wp_assistant_outbox_pending
    ON wp_assistant_outbox(created_at, outbox_id) WHERE published_at IS NULL;

COMMENT ON TABLE wp_assistant_governance IS
    'Tenant opt-in, kill switch, allowlisted model reference and prompt/tool governance. No provider secret or credential material is stored.';
COMMENT ON TABLE wp_assistant_requests IS
    'Redacted suggestion workflow. AI output is non-authoritative; booking_intent_id and booking_batch_id point to authoritative validation and execution.';
COMMENT ON TABLE wp_assistant_proposal_items IS
    'Explainable proposal items and authoritative validation projections. Proposal fields never prove availability or policy eligibility.';
COMMENT ON TABLE wp_assistant_commands IS
    'Idempotent assistant command receipts. RESULT_UNKNOWN is recovered through authoritative batch status lookup, never blind command replay.';
COMMENT ON TABLE wp_assistant_audit_events IS
    'Metadata-only assistant audit trail. Raw prompts, access tokens, credentials and provider secrets are prohibited.';
