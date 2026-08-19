CREATE TABLE msg_attachments (
    attachment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL REFERENCES msg_conversations(conversation_id) ON DELETE CASCADE,
    uploader_user_id BIGINT NOT NULL,
    message_id UUID REFERENCES msg_messages(message_id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    normalized_filename VARCHAR(255) NOT NULL,
    file_extension VARCHAR(20) NOT NULL,
    declared_content_type VARCHAR(160) NOT NULL,
    detected_content_type VARCHAR(160),
    size_bytes BIGINT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_sha256 CHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'QUARANTINED',
    rejection_reason VARCHAR(500),
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    upload_token_hash CHAR(64) NOT NULL,
    upload_expires_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    scan_started_at TIMESTAMPTZ,
    scan_completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_msg_attachment_object_key UNIQUE (object_key),
    CONSTRAINT uk_msg_attachment_idempotency UNIQUE (
        tenant_id, uploader_user_id, conversation_id, idempotency_key),
    CONSTRAINT ck_msg_attachment_size CHECK (size_bytes > 0),
    CONSTRAINT ck_msg_attachment_extension CHECK (
        file_extension ~ '^[a-z0-9]{1,20}$'),
    CONSTRAINT ck_msg_attachment_status CHECK (status IN (
        'QUARANTINED', 'SCANNING', 'CLEAN', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_msg_attachment_request_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_msg_attachment_upload_token CHECK (
        upload_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_msg_attachment_content_hash CHECK (
        content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_msg_attachment_message_state CHECK (
        message_id IS NULL OR status = 'CLEAN')
);

CREATE INDEX ix_msg_attachment_conversation
    ON msg_attachments (tenant_id, conversation_id, created_at DESC);
CREATE INDEX ix_msg_attachment_message
    ON msg_attachments (tenant_id, message_id)
    WHERE message_id IS NOT NULL;
CREATE INDEX ix_msg_attachment_expiry
    ON msg_attachments (status, upload_expires_at)
    WHERE status = 'QUARANTINED';

CREATE TABLE msg_attachment_download_grants (
    grant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attachment_id UUID NOT NULL REFERENCES msg_attachments(attachment_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_msg_attachment_download_token CHECK (
        token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_msg_attachment_download_expiry
    ON msg_attachment_download_grants (expires_at)
    WHERE consumed_at IS NULL;

COMMENT ON TABLE msg_attachments IS
    'Tenant-scoped immutable messaging attachment metadata and quarantine state machine.';
COMMENT ON COLUMN msg_attachments.object_key IS
    'Opaque immutable provider key. Original filenames are never used as storage paths.';
COMMENT ON TABLE msg_attachment_download_grants IS
    'Short-lived, one-use download grants bound to the requesting tenant member.';
