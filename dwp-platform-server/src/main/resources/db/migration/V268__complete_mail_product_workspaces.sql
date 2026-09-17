-- Completes the product-owned state required by the Mail U01-U14 and A01-A06 workspaces.
-- External provider evidence remains explicit; these tables never manufacture provider success.

CREATE TABLE mail_templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    account_id UUID REFERENCES mail_accounts(account_id) ON DELETE CASCADE,
    template_scope VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
    display_name VARCHAR(160) NOT NULL,
    subject_template VARCHAR(500) NOT NULL DEFAULT '',
    body_content TEXT NOT NULL,
    body_format VARCHAR(12) NOT NULL DEFAULT 'TEXT',
    mandatory_content TEXT,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_mail_template_scope CHECK (template_scope IN ('PERSONAL', 'ACCOUNT', 'ORGANIZATION')),
    CONSTRAINT ck_mail_template_format CHECK (body_format IN ('TEXT', 'HTML')),
    CONSTRAINT ck_mail_template_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mail_template_account_scope CHECK (
        (template_scope = 'PERSONAL' AND account_id IS NULL)
        OR (template_scope = 'ACCOUNT' AND account_id IS NOT NULL)
        OR (template_scope = 'ORGANIZATION' AND account_id IS NULL))
);

CREATE INDEX idx_mail_template_owner
    ON mail_templates (tenant_id, owner_user_id, lifecycle_state, updated_at DESC);

CREATE TABLE mail_signatures (
    signature_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    account_id UUID REFERENCES mail_accounts(account_id) ON DELETE CASCADE,
    signature_scope VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
    display_name VARCHAR(160) NOT NULL,
    body_content TEXT NOT NULL,
    body_format VARCHAR(12) NOT NULL DEFAULT 'HTML',
    default_for_new BOOLEAN NOT NULL DEFAULT FALSE,
    default_for_reply BOOLEAN NOT NULL DEFAULT FALSE,
    mandatory_content TEXT,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_mail_signature_scope CHECK (signature_scope IN ('PERSONAL', 'ACCOUNT', 'ORGANIZATION')),
    CONSTRAINT ck_mail_signature_format CHECK (body_format IN ('TEXT', 'HTML')),
    CONSTRAINT ck_mail_signature_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mail_signature_account_scope CHECK (
        (signature_scope = 'PERSONAL' AND account_id IS NULL)
        OR (signature_scope = 'ACCOUNT' AND account_id IS NOT NULL)
        OR (signature_scope = 'ORGANIZATION' AND account_id IS NULL))
);

CREATE INDEX idx_mail_signature_owner
    ON mail_signatures (tenant_id, owner_user_id, lifecycle_state, updated_at DESC);
CREATE UNIQUE INDEX uk_mail_signature_personal_default
    ON mail_signatures (tenant_id, owner_user_id)
    WHERE default_for_new = TRUE AND account_id IS NULL AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX uk_mail_signature_account_default
    ON mail_signatures (tenant_id, owner_user_id, account_id)
    WHERE default_for_new = TRUE AND account_id IS NOT NULL AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX uk_mail_signature_personal_reply_default
    ON mail_signatures (tenant_id, owner_user_id)
    WHERE default_for_reply = TRUE AND account_id IS NULL AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX uk_mail_signature_account_reply_default
    ON mail_signatures (tenant_id, owner_user_id, account_id)
    WHERE default_for_reply = TRUE AND account_id IS NOT NULL AND lifecycle_state = 'ACTIVE';

CREATE TABLE mail_user_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    density VARCHAR(16) NOT NULL DEFAULT 'COMFORTABLE',
    remote_images VARCHAR(12) NOT NULL DEFAULT 'ASK',
    send_delay_seconds INTEGER NOT NULL DEFAULT 0,
    keyboard_shortcuts BOOLEAN NOT NULL DEFAULT FALSE,
    notify_new_mail BOOLEAN NOT NULL DEFAULT TRUE,
    notify_shared_assignment BOOLEAN NOT NULL DEFAULT TRUE,
    notify_follow_up_due BOOLEAN NOT NULL DEFAULT TRUE,
    default_account_id UUID REFERENCES mail_accounts(account_id) ON DELETE SET NULL,
    default_signature_id UUID REFERENCES mail_signatures(signature_id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_mail_preference_density CHECK (density IN ('COMFORTABLE', 'COMPACT')),
    CONSTRAINT ck_mail_preference_remote_images CHECK (remote_images IN ('BLOCK', 'ASK', 'ALLOW')),
    CONSTRAINT ck_mail_preference_send_delay CHECK (send_delay_seconds BETWEEN 0 AND 120)
);

CREATE TABLE mail_saved_views (
    saved_view_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_mail_saved_view_filters CHECK (jsonb_typeof(filters) = 'object')
);

CREATE UNIQUE INDEX uk_mail_saved_view_name
    ON mail_saved_views (tenant_id, owner_user_id, lower(display_name));
CREATE UNIQUE INDEX uk_mail_saved_view_default
    ON mail_saved_views (tenant_id, owner_user_id) WHERE is_default = TRUE;

CREATE TABLE mail_follow_up_trackers (
    follow_up_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    expected_reply_at TIMESTAMPTZ NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    note VARCHAR(1000),
    tracker_status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    last_checked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_mail_follow_up_thread UNIQUE (tenant_id, owner_user_id, thread_id),
    CONSTRAINT ck_mail_follow_up_status CHECK (
        tracker_status IN ('WAITING', 'REPLIED', 'OVERDUE', 'CANCELLED'))
);

CREATE INDEX idx_mail_follow_up_due
    ON mail_follow_up_trackers (tenant_id, owner_user_id, tracker_status, expected_reply_at);

CREATE TABLE mail_compose_attachments (
    attachment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    uploader_user_id BIGINT NOT NULL,
    thread_id UUID REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    storage_reference VARCHAR(1000) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    scan_state VARCHAR(16) NOT NULL DEFAULT 'READY',
    scan_evidence VARCHAR(320) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_mail_attachment_size CHECK (size_bytes BETWEEN 1 AND 157286400),
    CONSTRAINT ck_mail_attachment_scan CHECK (
        scan_state IN ('UPLOADING', 'SCANNING', 'READY', 'BLOCKED', 'FAILED'))
);

CREATE INDEX idx_mail_attachment_owner
    ON mail_compose_attachments (tenant_id, uploader_user_id, created_at DESC);

CREATE TABLE mail_draft_options (
    tenant_id BIGINT NOT NULL,
    thread_id UUID PRIMARY KEY REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    owner_user_id BIGINT NOT NULL,
    account_id UUID REFERENCES mail_accounts(account_id) ON DELETE SET NULL,
    recipients JSONB NOT NULL DEFAULT '[]'::jsonb,
    body_format VARCHAR(12) NOT NULL DEFAULT 'TEXT',
    attachment_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    scheduled_at TIMESTAMPTZ,
    time_zone VARCHAR(80),
    template_id UUID REFERENCES mail_templates(template_id) ON DELETE SET NULL,
    signature_id UUID REFERENCES mail_signatures(signature_id) ON DELETE SET NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_mail_draft_options_recipients CHECK (jsonb_typeof(recipients) = 'array'),
    CONSTRAINT ck_mail_draft_options_attachments CHECK (jsonb_typeof(attachment_ids) = 'array'),
    CONSTRAINT ck_mail_draft_options_format CHECK (body_format IN ('TEXT', 'HTML')),
    CONSTRAINT ck_mail_draft_options_schedule CHECK (
        scheduled_at IS NULL OR time_zone IS NOT NULL)
);

CREATE TABLE mail_group_send_history (
    receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    group_id UUID NOT NULL REFERENCES mail_contact_groups(group_id) ON DELETE RESTRICT,
    group_version BIGINT NOT NULL,
    recipient_mode VARCHAR(8) NOT NULL,
    recipient_count INTEGER NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE RESTRICT,
    delivery_id UUID REFERENCES mail_delivery_outbox(delivery_id) ON DELETE SET NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    receipt_state VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    CONSTRAINT ck_mail_group_send_mode CHECK (recipient_mode IN ('TO', 'BCC')),
    CONSTRAINT ck_mail_group_send_count CHECK (recipient_count > 0),
    CONSTRAINT ck_mail_group_send_state CHECK (
        receipt_state IN ('ACCEPTED', 'DELIVERED', 'FAILED', 'UNKNOWN', 'CANCELLED'))
);

CREATE INDEX idx_mail_group_send_history
    ON mail_group_send_history (tenant_id, owner_user_id, group_id, accepted_at DESC);

CREATE TABLE mail_policy_history (
    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    changed_by BIGINT NOT NULL,
    diff_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    apply_result VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    correlation_id VARCHAR(160),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_mail_policy_history_diff CHECK (jsonb_typeof(diff_summary) = 'object'),
    CONSTRAINT ck_mail_policy_history_result CHECK (
        apply_result IN ('APPLIED', 'PARTIAL', 'FAILED', 'PENDING'))
);

CREATE TABLE mail_shared_inbox_access_grants (
    member_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    shared_inbox_id UUID NOT NULL REFERENCES mail_shared_inboxes(shared_inbox_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    department VARCHAR(160),
    member_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ,
    can_read BOOLEAN NOT NULL DEFAULT TRUE,
    can_send_as BOOLEAN NOT NULL DEFAULT FALSE,
    can_send_on_behalf BOOLEAN NOT NULL DEFAULT FALSE,
    can_assign BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage BOOLEAN NOT NULL DEFAULT FALSE,
    provider_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_mail_shared_access_user UNIQUE (tenant_id, shared_inbox_id, user_id),
    CONSTRAINT ck_mail_shared_access_state CHECK (member_state IN ('ACTIVE', 'PENDING', 'REVOKED')),
    CONSTRAINT ck_mail_shared_access_provider CHECK (
        provider_state IN ('APPLIED', 'PARTIAL', 'PENDING', 'UNAVAILABLE')),
    CONSTRAINT ck_mail_shared_access_manage CHECK (NOT can_manage OR (can_read AND can_assign))
);

INSERT INTO mail_shared_inbox_access_grants (
    tenant_id, shared_inbox_id, user_id, display_name, member_state,
    can_read, can_send_as, can_send_on_behalf, can_assign, can_manage,
    provider_state, created_by, updated_by)
SELECT member.tenant_id, member.shared_inbox_id, member.user_id,
       'Mail member ' || member.user_id,
       CASE member.lifecycle_state WHEN 'ACTIVE' THEN 'ACTIVE' ELSE 'REVOKED' END,
       TRUE,
       member.member_role = 'MANAGER',
       member.member_role = 'MANAGER',
       member.member_role = 'MANAGER',
       member.member_role = 'MANAGER',
       'PENDING', COALESCE(member.created_by, 1), COALESCE(member.updated_by, 1)
  FROM mail_shared_inbox_members member
ON CONFLICT (tenant_id, shared_inbox_id, user_id) DO NOTHING;

CREATE TABLE mail_connection_operations (
    operation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    connection_id UUID NOT NULL REFERENCES mail_provider_connections(connection_id) ON DELETE CASCADE,
    actor_user_id BIGINT NOT NULL,
    operation_kind VARCHAR(24) NOT NULL,
    operation_scope VARCHAR(24),
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    operation_state VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160),
    evidence_generated_at TIMESTAMPTZ,
    error_code VARCHAR(160),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mail_connection_operation UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_mail_connection_operation_kind CHECK (
        operation_kind IN ('DIAGNOSTIC', 'SYNC', 'TEST_SEND')),
    CONSTRAINT ck_mail_connection_operation_state CHECK (
        operation_state IN ('ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_mail_connection_operation_payload CHECK (jsonb_typeof(request_payload) = 'object')
);

CREATE TABLE mail_legal_holds (
    hold_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    safe_case_reference VARCHAR(240) NOT NULL,
    hold_scope JSONB NOT NULL,
    hold_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_mail_hold_scope CHECK (jsonb_typeof(hold_scope) = 'object'),
    CONSTRAINT ck_mail_hold_status CHECK (hold_status IN ('ACTIVE', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_mail_hold_window CHECK (expires_at IS NULL OR expires_at > starts_at)
);

CREATE TABLE mail_purge_previews (
    candidate_snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    purge_scope JSONB NOT NULL,
    resource_types JSONB NOT NULL,
    before_at TIMESTAMPTZ NOT NULL,
    snapshot_fingerprint CHAR(64) NOT NULL,
    total_candidates INTEGER NOT NULL,
    held_count INTEGER NOT NULL,
    eligible_count INTEGER NOT NULL,
    partial_sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    policy_version BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_purge_preview_command UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_mail_purge_preview_scope CHECK (jsonb_typeof(purge_scope) = 'object'),
    CONSTRAINT ck_mail_purge_preview_resources CHECK (jsonb_typeof(resource_types) = 'array'),
    CONSTRAINT ck_mail_purge_preview_partial CHECK (jsonb_typeof(partial_sources) = 'array'),
    CONSTRAINT ck_mail_purge_preview_counts CHECK (
        total_candidates >= 0 AND held_count >= 0 AND eligible_count >= 0
        AND total_candidates = held_count + eligible_count)
);

CREATE TABLE mail_purge_approvals (
    approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    candidate_snapshot_id UUID NOT NULL REFERENCES mail_purge_previews(candidate_snapshot_id) ON DELETE CASCADE,
    approver_user_id BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_purge_approver UNIQUE (tenant_id, candidate_snapshot_id, approver_user_id),
    CONSTRAINT uk_mail_purge_approval_command UNIQUE (tenant_id, approver_user_id, idempotency_key)
);

CREATE TABLE mail_purge_jobs (
    job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    candidate_snapshot_id UUID NOT NULL UNIQUE REFERENCES mail_purge_previews(candidate_snapshot_id),
    actor_user_id BIGINT NOT NULL,
    job_state VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    deleted_threads INTEGER NOT NULL DEFAULT 0,
    deleted_messages INTEGER NOT NULL DEFAULT 0,
    step_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    verification_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(160),
    idempotency_key UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mail_purge_job_command UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_mail_purge_job_state CHECK (
        job_state IN ('ACCEPTED', 'RUNNING', 'PARTIAL', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_mail_purge_job_steps CHECK (jsonb_typeof(step_results) = 'array'),
    CONSTRAINT ck_mail_purge_verification CHECK (
        verification_state IN ('PENDING', 'VERIFIED', 'FAILED', 'UNKNOWN'))
);

CREATE TABLE mail_delivery_recovery_events (
    recovery_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    delivery_id UUID NOT NULL REFERENCES mail_delivery_outbox(delivery_id) ON DELETE CASCADE,
    actor_user_id BIGINT NOT NULL,
    action_kind VARCHAR(16) NOT NULL,
    result_state VARCHAR(16) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    correlation_id VARCHAR(160),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_delivery_recovery_command UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_mail_delivery_recovery_action CHECK (
        action_kind IN ('RECONCILE', 'RETRY', 'CANCEL')),
    CONSTRAINT ck_mail_delivery_recovery_result CHECK (
        result_state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'BLOCKED')),
    CONSTRAINT ck_mail_delivery_recovery_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE TABLE mail_delivery_audit_exports (
    export_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    purpose VARCHAR(500) NOT NULL,
    export_state VARCHAR(16) NOT NULL DEFAULT 'READY',
    storage_reference VARCHAR(1000),
    watermark VARCHAR(320) NOT NULL,
    idempotency_key UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_mail_delivery_export_command UNIQUE (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT ck_mail_delivery_export_filters CHECK (jsonb_typeof(filters) = 'object'),
    CONSTRAINT ck_mail_delivery_export_state CHECK (
        export_state IN ('ACCEPTED', 'RUNNING', 'READY', 'FAILED'))
);

ALTER TABLE mail_delivery_outbox DROP CONSTRAINT ck_mail_delivery_status;
ALTER TABLE mail_delivery_outbox ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mail_delivery_outbox ADD CONSTRAINT ck_mail_delivery_status CHECK (
    delivery_status IN ('QUEUED', 'LEASED', 'RETRY_WAIT', 'DELIVERED', 'FAILED', 'CANCELLED'));

CREATE INDEX idx_mail_policy_history_time
    ON mail_policy_history (tenant_id, changed_at DESC);
CREATE INDEX idx_mail_connection_operation_time
    ON mail_connection_operations (tenant_id, connection_id, accepted_at DESC);
CREATE INDEX idx_mail_hold_active
    ON mail_legal_holds (tenant_id, hold_status, starts_at, expires_at);
CREATE INDEX idx_mail_delivery_recovery_time
    ON mail_delivery_recovery_events (tenant_id, delivery_id, occurred_at DESC);

COMMENT ON TABLE mail_purge_previews IS
    'Immutable candidate evidence. Execution requires a current policy version, matching fingerprint, no active hold, and two distinct approvers.';
COMMENT ON TABLE mail_shared_inbox_access_grants IS
    'Action-level shared-mailbox grants. Provider state is independent from local authorization.';
COMMENT ON TABLE mail_compose_attachments IS
    'Tenant-scoped compose uploads. Only READY attachments may be linked to a message.';
