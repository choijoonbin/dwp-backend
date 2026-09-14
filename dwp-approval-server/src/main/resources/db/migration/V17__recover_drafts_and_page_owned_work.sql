ALTER TABLE apr_requests
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT,
    ADD COLUMN deletion_reason VARCHAR(2000),
    ADD CONSTRAINT ck_apr_draft_deletion CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL AND deletion_reason IS NULL)
        OR (status = 'DRAFT' AND deleted_at IS NOT NULL
            AND deleted_by IS NOT NULL AND deletion_reason IS NOT NULL));

ALTER TABLE apr_request_payload_versions
    ADD COLUMN draft_snapshot JSONB,
    ADD CONSTRAINT ck_apr_draft_snapshot CHECK (
        draft_snapshot IS NULL OR jsonb_typeof(draft_snapshot) = 'object');

-- Only the current revision has provable request metadata during upgrade.
UPDATE apr_request_payload_versions history
   SET draft_snapshot = jsonb_build_object(
       'title', request.title, 'summary', request.summary,
       'priority', request.priority,
       'workflowId', workflow.workflow_id, 'formId', form.form_id)
  FROM apr_requests request
  JOIN apr_request_payloads payload
    ON payload.tenant_id = request.tenant_id AND payload.request_id = request.request_id
  JOIN apr_workflow_versions workflow
    ON workflow.tenant_id = request.tenant_id
   AND workflow.workflow_version_id = request.workflow_version_id
  JOIN apr_form_versions form
    ON form.tenant_id = request.tenant_id AND form.form_version_id = request.form_version_id
 WHERE history.tenant_id = request.tenant_id AND history.request_id = request.request_id
   AND history.revision_number = payload.schema_version
   AND history.payload_sha256 = payload.payload_sha256 AND history.payload = payload.payload
   AND request.status = 'DRAFT';

CREATE FUNCTION capture_approval_draft_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT jsonb_build_object(
               'title', request.title, 'summary', request.summary,
               'priority', request.priority,
               'workflowId', workflow.workflow_id, 'formId', form.form_id)
      INTO NEW.draft_snapshot
      FROM apr_requests request
      JOIN apr_workflow_versions workflow
        ON workflow.tenant_id = request.tenant_id
       AND workflow.workflow_version_id = request.workflow_version_id
      JOIN apr_form_versions form
        ON form.tenant_id = request.tenant_id AND form.form_version_id = request.form_version_id
      JOIN apr_request_payloads payload
        ON payload.tenant_id = request.tenant_id AND payload.request_id = request.request_id
       AND payload.schema_version = NEW.revision_number AND payload.payload_sha256 = NEW.payload_sha256
       AND payload.payload = NEW.payload
     WHERE request.tenant_id = NEW.tenant_id AND request.request_id = NEW.request_id
       AND request.status = 'DRAFT' AND request.deleted_at IS NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_apr_draft_snapshot BEFORE INSERT ON apr_request_payload_versions
    FOR EACH ROW EXECUTE FUNCTION capture_approval_draft_snapshot();

CREATE FUNCTION preserve_approval_draft_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.draft_snapshot IS NOT NULL THEN
        RAISE EXCEPTION 'Approval draft revision snapshot is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_draft_revision BEFORE UPDATE OR DELETE ON apr_request_payload_versions
    FOR EACH ROW EXECUTE FUNCTION preserve_approval_draft_revision();

CREATE TABLE apr_draft_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    request_id UUID,
    idempotency_key VARCHAR(120) NOT NULL,
    command_route VARCHAR(300) NOT NULL,
    command_type VARCHAR(20) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    source_revision INTEGER,
    result JSONB,
    result_state JSONB,
    correlation_id VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_apr_draft_command_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id),
    CONSTRAINT uk_apr_draft_command_key UNIQUE (tenant_id, actor_user_id, command_route, idempotency_key),
    CONSTRAINT ck_apr_draft_command_type CHECK (command_type IN ('CREATE', 'UPDATE', 'RECOVER', 'DELETE', 'RESTORE')),
    CONSTRAINT ck_apr_draft_command_version CHECK (expected_version >= 0),
    CONSTRAINT ck_apr_draft_command_revision CHECK (
        (command_type = 'RECOVER' AND source_revision > 0)
        OR (command_type <> 'RECOVER' AND source_revision IS NULL)),
    CONSTRAINT ck_apr_draft_command_result CHECK (
        (result IS NULL AND result_state IS NULL AND completed_at IS NULL)
        OR (result IS NOT NULL AND jsonb_typeof(result) = 'object'
            AND result_state IS NOT NULL AND jsonb_typeof(result_state) = 'object'
            AND completed_at IS NOT NULL AND request_id IS NOT NULL))
);

CREATE FUNCTION preserve_approval_draft_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.result IS NOT NULL
       OR (NEW.tenant_id, NEW.actor_user_id, NEW.command_route, NEW.idempotency_key,
           NEW.fingerprint, NEW.expected_version, NEW.source_revision, NEW.command_type)
          IS DISTINCT FROM
          (OLD.tenant_id, OLD.actor_user_id, OLD.command_route, OLD.idempotency_key,
           OLD.fingerprint, OLD.expected_version, OLD.source_revision, OLD.command_type) THEN
        RAISE EXCEPTION 'Approval draft command receipt is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_apr_draft_receipt BEFORE UPDATE OR DELETE ON apr_draft_commands
    FOR EACH ROW EXECUTE FUNCTION preserve_approval_draft_receipt();

CREATE INDEX idx_apr_owned_request_page
    ON apr_requests (tenant_id, requester_user_id, updated_at DESC, request_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_apr_deleted_draft_page
    ON apr_requests (tenant_id, requester_user_id, deleted_at DESC, request_id)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_apr_own_completed_task_page
    ON apr_tasks (tenant_id, decision_actor_user_id, completed_at DESC, task_id)
    WHERE status IN ('APPROVED', 'REJECTED');

COMMENT ON TABLE apr_draft_commands IS
    'Atomic owner-checked draft recovery/delete/restore results; no physical purge or provider readiness implication.';
