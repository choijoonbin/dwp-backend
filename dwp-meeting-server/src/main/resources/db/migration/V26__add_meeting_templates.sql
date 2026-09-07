CREATE TABLE vm_meeting_templates (
    template_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    template_scope VARCHAR(20) NOT NULL,
    name VARCHAR(160) NOT NULL,
    purpose VARCHAR(2000) NOT NULL DEFAULT '',
    category VARCHAR(40) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_vm_template_tenant UNIQUE (tenant_id, template_id),
    CONSTRAINT ck_vm_template_scope CHECK (template_scope IN ('PERSONAL', 'ORGANIZATION')),
    CONSTRAINT ck_vm_template_duration CHECK (duration_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ck_vm_template_name CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_vm_template_version CHECK (version >= 0)
);
CREATE INDEX ix_vm_template_owner ON vm_meeting_templates (tenant_id, owner_user_id, updated_at DESC);
CREATE INDEX ix_vm_template_org ON vm_meeting_templates (tenant_id, template_scope, updated_at DESC);

-- Immutable revisions preserve the exact source used by an applied schedule draft.
CREATE TABLE vm_meeting_template_revisions (
    tenant_id BIGINT NOT NULL,
    template_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, template_id, revision),
    FOREIGN KEY (tenant_id, template_id) REFERENCES vm_meeting_templates (tenant_id, template_id),
    CONSTRAINT ck_vm_template_revision CHECK (revision >= 0 AND jsonb_typeof(snapshot) = 'object')
);
CREATE FUNCTION vm_reject_template_revision_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'meeting template revisions are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_vm_template_revision_immutable
    BEFORE UPDATE OR DELETE ON vm_meeting_template_revisions
    FOR EACH ROW EXECUTE FUNCTION vm_reject_template_revision_mutation();

CREATE TABLE vm_meeting_template_agenda_items (
    tenant_id BIGINT NOT NULL,
    template_id UUID NOT NULL,
    position INTEGER NOT NULL,
    title VARCHAR(240) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    presenter_role VARCHAR(80) NOT NULL DEFAULT '',
    duration_minutes INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, template_id, position),
    FOREIGN KEY (tenant_id, template_id) REFERENCES vm_meeting_templates (tenant_id, template_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_template_agenda_position CHECK (position BETWEEN 0 AND 49),
    CONSTRAINT ck_vm_template_agenda_duration CHECK (duration_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_vm_template_agenda_title CHECK (length(trim(title)) > 0)
);

CREATE TABLE vm_meeting_template_favorites (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    template_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, template_id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES vm_meeting_templates (tenant_id, template_id) ON DELETE CASCADE
);

-- No template content, participant data, invitation secret or device identifiers are
-- duplicated in receipts. Replays return the currently authorized canonical entity.
CREATE TABLE vm_meeting_workspace_commands (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_type VARCHAR(60) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_id UUID,
    result_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, command_type, idempotency_key),
    CONSTRAINT ck_vm_workspace_request_hash CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_vm_workspace_result_version CHECK (result_version >= 0)
);
