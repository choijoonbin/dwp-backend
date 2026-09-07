CREATE TABLE mail_contacts (
    contact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    create_request_id UUID NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    email_address VARCHAR(255) NOT NULL,
    organization_name VARCHAR(200),
    job_title VARCHAR(160),
    phone_number VARCHAR(40),
    source_kind VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    source_person_public_id UUID,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_mail_contact_owner_id UNIQUE (tenant_id, owner_user_id, contact_id),
    CONSTRAINT uk_mail_contact_create_request
        UNIQUE (tenant_id, owner_user_id, create_request_id),
    CONSTRAINT ck_mail_contact_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 160),
    CONSTRAINT ck_mail_contact_email CHECK (
        email_address = lower(btrim(email_address))
        AND email_address ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'),
    CONSTRAINT ck_mail_contact_source CHECK (source_kind IN ('MANUAL', 'DIRECTORY')),
    CONSTRAINT ck_mail_contact_source_reference CHECK (
        (source_kind = 'MANUAL' AND source_person_public_id IS NULL)
        OR (source_kind = 'DIRECTORY' AND source_person_public_id IS NOT NULL)),
    CONSTRAINT ck_mail_contact_lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mail_contact_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_mail_contact_active_email
    ON mail_contacts (tenant_id, owner_user_id, lower(email_address))
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_mail_contact_owner_search
    ON mail_contacts (tenant_id, owner_user_id, favorite DESC, display_name, contact_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE mail_contact_groups (
    group_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    create_request_id UUID NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_mail_contact_group_owner_id
        UNIQUE (tenant_id, owner_user_id, group_id),
    CONSTRAINT uk_mail_contact_group_create_request
        UNIQUE (tenant_id, owner_user_id, create_request_id),
    CONSTRAINT ck_mail_contact_group_name CHECK (
        length(btrim(display_name)) BETWEEN 1 AND 160),
    CONSTRAINT ck_mail_contact_group_lifecycle CHECK (
        lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mail_contact_group_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_mail_contact_group_active_name
    ON mail_contact_groups (tenant_id, owner_user_id, lower(display_name))
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_mail_contact_group_owner
    ON mail_contact_groups (tenant_id, owner_user_id, display_name, group_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE mail_contact_group_members (
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    group_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, owner_user_id, group_id, contact_id),
    CONSTRAINT fk_mail_contact_group_member_group
        FOREIGN KEY (tenant_id, owner_user_id, group_id)
        REFERENCES mail_contact_groups (tenant_id, owner_user_id, group_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_mail_contact_group_member_contact
        FOREIGN KEY (tenant_id, owner_user_id, contact_id)
        REFERENCES mail_contacts (tenant_id, owner_user_id, contact_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_mail_contact_group_member_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_mail_contact_group_member_contact
    ON mail_contact_group_members (tenant_id, owner_user_id, contact_id, group_id);

CREATE UNIQUE INDEX uk_mail_message_tenant_identity
    ON mail_messages (tenant_id, message_id);

CREATE UNIQUE INDEX uk_mail_delivery_tenant_identity
    ON mail_delivery_outbox (tenant_id, delivery_id);

CREATE TABLE mail_group_recipient_snapshots (
    delivery_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    thread_id UUID NOT NULL,
    message_id UUID NOT NULL,
    group_id UUID NOT NULL,
    group_version BIGINT NOT NULL,
    recipient_count INTEGER NOT NULL,
    recipients JSONB NOT NULL,
    recipients_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mail_group_snapshot_delivery
        FOREIGN KEY (tenant_id, delivery_id)
        REFERENCES mail_delivery_outbox (tenant_id, delivery_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mail_group_snapshot_thread
        FOREIGN KEY (tenant_id, thread_id)
        REFERENCES mail_threads (tenant_id, thread_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mail_group_snapshot_message
        FOREIGN KEY (tenant_id, message_id)
        REFERENCES mail_messages (tenant_id, message_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_mail_group_snapshot_group
        FOREIGN KEY (tenant_id, owner_user_id, group_id)
        REFERENCES mail_contact_groups (tenant_id, owner_user_id, group_id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_mail_group_snapshot_message UNIQUE (tenant_id, message_id),
    CONSTRAINT ck_mail_group_snapshot_version CHECK (group_version >= 0),
    CONSTRAINT ck_mail_group_snapshot_count CHECK (
        recipient_count BETWEEN 1 AND 100
        AND jsonb_typeof(recipients) = 'array'
        AND jsonb_array_length(recipients) = recipient_count),
    CONSTRAINT ck_mail_group_snapshot_hash CHECK (
        recipients_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE OR REPLACE FUNCTION reject_mail_group_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'mail group recipient snapshots are append-only';
END;
$$;

CREATE TRIGGER trg_mail_group_snapshot_append_only
BEFORE UPDATE OR DELETE ON mail_group_recipient_snapshots
FOR EACH ROW
EXECUTE FUNCTION reject_mail_group_snapshot_mutation();

CREATE TABLE mail_address_book_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    target_id UUID,
    applied_version BIGINT,
    command_status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, actor_user_id, command_type, idempotency_key),
    CONSTRAINT ck_mail_address_book_command_type CHECK (command_type IN (
        'CONTACT_CREATE', 'GROUP_CREATE', 'GROUP_MEMBERS_REPLACE', 'GROUP_MESSAGE_SEND')),
    CONSTRAINT ck_mail_address_book_command_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_address_book_command_status CHECK (
        command_status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_mail_address_book_command_completion CHECK (
        (command_status = 'IN_PROGRESS' AND completed_at IS NULL)
        OR (command_status = 'COMPLETED' AND completed_at IS NOT NULL
            AND target_id IS NOT NULL))
);

COMMENT ON TABLE mail_contacts IS
    'User-owned mail contacts. Directory references are provenance only and never grant People access.';
COMMENT ON TABLE mail_contact_groups IS
    'User-owned recipient groups. Membership is resolved under tenant and owner authority at send time.';
COMMENT ON TABLE mail_group_recipient_snapshots IS
    'Immutable canonical group recipient evidence consumed by the delivery worker.';
COMMENT ON TABLE mail_address_book_command_receipts IS
    'Fingerprint-bound idempotency receipts for personal address-book mutations and group delivery.';
