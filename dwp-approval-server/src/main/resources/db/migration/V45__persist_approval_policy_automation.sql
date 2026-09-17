CREATE TABLE apr_business_calendars (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    calendar_id UUID NOT NULL,
    calendar_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    work_week JSONB NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, calendar_id),
    UNIQUE (tenant_id, resource_set_key, calendar_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (calendar_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (btrim(display_name) <> '' AND btrim(time_zone) <> ''),
    CHECK (jsonb_typeof(work_week) = 'object'),
    CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0)
);

CREATE TABLE apr_business_calendar_holidays (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    calendar_id UUID NOT NULL,
    holiday_date DATE NOT NULL,
    label VARCHAR(160) NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, calendar_id, holiday_date),
    FOREIGN KEY (tenant_id, resource_set_key, calendar_id)
        REFERENCES apr_business_calendars(tenant_id, resource_set_key, calendar_id),
    CHECK (btrim(label) <> '')
);

CREATE TABLE apr_business_calendar_exceptions (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    calendar_id UUID NOT NULL,
    exception_date DATE NOT NULL,
    closed BOOLEAN NOT NULL,
    opens_at TIME,
    closes_at TIME,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, calendar_id, exception_date),
    FOREIGN KEY (tenant_id, resource_set_key, calendar_id)
        REFERENCES apr_business_calendars(tenant_id, resource_set_key, calendar_id),
    CHECK (btrim(reason) <> ''),
    CHECK ((closed AND opens_at IS NULL AND closes_at IS NULL)
        OR (NOT closed AND opens_at IS NOT NULL AND closes_at IS NOT NULL
            AND closes_at > opens_at))
);

CREATE TABLE apr_notification_channels (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    channel_id UUID NOT NULL,
    channel_key VARCHAR(100) NOT NULL,
    channel_type VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    readiness_state VARCHAR(20) NOT NULL DEFAULT 'NOT_CONFIGURED',
    source_revision VARCHAR(240),
    evidence_sha256 CHAR(64),
    observed_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    verification_reference VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, channel_id),
    UNIQUE (tenant_id, resource_set_key, channel_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (channel_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (channel_type IN ('IN_APP', 'EMAIL', 'PUSH', 'TEAMS', 'SLACK', 'WEBHOOK')),
    CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CHECK (readiness_state IN (
        'NOT_CONFIGURED', 'READY', 'DEGRADED', 'UNAVAILABLE', 'STALE', 'UNKNOWN')),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0),
    CHECK (
        (readiness_state = 'NOT_CONFIGURED'
            AND source_revision IS NULL AND evidence_sha256 IS NULL
            AND observed_at IS NULL AND valid_until IS NULL
            AND verification_reference IS NULL)
        OR
        (readiness_state <> 'NOT_CONFIGURED'
            AND source_revision IS NOT NULL
            AND btrim(source_revision) <> ''
            AND evidence_sha256 IS NOT NULL
            AND evidence_sha256 ~ '^[0-9a-f]{64}$'
            AND observed_at IS NOT NULL AND valid_until IS NOT NULL
            AND valid_until > observed_at
            AND ((readiness_state = 'READY'
                    AND verification_reference IS NOT NULL
                    AND verification_reference ~ '^verified:[0-9a-f]{64}$')
                OR (readiness_state <> 'READY'
                    AND verification_reference IS NULL))))
);

CREATE TABLE apr_notification_channel_observations (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    channel_id UUID NOT NULL,
    observation_id UUID NOT NULL,
    channel_version BIGINT NOT NULL,
    readiness_state VARCHAR(20) NOT NULL,
    source_revision VARCHAR(240) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    verification_reference VARCHAR(80),
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, channel_id, observation_id),
    FOREIGN KEY (tenant_id, resource_set_key, channel_id)
        REFERENCES apr_notification_channels(tenant_id, resource_set_key, channel_id),
    CHECK (channel_version BETWEEN 1 AND 9007199254740991),
    CHECK (readiness_state IN ('READY', 'DEGRADED', 'UNAVAILABLE', 'STALE', 'UNKNOWN')),
    CHECK (btrim(source_revision) <> ''),
    CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((readiness_state = 'READY'
            AND verification_reference IS NOT NULL
            AND verification_reference ~ '^verified:[0-9a-f]{64}$')
        OR (readiness_state <> 'READY' AND verification_reference IS NULL)),
    CHECK (valid_until > observed_at AND recorded_by > 0)
);

CREATE TABLE apr_policy_automation_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    policy_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 1,
    draft_revision_id UUID,
    published_revision_id UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, policy_id),
    UNIQUE (tenant_id, resource_set_key, policy_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (policy_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (btrim(display_name) <> ''),
    CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0),
    CHECK (draft_revision_id IS NOT NULL OR published_revision_id IS NOT NULL),
    CHECK (draft_revision_id IS NULL OR published_revision_id IS NULL
        OR draft_revision_id <> published_revision_id)
);

CREATE TABLE apr_policy_automation_revisions (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    calendar_id UUID NOT NULL,
    reminders JSONB NOT NULL,
    escalations JSONB NOT NULL,
    channel_ids JSONB NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    definition_sha256 CHAR(64) NOT NULL,
    maker_user_id BIGINT NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_user_id BIGINT NOT NULL,
    editor_person_public_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, policy_id, revision_id),
    UNIQUE (tenant_id, resource_set_key, policy_id, revision_number),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id)
        REFERENCES apr_policy_automation_heads(tenant_id, resource_set_key, policy_id),
    FOREIGN KEY (tenant_id, resource_set_key, calendar_id)
        REFERENCES apr_business_calendars(tenant_id, resource_set_key, calendar_id),
    CHECK (revision_number BETWEEN 1 AND 9007199254740991),
    CHECK (jsonb_typeof(reminders) = 'array'
        AND jsonb_typeof(escalations) = 'array'
        AND jsonb_typeof(channel_ids) = 'array'),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (maker_user_id > 0 AND editor_user_id > 0)
);

ALTER TABLE apr_policy_automation_heads
    ADD CONSTRAINT fk_apr_policy_automation_draft
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, draft_revision_id)
    REFERENCES apr_policy_automation_revisions(
        tenant_id, resource_set_key, policy_id, revision_id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE apr_policy_automation_heads
    ADD CONSTRAINT fk_apr_policy_automation_published
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, published_revision_id)
    REFERENCES apr_policy_automation_revisions(
        tenant_id, resource_set_key, policy_id, revision_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE apr_policy_automation_publications (
    publication_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    policy_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_person_public_id UUID NOT NULL,
    checker_user_id BIGINT NOT NULL,
    checker_person_public_id UUID NOT NULL,
    review_evidence_sha256 CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, policy_id, revision_id),
    FOREIGN KEY (tenant_id, resource_set_key, policy_id, revision_id)
        REFERENCES apr_policy_automation_revisions(
            tenant_id, resource_set_key, policy_id, revision_id),
    CHECK (checker_user_id > 0),
    CHECK (checker_person_public_id <> maker_person_public_id
        AND checker_person_public_id <> editor_person_public_id),
    CHECK (review_evidence_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE apr_policy_automation_commands (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    target_id UUID,
    command_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    result_type VARCHAR(240),
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (
        tenant_id, resource_set_key, actor_user_id, operation, idempotency_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (actor_user_id > 0),
    CHECK (operation IN (
        'SAVE_CALENDAR', 'SAVE_CHANNEL', 'OBSERVE_CHANNEL',
        'SAVE_POLICY_DRAFT', 'PUBLISH_POLICY', 'REVIEW_DELEGATION')),
    CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (command_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('UNKNOWN', 'SUCCEEDED')),
    CHECK ((status = 'UNKNOWN' AND result_type IS NULL
        AND result_payload IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND result_type IS NOT NULL
        AND btrim(result_type) <> '' AND result_payload IS NOT NULL
        AND jsonb_typeof(result_payload) = 'object' AND completed_at IS NOT NULL))
);

ALTER TABLE apr_delegations
    ADD COLUMN management_resource_set_key VARCHAR(80);

UPDATE apr_delegations delegation
   SET management_resource_set_key = workflow.management_resource_set_key
  FROM apr_workflow_definitions workflow
 WHERE delegation.scope_type = 'WORKFLOW'
   AND workflow.tenant_id = delegation.tenant_id
   AND workflow.workflow_id = delegation.workflow_id
   AND workflow.workflow_key = delegation.workflow_key;

UPDATE apr_delegations
   SET management_resource_set_key = 'RS_APPROVALS'
 WHERE scope_type = 'ALL';

ALTER TABLE apr_delegations
    ALTER COLUMN management_resource_set_key SET NOT NULL,
    ADD CONSTRAINT ck_apr_delegation_management_scope CHECK (
        management_resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    ADD CONSTRAINT uk_apr_delegation_tenant_identity
        UNIQUE (tenant_id, delegation_id),
    ADD CONSTRAINT uk_apr_delegation_scoped_identity
        UNIQUE (tenant_id, management_resource_set_key, delegation_id);

CREATE FUNCTION bind_apr_delegation_management_scope()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    resolved_scope VARCHAR(80);
BEGIN
    IF NEW.scope_type = 'ALL' THEN
        resolved_scope := 'RS_APPROVALS';
    ELSE
        SELECT workflow.management_resource_set_key
          INTO resolved_scope
          FROM apr_workflow_definitions workflow
         WHERE workflow.tenant_id = NEW.tenant_id
           AND workflow.workflow_id = NEW.workflow_id
           AND workflow.workflow_key = NEW.workflow_key;
        IF resolved_scope IS NULL THEN
            RAISE EXCEPTION 'Approval delegation workflow scope is unavailable'
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    END IF;
    IF NEW.management_resource_set_key IS NOT NULL
       AND NEW.management_resource_set_key <> resolved_scope THEN
        RAISE EXCEPTION 'Approval delegation management scope is not exact'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    NEW.management_resource_set_key := resolved_scope;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bind_apr_delegation_management_scope
    BEFORE INSERT OR UPDATE OF tenant_id, scope_type, workflow_id, workflow_key,
        management_resource_set_key
    ON apr_delegations FOR EACH ROW
    EXECUTE FUNCTION bind_apr_delegation_management_scope();

CREATE TABLE apr_delegation_governance_reviews (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    delegation_id UUID NOT NULL,
    review_id UUID NOT NULL,
    delegation_version BIGINT NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    compliance_state VARCHAR(24) NOT NULL,
    scope_binding_truth VARCHAR(16) NOT NULL,
    time_window_truth VARCHAR(16) NOT NULL,
    no_sub_delegation_truth VARCHAR(16) NOT NULL,
    identity_separation_truth VARCHAR(16) NOT NULL,
    role_snapshot_truth VARCHAR(16) NOT NULL,
    role_sod_truth VARCHAR(16) NOT NULL,
    findings JSONB NOT NULL,
    review_evidence_sha256 CHAR(64) NOT NULL,
    reviewed_by BIGINT NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, delegation_id, review_id),
    FOREIGN KEY (tenant_id, resource_set_key, delegation_id)
        REFERENCES apr_delegations(
            tenant_id, management_resource_set_key, delegation_id),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (delegation_version BETWEEN 0 AND 9007199254740991),
    CHECK (disposition IN ('ACKNOWLEDGED_FINDINGS', 'REMEDIATION_REQUESTED')),
    CHECK (compliance_state IN ('BLOCKED', 'EVIDENCE_REQUIRED')),
    CHECK (scope_binding_truth IN ('VERIFIED', 'VIOLATED', 'NOT_VERIFIED')),
    CHECK (time_window_truth IN ('VERIFIED', 'VIOLATED', 'NOT_VERIFIED')),
    CHECK (no_sub_delegation_truth IN ('VERIFIED', 'VIOLATED', 'NOT_VERIFIED')),
    CHECK (identity_separation_truth IN ('VERIFIED', 'VIOLATED', 'NOT_VERIFIED')),
    CHECK (role_snapshot_truth IN ('VERIFIED', 'VIOLATED', 'NOT_VERIFIED')),
    CHECK (role_sod_truth IN ('VERIFIED', 'VIOLATED', 'NOT_VERIFIED')),
    CHECK (jsonb_typeof(findings) = 'array'),
    CHECK (review_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (reviewed_by > 0)
);

CREATE INDEX idx_apr_delegation_governance_review_latest
    ON apr_delegation_governance_reviews (
        tenant_id, resource_set_key, delegation_id, reviewed_at DESC, review_id);

CREATE FUNCTION validate_apr_delegation_governance_review_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    current_delegation_version BIGINT;
BEGIN
    SELECT delegation.version
      INTO current_delegation_version
      FROM apr_delegations delegation
     WHERE delegation.tenant_id = NEW.tenant_id
       AND delegation.management_resource_set_key = NEW.resource_set_key
       AND delegation.delegation_id = NEW.delegation_id
       FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Delegation governance review is not bound to the exact management scope'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF current_delegation_version IS DISTINCT FROM NEW.delegation_version THEN
        RAISE EXCEPTION 'Delegation governance review version is stale'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_apr_delegation_governance_review_binding
    BEFORE INSERT ON apr_delegation_governance_reviews
    FOR EACH ROW EXECUTE FUNCTION validate_apr_delegation_governance_review_binding();

CREATE INDEX idx_apr_policy_automation_effective
    ON apr_policy_automation_revisions (
        tenant_id, resource_set_key, effective_from, effective_to, policy_id);

CREATE FUNCTION reject_apr_policy_automation_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval policy automation evidence is append-only'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_apr_notification_observation_append_only
    BEFORE UPDATE OR DELETE ON apr_notification_channel_observations
    FOR EACH ROW EXECUTE FUNCTION reject_apr_policy_automation_evidence_mutation();

CREATE TRIGGER trg_apr_policy_publication_append_only
    BEFORE UPDATE OR DELETE ON apr_policy_automation_publications
    FOR EACH ROW EXECUTE FUNCTION reject_apr_policy_automation_evidence_mutation();

CREATE TRIGGER trg_apr_delegation_governance_review_append_only
    BEFORE UPDATE OR DELETE ON apr_delegation_governance_reviews
    FOR EACH ROW EXECUTE FUNCTION reject_apr_policy_automation_evidence_mutation();

COMMENT ON TABLE apr_policy_automation_heads IS
    'Maker-checker governed reminders and escalations bound to business calendars and observed channels.';
COMMENT ON TABLE apr_notification_channels IS
    'Notification channel readiness is explicit, expiring evidence and never inferred from configuration.';
COMMENT ON TABLE apr_delegation_governance_reviews IS
    'Version-fenced reviewer dispositions over current delegation facts; records do not assert external role-SoD evidence.';
