-- Legal-hold release is a separate, human-approved command. The immutable preview
-- captures only safe aggregates; releasing a hold never creates a purge job.
CREATE TABLE mail_legal_hold_release_previews (
    release_preview_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    hold_id UUID NOT NULL REFERENCES mail_legal_holds(hold_id) ON DELETE RESTRICT,
    requester_user_id BIGINT NOT NULL,
    hold_version BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    hold_scope JSONB NOT NULL,
    retention_boundary TIMESTAMPTZ NOT NULL,
    snapshot_fingerprint CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    affected_resource_counts JSONB NOT NULL,
    currently_held_resource_counts JSONB NOT NULL,
    purge_safe_after_release_resource_counts JSONB NOT NULL,
    still_protected_after_release_resource_counts JSONB NOT NULL,
    provider_capability_required_resource_counts JSONB NOT NULL,
    idempotency_key UUID NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_mail_hold_release_preview_command
        UNIQUE (tenant_id, requester_user_id, idempotency_key),
    CONSTRAINT ck_mail_hold_release_preview_versions
        CHECK (hold_version >= 0 AND policy_version >= 0),
    CONSTRAINT ck_mail_hold_release_preview_window
        CHECK (expires_at > generated_at),
    CONSTRAINT ck_mail_hold_release_preview_fingerprints
        CHECK (snapshot_fingerprint ~ '^[0-9a-f]{64}$'
               AND request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_hold_release_preview_scope
        CHECK (jsonb_typeof(hold_scope) = 'object'),
    CONSTRAINT ck_mail_hold_release_preview_aggregates
        CHECK (jsonb_typeof(affected_resource_counts) = 'object'
               AND jsonb_typeof(currently_held_resource_counts) = 'object'
               AND jsonb_typeof(purge_safe_after_release_resource_counts) = 'object'
               AND jsonb_typeof(still_protected_after_release_resource_counts) = 'object'
               AND jsonb_typeof(provider_capability_required_resource_counts) = 'object')
);

CREATE INDEX idx_mail_hold_release_preview_hold
    ON mail_legal_hold_release_previews (tenant_id, hold_id, generated_at DESC);

CREATE TABLE mail_legal_hold_release_approvals (
    approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    release_preview_id UUID NOT NULL
        REFERENCES mail_legal_hold_release_previews(release_preview_id) ON DELETE RESTRICT,
    approver_user_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    hold_version BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    preview_fingerprint CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    idempotency_key UUID NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_hold_release_approver
        UNIQUE (tenant_id, release_preview_id, approver_user_id),
    CONSTRAINT uk_mail_hold_release_approval_command
        UNIQUE (tenant_id, approver_user_id, idempotency_key),
    CONSTRAINT ck_mail_hold_release_approval_decision
        CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT ck_mail_hold_release_approval_versions
        CHECK (hold_version >= 0 AND policy_version >= 0),
    CONSTRAINT ck_mail_hold_release_approval_fingerprints
        CHECK (preview_fingerprint ~ '^[0-9a-f]{64}$'
               AND request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_mail_hold_release_approval_preview
    ON mail_legal_hold_release_approvals (tenant_id, release_preview_id, decided_at);

CREATE TABLE mail_legal_hold_release_executions (
    execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    release_preview_id UUID NOT NULL
        REFERENCES mail_legal_hold_release_previews(release_preview_id) ON DELETE RESTRICT,
    hold_id UUID NOT NULL REFERENCES mail_legal_holds(hold_id) ON DELETE RESTRICT,
    requester_user_id BIGINT NOT NULL,
    approved_by_user_id BIGINT NOT NULL,
    executed_by_user_id BIGINT NOT NULL,
    hold_version_before BIGINT NOT NULL,
    hold_version_after BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    preview_fingerprint CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    idempotency_key UUID NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_hold_release_execution_preview
        UNIQUE (tenant_id, release_preview_id),
    CONSTRAINT uk_mail_hold_release_execution_command
        UNIQUE (tenant_id, executed_by_user_id, idempotency_key),
    CONSTRAINT ck_mail_hold_release_execution_versions
        CHECK (hold_version_before >= 0
               AND hold_version_after = hold_version_before + 1
               AND policy_version >= 0),
    CONSTRAINT ck_mail_hold_release_execution_fingerprints
        CHECK (preview_fingerprint ~ '^[0-9a-f]{64}$'
               AND request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_hold_release_execution_separation
        CHECK (requester_user_id <> approved_by_user_id)
);

CREATE OR REPLACE FUNCTION guard_mail_legal_hold_release_evidence()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Mail legal-hold release evidence is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_mail_hold_release_preview_append_only
    BEFORE UPDATE OR DELETE ON mail_legal_hold_release_previews
    FOR EACH ROW EXECUTE FUNCTION guard_mail_legal_hold_release_evidence();

CREATE TRIGGER trg_mail_hold_release_approval_append_only
    BEFORE UPDATE OR DELETE ON mail_legal_hold_release_approvals
    FOR EACH ROW EXECUTE FUNCTION guard_mail_legal_hold_release_evidence();

CREATE TRIGGER trg_mail_hold_release_execution_append_only
    BEFORE UPDATE OR DELETE ON mail_legal_hold_release_executions
    FOR EACH ROW EXECUTE FUNCTION guard_mail_legal_hold_release_evidence();

COMMENT ON TABLE mail_legal_hold_release_previews IS
    'Immutable, privacy-minimized impact snapshot required before a legal hold can be released.';
COMMENT ON TABLE mail_legal_hold_release_executions IS
    'Append-only hold release evidence. Rows do not enqueue or authorize purge execution.';
