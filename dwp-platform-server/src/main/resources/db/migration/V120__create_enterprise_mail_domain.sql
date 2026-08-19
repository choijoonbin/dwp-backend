CREATE TABLE mail_tenant_policies (
    tenant_id BIGINT PRIMARY KEY,
    external_sender_banner BOOLEAN NOT NULL DEFAULT TRUE,
    block_remote_images BOOLEAN NOT NULL DEFAULT TRUE,
    allow_shared_inboxes BOOLEAN NOT NULL DEFAULT TRUE,
    ai_assistance_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ai_cross_app_actions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ai_auto_execute_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    retention_days INTEGER NOT NULL DEFAULT 365,
    maximum_attachment_mb INTEGER NOT NULL DEFAULT 25,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_mail_policy_retention CHECK (retention_days BETWEEN 30 AND 3650),
    CONSTRAINT ck_mail_policy_attachment CHECK (maximum_attachment_mb BETWEEN 1 AND 150),
    CONSTRAINT ck_mail_policy_no_autonomous_execution CHECK (ai_auto_execute_enabled = FALSE)
);

CREATE TABLE mail_provider_connections (
    connection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    connection_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    authentication_mode VARCHAR(24) NOT NULL,
    mail_domain VARCHAR(255),
    credential_ref VARCHAR(500),
    connection_state VARCHAR(32) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED',
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_synchronized_at TIMESTAMPTZ,
    last_error_code VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_mail_provider_connection UNIQUE (tenant_id, connection_key),
    CONSTRAINT ck_mail_provider_type CHECK (provider_type IN (
        'DWP_SANDBOX', 'MICROSOFT_GRAPH', 'GOOGLE_GMAIL',
        'NAVER_WORKS', 'JMAP', 'IMAP_SMTP')),
    CONSTRAINT ck_mail_provider_auth CHECK (authentication_mode IN (
        'NONE', 'OAUTH2', 'SERVICE_ACCOUNT', 'PASSWORD', 'API_TOKEN')),
    CONSTRAINT ck_mail_provider_state CHECK (connection_state IN (
        'ACTIVE', 'CONFIGURATION_REQUIRED', 'SYNCING', 'DEGRADED', 'SUSPENDED')),
    CONSTRAINT ck_mail_provider_capabilities CHECK (jsonb_typeof(capabilities) = 'array'),
    CONSTRAINT ck_mail_provider_credentials CHECK (
        provider_type = 'DWP_SANDBOX' OR credential_ref IS NOT NULL
        OR connection_state = 'CONFIGURATION_REQUIRED')
);

CREATE TABLE mail_accounts (
    account_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    connection_id UUID NOT NULL REFERENCES mail_provider_connections(connection_id),
    owner_user_id BIGINT,
    owner_person_public_id UUID,
    email_address VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    account_kind VARCHAR(20) NOT NULL DEFAULT 'PERSONAL',
    connection_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    synchronization_state VARCHAR(24) NOT NULL DEFAULT 'READY',
    provider_account_ref VARCHAR(500),
    synchronization_cursor VARCHAR(2000),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_mail_account_address UNIQUE (tenant_id, email_address),
    CONSTRAINT uk_mail_account_provider_ref UNIQUE (
        tenant_id, connection_id, provider_account_ref),
    CONSTRAINT ck_mail_account_kind CHECK (account_kind IN ('PERSONAL', 'SHARED')),
    CONSTRAINT ck_mail_account_connection CHECK (connection_state IN (
        'ACTIVE', 'REAUTHENTICATION_REQUIRED', 'SUSPENDED', 'DISCONNECTED')),
    CONSTRAINT ck_mail_account_sync CHECK (synchronization_state IN (
        'READY', 'SYNCING', 'DEGRADED', 'PAUSED')),
    CONSTRAINT ck_mail_account_owner CHECK (
        (account_kind = 'PERSONAL' AND owner_user_id IS NOT NULL)
        OR (account_kind = 'SHARED' AND owner_user_id IS NULL))
);

CREATE UNIQUE INDEX uk_mail_account_default
    ON mail_accounts (tenant_id, owner_user_id)
    WHERE is_default = TRUE AND owner_user_id IS NOT NULL;

CREATE TABLE mail_folders (
    folder_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL REFERENCES mail_accounts(account_id) ON DELETE CASCADE,
    provider_folder_ref VARCHAR(500),
    folder_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    folder_type VARCHAR(20) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_folder_key UNIQUE (account_id, folder_key),
    CONSTRAINT ck_mail_folder_type CHECK (folder_type IN (
        'INBOX', 'SENT', 'DRAFTS', 'ARCHIVE', 'SPAM', 'TRASH', 'CUSTOM')),
    CONSTRAINT ck_mail_folder_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE mail_shared_inboxes (
    shared_inbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL UNIQUE REFERENCES mail_accounts(account_id),
    inbox_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    purpose VARCHAR(1000),
    service_target_minutes INTEGER NOT NULL DEFAULT 240,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_mail_shared_inbox_key UNIQUE (tenant_id, inbox_key),
    CONSTRAINT ck_mail_shared_target CHECK (service_target_minutes BETWEEN 15 AND 10080),
    CONSTRAINT ck_mail_shared_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE mail_shared_inbox_members (
    tenant_id BIGINT NOT NULL,
    shared_inbox_id UUID NOT NULL REFERENCES mail_shared_inboxes(shared_inbox_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, shared_inbox_id, user_id),
    CONSTRAINT ck_mail_shared_member_role CHECK (member_role IN ('MEMBER', 'MANAGER')),
    CONSTRAINT ck_mail_shared_member_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE mail_threads (
    thread_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL REFERENCES mail_accounts(account_id) ON DELETE CASCADE,
    folder_id UUID NOT NULL REFERENCES mail_folders(folder_id),
    shared_inbox_id UUID REFERENCES mail_shared_inboxes(shared_inbox_id),
    provider_thread_ref VARCHAR(500),
    subject VARCHAR(500) NOT NULL,
    preview VARCHAR(1200) NOT NULL,
    participants JSONB NOT NULL DEFAULT '[]'::jsonb,
    latest_message_at TIMESTAMPTZ NOT NULL,
    unread BOOLEAN NOT NULL DEFAULT TRUE,
    starred BOOLEAN NOT NULL DEFAULT FALSE,
    importance VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    triage_lane VARCHAR(24) NOT NULL DEFAULT 'PRIORITY',
    workflow_state VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    snoozed_until TIMESTAMPTZ,
    assigned_user_id BIGINT,
    assigned_name VARCHAR(160),
    has_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    external_sender BOOLEAN NOT NULL DEFAULT FALSE,
    classification VARCHAR(24) NOT NULL DEFAULT 'INTERNAL',
    message_count INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_mail_thread_provider UNIQUE (account_id, provider_thread_ref),
    CONSTRAINT ck_mail_thread_participants CHECK (jsonb_typeof(participants) = 'array'),
    CONSTRAINT ck_mail_thread_importance CHECK (importance IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_mail_thread_lane CHECK (triage_lane IN (
        'PRIORITY', 'NEEDS_REPLY', 'ASSIGNED', 'UPDATES', 'NEWSLETTERS')),
    CONSTRAINT ck_mail_thread_workflow CHECK (workflow_state IN (
        'OPEN', 'DONE', 'SNOOZED', 'ARCHIVED', 'DRAFT')),
    CONSTRAINT ck_mail_thread_classification CHECK (classification IN (
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_mail_thread_count CHECK (message_count > 0),
    CONSTRAINT ck_mail_thread_assignment CHECK (
        (assigned_user_id IS NULL AND assigned_name IS NULL)
        OR (assigned_user_id IS NOT NULL AND assigned_name IS NOT NULL))
);

CREATE TABLE mail_messages (
    message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    provider_message_ref VARCHAR(500),
    sender_email VARCHAR(255) NOT NULL,
    sender_name VARCHAR(160) NOT NULL,
    recipients JSONB NOT NULL DEFAULT '[]'::jsonb,
    message_direction VARCHAR(16) NOT NULL,
    body_format VARCHAR(12) NOT NULL DEFAULT 'TEXT',
    body_content TEXT NOT NULL,
    attachments JSONB NOT NULL DEFAULT '[]'::jsonb,
    sent_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT uk_mail_message_provider UNIQUE (thread_id, provider_message_ref),
    CONSTRAINT ck_mail_message_recipients CHECK (jsonb_typeof(recipients) = 'array'),
    CONSTRAINT ck_mail_message_attachments CHECK (jsonb_typeof(attachments) = 'array'),
    CONSTRAINT ck_mail_message_direction CHECK (message_direction IN ('INBOUND', 'OUTBOUND', 'DRAFT')),
    CONSTRAINT ck_mail_message_format CHECK (body_format IN ('TEXT', 'HTML'))
);

CREATE TABLE mail_internal_comments (
    comment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    author_user_id BIGINT NOT NULL,
    author_name VARCHAR(160) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    mentioned_user_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_mail_comment_body CHECK (length(btrim(body)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_mail_comment_mentions CHECK (jsonb_typeof(mentioned_user_ids) = 'array')
);

CREATE TABLE mail_action_proposals (
    proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL REFERENCES mail_accounts(account_id) ON DELETE CASCADE,
    thread_id UUID NOT NULL REFERENCES mail_threads(thread_id) ON DELETE CASCADE,
    proposal_type VARCHAR(40) NOT NULL,
    proposal_status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    title VARCHAR(240) NOT NULL,
    summary VARCHAR(1200) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    proposed_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence NUMERIC(5,4) NOT NULL,
    risk_level VARCHAR(12) NOT NULL,
    required_resource_key VARCHAR(120),
    required_permission_code VARCHAR(30),
    target_route VARCHAR(500),
    expires_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    decided_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_mail_proposal_type CHECK (proposal_type IN (
        'DRAFT_REPLY', 'CREATE_CALENDAR_EVENT', 'CREATE_LEAVE_REQUEST',
        'CREATE_TASK', 'ESCALATE_NOTIFICATION')),
    CONSTRAINT ck_mail_proposal_status CHECK (proposal_status IN (
        'PROPOSED', 'ACCEPTED', 'DISMISSED', 'EXPIRED', 'EXECUTED')),
    CONSTRAINT ck_mail_proposal_evidence CHECK (jsonb_typeof(evidence) = 'array'),
    CONSTRAINT ck_mail_proposal_payload CHECK (jsonb_typeof(proposed_payload) = 'object'),
    CONSTRAINT ck_mail_proposal_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_mail_proposal_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_mail_proposal_decision CHECK (
        (proposal_status = 'PROPOSED' AND decided_at IS NULL AND decided_by IS NULL)
        OR proposal_status IN ('EXPIRED')
        OR (proposal_status IN ('ACCEPTED', 'DISMISSED', 'EXECUTED')
            AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);

CREATE TABLE mail_domain_events (
    domain_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(160),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_mail_domain_event_type CHECK (event_type ~ '^mail\.[a-z][a-z0-9.]{2,114}$'),
    CONSTRAINT ck_mail_domain_event_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_mail_domain_event_attempts CHECK (publish_attempts >= 0)
);

CREATE TABLE mail_audit_events (
    audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    correlation_id VARCHAR(160),
    before_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_mail_audit_action CHECK (action ~ '^mail\.[a-z][a-z0-9.]{2,114}$'),
    CONSTRAINT ck_mail_audit_snapshots CHECK (
        jsonb_typeof(before_snapshot) = 'object'
        AND jsonb_typeof(after_snapshot) = 'object')
);

CREATE INDEX idx_mail_account_owner
    ON mail_accounts (tenant_id, owner_user_id, connection_state);
CREATE INDEX idx_mail_thread_queue
    ON mail_threads (tenant_id, account_id, workflow_state, triage_lane, latest_message_at DESC);
CREATE INDEX idx_mail_thread_unread
    ON mail_threads (tenant_id, account_id, latest_message_at DESC)
    WHERE unread = TRUE AND workflow_state <> 'ARCHIVED';
CREATE INDEX idx_mail_thread_assignee
    ON mail_threads (tenant_id, assigned_user_id, workflow_state, latest_message_at DESC)
    WHERE assigned_user_id IS NOT NULL;
CREATE INDEX idx_mail_shared_member_user
    ON mail_shared_inbox_members (tenant_id, user_id, lifecycle_state, shared_inbox_id);
CREATE INDEX idx_mail_message_thread_time
    ON mail_messages (tenant_id, thread_id, sent_at);
CREATE INDEX idx_mail_comment_thread_time
    ON mail_internal_comments (tenant_id, thread_id, created_at);
CREATE INDEX idx_mail_proposal_owner_status
    ON mail_action_proposals (tenant_id, account_id, proposal_status, created_at DESC);
CREATE INDEX idx_mail_domain_event_outbox
    ON mail_domain_events (occurred_at)
    WHERE published_at IS NULL;
CREATE INDEX idx_mail_audit_target_time
    ON mail_audit_events (tenant_id, target_type, target_id, occurred_at DESC);

INSERT INTO mail_tenant_policies (tenant_id, created_by, updated_by)
SELECT tenant_id, 1, 1 FROM sys_service_tenants
ON CONFLICT (tenant_id) DO NOTHING;

COMMENT ON COLUMN mail_provider_connections.credential_ref IS
    'Opaque reference to an external secret store. Provider secrets must never be persisted in this database.';
COMMENT ON TABLE mail_action_proposals IS
    'Human-confirmed, evidence-bearing proposals for mail and cross-application actions. Agents do not mutate business systems directly.';
COMMENT ON TABLE mail_domain_events IS
    'Transactional outbox for integration and agent consumers. Payloads contain references and minimum necessary metadata only.';
