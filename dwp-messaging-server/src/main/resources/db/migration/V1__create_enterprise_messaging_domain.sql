CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE msg_tenant_policies (
    tenant_id BIGINT PRIMARY KEY,
    direct_messages_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    space_messaging_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    allow_message_edit BOOLEAN NOT NULL DEFAULT TRUE,
    allow_message_delete BOOLEAN NOT NULL DEFAULT TRUE,
    ai_assistance_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ai_auto_execute_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    retention_days INTEGER NOT NULL DEFAULT 1095,
    maximum_attachment_mb INTEGER NOT NULL DEFAULT 100,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_msg_policy_retention CHECK (retention_days BETWEEN 30 AND 3650),
    CONSTRAINT ck_msg_policy_attachment CHECK (maximum_attachment_mb BETWEEN 1 AND 1024),
    CONSTRAINT ck_msg_policy_no_auto_execute CHECK (ai_auto_execute_enabled = FALSE)
);

CREATE TABLE msg_people_snapshot (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    person_public_id UUID,
    email_address VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    job_title VARCHAR(180),
    organization_name VARCHAR(180),
    presence_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT uk_msg_people_email UNIQUE (tenant_id, email_address),
    CONSTRAINT ck_msg_people_presence CHECK (presence_state IN (
        'AVAILABLE', 'BUSY', 'AWAY', 'FOCUS', 'OFFLINE', 'UNKNOWN')),
    CONSTRAINT ck_msg_people_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE msg_conversations (
    conversation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    conversation_key VARCHAR(160) NOT NULL,
    conversation_type VARCHAR(24) NOT NULL,
    name VARCHAR(220),
    topic VARCHAR(1200),
    visibility VARCHAR(24) NOT NULL DEFAULT 'PRIVATE',
    data_classification VARCHAR(24) NOT NULL DEFAULT 'INTERNAL',
    linked_space_key VARCHAR(120),
    linked_space_name VARCHAR(220),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    last_message_id UUID,
    last_message_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_msg_conversation_key UNIQUE (tenant_id, conversation_key),
    CONSTRAINT ck_msg_conversation_type CHECK (conversation_type IN (
        'DIRECT', 'GROUP', 'CHANNEL', 'ANNOUNCEMENT', 'INCIDENT', 'MEETING')),
    CONSTRAINT ck_msg_conversation_visibility CHECK (visibility IN (
        'PRIVATE', 'SPACE', 'TENANT_DISCOVERABLE', 'ANNOUNCEMENT')),
    CONSTRAINT ck_msg_conversation_classification CHECK (data_classification IN (
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_msg_conversation_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_msg_space_binding CHECK (
        (visibility = 'SPACE' AND linked_space_key IS NOT NULL)
        OR visibility <> 'SPACE')
);

CREATE INDEX ix_msg_conversation_tenant_activity
    ON msg_conversations (tenant_id, lifecycle_state, last_message_at DESC NULLS LAST);

CREATE TABLE msg_conversation_members (
    tenant_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL REFERENCES msg_conversations(conversation_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    person_public_id UUID,
    member_role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
    membership_source VARCHAR(32) NOT NULL DEFAULT 'DIRECT',
    notification_level VARCHAR(24) NOT NULL DEFAULT 'DEFAULT',
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    last_read_message_id UUID,
    last_read_at TIMESTAMPTZ,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, conversation_id, user_id),
    CONSTRAINT ck_msg_member_role CHECK (member_role IN (
        'VIEWER', 'MEMBER', 'MODERATOR', 'OWNER')),
    CONSTRAINT ck_msg_member_source CHECK (membership_source IN (
        'DIRECT', 'SPACE_MIRRORED', 'SPACE_SCOPED', 'SYSTEM')),
    CONSTRAINT ck_msg_member_notification CHECK (notification_level IN (
        'DEFAULT', 'MENTIONS', 'MUTE')),
    CONSTRAINT ck_msg_member_state CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX ix_msg_member_user
    ON msg_conversation_members (tenant_id, user_id, lifecycle_state, pinned DESC, favorite DESC);

CREATE TABLE msg_messages (
    message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL REFERENCES msg_conversations(conversation_id) ON DELETE CASCADE,
    sender_user_id BIGINT NOT NULL,
    sender_person_public_id UUID,
    sender_name VARCHAR(160) NOT NULL,
    body TEXT NOT NULL,
    content_type VARCHAR(24) NOT NULL DEFAULT 'TEXT',
    message_kind VARCHAR(24) NOT NULL DEFAULT 'USER',
    reply_to_message_id UUID,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_msg_body_length CHECK (length(btrim(body)) BETWEEN 1 AND 20000),
    CONSTRAINT ck_msg_content_type CHECK (content_type IN ('TEXT', 'MARKDOWN')),
    CONSTRAINT ck_msg_kind CHECK (message_kind IN ('USER', 'SYSTEM', 'AI_PROPOSAL')),
    CONSTRAINT fk_msg_reply_parent FOREIGN KEY (reply_to_message_id)
        REFERENCES msg_messages(message_id)
);

CREATE INDEX ix_msg_messages_conversation
    ON msg_messages (tenant_id, conversation_id, created_at DESC, message_id DESC);

CREATE TABLE msg_message_reactions (
    tenant_id BIGINT NOT NULL,
    message_id UUID NOT NULL REFERENCES msg_messages(message_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    emoji VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, message_id, user_id, emoji),
    CONSTRAINT ck_msg_reaction_emoji CHECK (length(btrim(emoji)) BETWEEN 1 AND 40)
);

CREATE TABLE msg_saved_items (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message_id UUID NOT NULL REFERENCES msg_messages(message_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, message_id)
);

CREATE TABLE msg_audit_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT,
    event_type VARCHAR(120) NOT NULL,
    object_type VARCHAR(60) NOT NULL,
    object_id VARCHAR(120) NOT NULL,
    before_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(120),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_msg_audit_before CHECK (jsonb_typeof(before_state) = 'object'),
    CONSTRAINT ck_msg_audit_after CHECK (jsonb_typeof(after_state) = 'object')
);

CREATE INDEX ix_msg_audit_tenant_time
    ON msg_audit_events (tenant_id, occurred_at DESC);
