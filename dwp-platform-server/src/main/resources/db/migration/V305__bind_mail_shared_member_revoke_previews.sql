CREATE TABLE mail_shared_member_revoke_previews (
    preview_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    shared_inbox_id UUID NOT NULL,
    member_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    member_version BIGINT NOT NULL,
    active_assignments INTEGER NOT NULL,
    open_drafts INTEGER NOT NULL,
    pending_commands INTEGER NOT NULL,
    provider_revocation_required BOOLEAN NOT NULL,
    snapshot_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_mail_member_revoke_preview_inbox
        FOREIGN KEY (shared_inbox_id)
        REFERENCES mail_shared_inboxes (shared_inbox_id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_member_revoke_preview_member
        FOREIGN KEY (member_id)
        REFERENCES mail_shared_inbox_access_grants (member_id) ON DELETE CASCADE,
    CONSTRAINT ck_mail_member_revoke_preview_version CHECK (member_version >= 0),
    CONSTRAINT ck_mail_member_revoke_preview_counts CHECK (
        active_assignments >= 0 AND open_drafts >= 0 AND pending_commands >= 0),
    CONSTRAINT ck_mail_member_revoke_preview_fingerprint
        CHECK (snapshot_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_member_revoke_preview_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_mail_member_revoke_preview_lookup
    ON mail_shared_member_revoke_previews (
        tenant_id, shared_inbox_id, member_id, actor_user_id, created_at DESC);

COMMENT ON TABLE mail_shared_member_revoke_previews IS
    'Actor-bound, expiring member-specific impact evidence required by shared inbox revoke.';

ALTER TABLE mail_purge_jobs
    ADD COLUMN candidate_thread_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN current_step VARCHAR(48) NOT NULL DEFAULT 'ACCEPTED',
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lease_owner VARCHAR(240),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE mail_purge_jobs
    ADD CONSTRAINT ck_mail_purge_job_candidate_threads
        CHECK (jsonb_typeof(candidate_thread_ids) = 'array'),
    ADD CONSTRAINT ck_mail_purge_job_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_mail_purge_job_lease CHECK (
        (job_state = 'RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (job_state <> 'RUNNING' AND lease_owner IS NULL AND lease_expires_at IS NULL));

CREATE INDEX idx_mail_purge_job_claim
    ON mail_purge_jobs (job_state, lease_expires_at, started_at)
    WHERE job_state IN ('ACCEPTED', 'RUNNING', 'PARTIAL', 'UNKNOWN');

COMMENT ON COLUMN mail_purge_jobs.candidate_thread_ids IS
    'Immutable approved local candidate identity set captured when execution is accepted.';
COMMENT ON COLUMN mail_purge_jobs.current_step IS
    'Last durable purge worker checkpoint; external steps without evidence remain SKIPPED or UNKNOWN.';

CREATE TABLE mail_purge_candidate_snapshot_rows (
    candidate_snapshot_id UUID NOT NULL
        REFERENCES mail_purge_previews(candidate_snapshot_id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL,
    thread_id UUID NOT NULL,
    account_id UUID NOT NULL,
    message_count INTEGER NOT NULL,
    attachment_count INTEGER NOT NULL,
    draft_count INTEGER NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    held BOOLEAN NOT NULL,
    hold_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (candidate_snapshot_id, thread_id),
    CONSTRAINT ck_mail_purge_candidate_counts CHECK (
        message_count >= 0 AND attachment_count >= 0 AND draft_count >= 0),
    CONSTRAINT ck_mail_purge_candidate_hold_ids CHECK (jsonb_typeof(hold_ids) = 'array'),
    CONSTRAINT ck_mail_purge_candidate_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX idx_mail_purge_candidate_tenant
    ON mail_purge_candidate_snapshot_rows (tenant_id, candidate_snapshot_id, held, thread_id);

CREATE FUNCTION reject_mail_purge_candidate_snapshot_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Mail purge candidate snapshots are append-only';
END;
$$;

CREATE TRIGGER trg_mail_purge_candidate_snapshot_immutable
    BEFORE UPDATE OR DELETE ON mail_purge_candidate_snapshot_rows
    FOR EACH ROW EXECUTE FUNCTION reject_mail_purge_candidate_snapshot_mutation();

COMMENT ON TABLE mail_purge_candidate_snapshot_rows IS
    'Append-only per-thread evidence for the exact approved purge candidate and held sets.';

CREATE TABLE mail_purge_deleted_candidate_receipts (
    job_id UUID NOT NULL REFERENCES mail_purge_jobs(job_id) ON DELETE RESTRICT,
    candidate_snapshot_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    thread_id UUID NOT NULL,
    message_count INTEGER NOT NULL,
    attachment_count INTEGER NOT NULL,
    draft_count INTEGER NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, thread_id),
    CONSTRAINT fk_mail_purge_deleted_candidate_snapshot
        FOREIGN KEY (candidate_snapshot_id, thread_id)
        REFERENCES mail_purge_candidate_snapshot_rows (
            candidate_snapshot_id, thread_id) ON DELETE RESTRICT,
    CONSTRAINT ck_mail_purge_deleted_candidate_counts CHECK (
        message_count >= 0 AND attachment_count >= 0 AND draft_count >= 0)
);

CREATE INDEX idx_mail_purge_deleted_candidate_snapshot
    ON mail_purge_deleted_candidate_receipts (
        tenant_id, candidate_snapshot_id, job_id, thread_id);

CREATE TRIGGER trg_mail_purge_deleted_candidate_receipt_immutable
    BEFORE UPDATE OR DELETE ON mail_purge_deleted_candidate_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_mail_purge_candidate_snapshot_mutation();

COMMENT ON TABLE mail_purge_deleted_candidate_receipts IS
    'Append-only deletion tombstones committed atomically with each eligible thread delete so crash recovery cannot confuse restored or externally missing rows with completed work.';

CREATE UNIQUE INDEX uk_mail_purge_completion_domain_event
    ON mail_domain_events (tenant_id, aggregate_id, event_type)
    WHERE aggregate_type = 'MAIL_PURGE_JOB' AND event_type = 'mail.purge.completed';

ALTER TABLE mail_delivery_audit_exports
    DROP CONSTRAINT ck_mail_delivery_export_state,
    ADD COLUMN export_kind VARCHAR(24) NOT NULL DEFAULT 'DELIVERY_AUDIT',
    ADD COLUMN export_scope JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN policy_version BIGINT,
    ADD COLUMN required_approvals INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN request_fingerprint CHAR(64);

UPDATE mail_delivery_audit_exports
   SET export_state = 'FAILED'
 WHERE export_state IN ('ACCEPTED', 'RUNNING');

ALTER TABLE mail_delivery_audit_exports
    ADD CONSTRAINT ck_mail_evidence_export_state CHECK (
        export_state IN ('PENDING_APPROVAL', 'READY', 'FAILED', 'EXPIRED')),
    ADD CONSTRAINT ck_mail_evidence_export_kind CHECK (
        export_kind IN ('DELIVERY_AUDIT', 'RETENTION_EVIDENCE')),
    ADD CONSTRAINT ck_mail_evidence_export_scope CHECK (
        jsonb_typeof(export_scope) = 'object'),
    ADD CONSTRAINT ck_mail_evidence_export_policy_version CHECK (
        policy_version IS NULL OR policy_version >= 0),
    ADD CONSTRAINT ck_mail_evidence_export_required_approvals CHECK (
        required_approvals > 0),
    ADD CONSTRAINT ck_mail_evidence_export_request_fingerprint CHECK (
        request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');

CREATE TABLE mail_evidence_export_approvals (
    approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    export_id UUID NOT NULL
        REFERENCES mail_delivery_audit_exports(export_id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL,
    approver_user_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_evidence_export_approval_actor
        UNIQUE (tenant_id, export_id, approver_user_id),
    CONSTRAINT uk_mail_evidence_export_approval_command
        UNIQUE (tenant_id, approver_user_id, idempotency_key),
    CONSTRAINT ck_mail_evidence_export_approval_decision CHECK (decision = 'APPROVED'),
    CONSTRAINT ck_mail_evidence_export_approval_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_mail_evidence_export_approval_lookup
    ON mail_evidence_export_approvals (tenant_id, export_id, decided_at, approver_user_id);

COMMENT ON TABLE mail_evidence_export_approvals IS
    'Independent actor approval evidence required before a delivery or retention evidence snapshot may be downloaded.';
