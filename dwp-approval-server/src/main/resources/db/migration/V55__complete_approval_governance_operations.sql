ALTER TABLE apr_policy_automation_commands
    DROP CONSTRAINT apr_policy_automation_commands_operation_check;

ALTER TABLE apr_policy_automation_commands
    ADD CONSTRAINT apr_policy_automation_commands_operation_check CHECK (operation IN (
        'SAVE_CALENDAR', 'SAVE_CHANNEL', 'OBSERVE_CHANNEL',
        'SAVE_POLICY_DRAFT', 'PUBLISH_POLICY', 'REVIEW_DELEGATION',
        'CREATE_DELEGATION', 'UPDATE_DELEGATION', 'REVOKE_DELEGATION',
        'CANCEL_SCHEDULED_DELEGATION', 'KILL_SWITCH_DELEGATIONS',
        'SIMULATE_POLICY', 'REVIEW_POLICY', 'SET_POLICY_FREEZE',
        'EXPORT_POLICY'));

CREATE TABLE apr_policy_automation_simulations (
    simulation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    input_payload JSONB NOT NULL,
    result_payload JSONB NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    requested_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, simulation_id),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, revision_id)
        REFERENCES apr_policy_automation_revisions(
            tenant_id, resource_set_key, policy_id, revision_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (policy_version BETWEEN 1 AND 9007199254740991),
    CHECK (jsonb_typeof(input_payload) = 'object'
        AND jsonb_typeof(result_payload) = 'object'),
    CHECK (outcome IN ('PASS', 'WARNING', 'BLOCKED')),
    CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (requested_by > 0)
);

CREATE TABLE apr_policy_automation_reviews (
    review_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    reviewed_policy_version BIGINT NOT NULL,
    disposition VARCHAR(24) NOT NULL,
    review_comment VARCHAR(2000) NOT NULL,
    review_evidence_sha256 CHAR(64) NOT NULL,
    maker_user_id BIGINT NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_user_id BIGINT NOT NULL,
    editor_person_public_id UUID NOT NULL,
    checker_user_id BIGINT NOT NULL,
    checker_person_public_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, review_id),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, revision_id)
        REFERENCES apr_policy_automation_revisions(
            tenant_id, resource_set_key, policy_id, revision_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (reviewed_policy_version BETWEEN 1 AND 9007199254740991),
    CHECK (disposition IN ('APPROVED', 'CHANGES_REQUESTED', 'REJECTED')),
    CHECK (length(btrim(review_comment)) BETWEEN 10 AND 2000),
    CHECK (review_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (maker_user_id > 0 AND editor_user_id > 0 AND checker_user_id > 0),
    CHECK (checker_person_public_id <> maker_person_public_id
        AND checker_person_public_id <> editor_person_public_id)
);

CREATE UNIQUE INDEX uk_apr_policy_automation_approved_review
    ON apr_policy_automation_reviews(
        tenant_id, resource_set_key, policy_id, revision_id)
    WHERE disposition = 'APPROVED';

CREATE TABLE apr_policy_automation_freezes (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, resource_set_key, policy_id),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id)
        REFERENCES apr_policy_automation_heads(tenant_id, resource_set_key, policy_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    CHECK (changed_by > 0),
    CHECK (version BETWEEN 1 AND 9007199254740991)
);

CREATE TABLE apr_policy_automation_freeze_journal (
    freeze_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    policy_version BIGINT NOT NULL,
    freeze_version BIGINT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id)
        REFERENCES apr_policy_automation_heads(tenant_id, resource_set_key, policy_id),
    CHECK (policy_version BETWEEN 1 AND 9007199254740991),
    CHECK (freeze_version BETWEEN 1 AND 9007199254740991),
    CHECK (actor_user_id > 0)
);

CREATE TABLE apr_policy_automation_exports (
    export_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    manifest_payload JSONB NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    requested_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, export_id),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, revision_id)
        REFERENCES apr_policy_automation_revisions(
            tenant_id, resource_set_key, policy_id, revision_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (policy_version BETWEEN 1 AND 9007199254740991),
    CHECK (jsonb_typeof(manifest_payload) = 'object'),
    CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (requested_by > 0)
);

CREATE TABLE apr_incident_reports (
    report_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    incident_version BIGINT NOT NULL,
    report_format VARCHAR(24) NOT NULL,
    report_payload JSONB NOT NULL,
    report_sha256 CHAR(64) NOT NULL,
    generated_by BIGINT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, report_id),
    FOREIGN KEY (tenant_id, resource_set_key, incident_id)
        REFERENCES apr_incidents(tenant_id, resource_set_key, incident_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (incident_version BETWEEN 1 AND 9007199254740991),
    CHECK (report_format IN ('EVIDENCE_JSON')),
    CHECK (jsonb_typeof(report_payload) = 'object'),
    CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (generated_by > 0)
);

ALTER TABLE apr_incident_timeline
    DROP CONSTRAINT apr_incident_timeline_event_type_check;

ALTER TABLE apr_incident_timeline
    ADD CONSTRAINT apr_incident_timeline_event_type_check CHECK (event_type IN (
        'OPENED', 'STATUS_CHANGED', 'DIAGNOSTIC_ADDED', 'PLAN_CREATED',
        'PLAN_VALIDATED', 'STAGE_STARTED', 'STAGE_COMPLETED',
        'RECONCILED', 'POSTMORTEM_RECORDED', 'REPORT_CREATED'));

CREATE INDEX idx_apr_incident_reports_latest
    ON apr_incident_reports(
        tenant_id, resource_set_key, incident_id, generated_at DESC, report_id);

ALTER TABLE apr_incident_commands
    DROP CONSTRAINT apr_incident_commands_operation_check;

ALTER TABLE apr_incident_commands
    ADD CONSTRAINT apr_incident_commands_operation_check CHECK (operation IN (
        'OPEN_INCIDENT', 'CHANGE_STATUS', 'ADD_DIAGNOSTIC',
        'CREATE_PLAN', 'RECORD_DRY_RUN', 'START_STAGE',
        'COMPLETE_STAGE', 'RECONCILE_PLAN', 'RECORD_POSTMORTEM',
        'CREATE_REPORT'));

CREATE TABLE apr_audit_verification_receipts (
    verification_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    export_id UUID NOT NULL,
    export_version BIGINT NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    recomputed_sha256 CHAR(64) NOT NULL,
    result VARCHAR(24) NOT NULL,
    verified_by BIGINT NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, management_resource_set_key, verification_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, export_id)
        REFERENCES apr_audit_export_jobs(
            tenant_id, management_resource_set_key, export_id),
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (export_version >= 0),
    CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'
        AND recomputed_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (result IN ('VERIFIED', 'DIGEST_MISMATCH')),
    CHECK (verified_by > 0)
);

CREATE TABLE apr_deployment_canary_controls (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    promotion_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, management_resource_set_key, promotion_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, promotion_id)
        REFERENCES apr_deployment_promotions(
            tenant_id, management_resource_set_key, promotion_id),
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (state IN ('RUNNING', 'PAUSED')),
    CHECK (length(btrim(reason)) BETWEEN 10 AND 1000),
    CHECK (changed_by > 0 AND version BETWEEN 1 AND 9007199254740991)
);

CREATE TABLE apr_deployment_telemetry (
    telemetry_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    promotion_id UUID NOT NULL,
    promotion_version BIGINT NOT NULL,
    observed_outcome VARCHAR(20) NOT NULL,
    stable_weight SMALLINT NOT NULL,
    canary_weight SMALLINT NOT NULL,
    metric_payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    source_generated_at TIMESTAMPTZ NOT NULL,
    verification_reference VARCHAR(249) NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, management_resource_set_key, telemetry_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, promotion_id)
        REFERENCES apr_deployment_promotions(
            tenant_id, management_resource_set_key, promotion_id),
    CHECK (promotion_version >= 0),
    CHECK (observed_outcome IN ('HEALTHY', 'DEGRADED', 'UNKNOWN', 'FAILED')),
    CHECK (stable_weight BETWEEN 0 AND 100
        AND canary_weight BETWEEN 0 AND 100
        AND stable_weight + canary_weight = 100),
    CHECK (jsonb_typeof(metric_payload) = 'object'),
    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (verification_reference ~ '^verified:[0-9a-f]{64}$'),
    CHECK (recorded_by > 0)
);

CREATE INDEX idx_apr_deployment_telemetry_latest
    ON apr_deployment_telemetry(
        tenant_id, management_resource_set_key, promotion_id,
        source_generated_at DESC, telemetry_id);

CREATE FUNCTION reject_apr_governance_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval governance evidence is append-only';
END;
$$;

CREATE TRIGGER trg_apr_policy_review_append_only
    BEFORE UPDATE OR DELETE ON apr_policy_automation_reviews
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_policy_simulation_append_only
    BEFORE UPDATE OR DELETE ON apr_policy_automation_simulations
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_policy_export_append_only
    BEFORE UPDATE OR DELETE ON apr_policy_automation_exports
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_policy_freeze_journal_append_only
    BEFORE UPDATE OR DELETE ON apr_policy_automation_freeze_journal
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_incident_report_append_only
    BEFORE UPDATE OR DELETE ON apr_incident_reports
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_audit_verification_append_only
    BEFORE UPDATE OR DELETE ON apr_audit_verification_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_deployment_telemetry_append_only
    BEFORE UPDATE OR DELETE ON apr_deployment_telemetry
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

CREATE TRIGGER trg_apr_deployment_journal_append_only
    BEFORE UPDATE OR DELETE ON apr_deployment_journal
    FOR EACH ROW EXECUTE FUNCTION reject_apr_governance_evidence_mutation();

COMMENT ON TABLE apr_audit_verification_receipts IS
    'Durable recomputation receipts; VERIFIED asserts exact manifest bytes only.';

COMMENT ON TABLE apr_deployment_telemetry IS
    'Verified canary observations. Provider-side state is never inferred from this table.';
