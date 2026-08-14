CREATE TABLE apr_tenants (
    tenant_id BIGINT PRIMARY KEY,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    default_time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_apr_tenant_state CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE apr_workflow_definitions (
    workflow_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    workflow_key VARCHAR(100) NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    description_ko VARCHAR(1000) NOT NULL,
    description_en VARCHAR(1000) NOT NULL,
    category VARCHAR(40) NOT NULL,
    data_classification VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_version INTEGER NOT NULL DEFAULT 1,
    sla_minutes INTEGER NOT NULL DEFAULT 1440,
    allow_self_approval BOOLEAN NOT NULL DEFAULT FALSE,
    owner_group_ref VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_workflow_key UNIQUE (tenant_id, workflow_key),
    CONSTRAINT uk_apr_workflow_scope UNIQUE (tenant_id, workflow_id),
    CONSTRAINT ck_apr_workflow_category CHECK (
        category IN ('FINANCE', 'PEOPLE', 'PROCUREMENT', 'ACCESS', 'GENERAL')),
    CONSTRAINT ck_apr_workflow_classification CHECK (
        data_classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_apr_workflow_state CHECK (
        lifecycle_state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_apr_workflow_version CHECK (current_version > 0),
    CONSTRAINT ck_apr_workflow_sla CHECK (sla_minutes BETWEEN 15 AND 525600)
);

CREATE TABLE apr_workflow_versions (
    workflow_version_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    workflow_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    definition JSONB NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT uk_apr_workflow_version UNIQUE (tenant_id, workflow_id, version_number),
    CONSTRAINT uk_apr_workflow_version_scope UNIQUE (tenant_id, workflow_version_id),
    CONSTRAINT fk_apr_workflow_version_definition FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES apr_workflow_definitions(tenant_id, workflow_id),
    CONSTRAINT ck_apr_workflow_version_definition CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT ck_apr_workflow_version_state CHECK (
        lifecycle_state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_apr_workflow_version_number CHECK (version_number > 0),
    CONSTRAINT ck_apr_workflow_version_effective CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);

CREATE TABLE apr_forms (
    form_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    form_key VARCHAR(100) NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_version INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_form_key UNIQUE (tenant_id, form_key),
    CONSTRAINT uk_apr_form_scope UNIQUE (tenant_id, form_id),
    CONSTRAINT ck_apr_form_state CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_apr_form_version CHECK (current_version > 0)
);

CREATE TABLE apr_form_versions (
    form_version_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    form_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    schema_payload JSONB NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT uk_apr_form_version UNIQUE (tenant_id, form_id, version_number),
    CONSTRAINT uk_apr_form_version_scope UNIQUE (tenant_id, form_version_id),
    CONSTRAINT fk_apr_form_version_form FOREIGN KEY (tenant_id, form_id)
        REFERENCES apr_forms(tenant_id, form_id),
    CONSTRAINT ck_apr_form_version_schema CHECK (jsonb_typeof(schema_payload) = 'object'),
    CONSTRAINT ck_apr_form_version_state CHECK (
        lifecycle_state IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

CREATE TABLE apr_requests (
    request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    request_number VARCHAR(80) NOT NULL,
    workflow_version_id UUID NOT NULL,
    form_version_id UUID NOT NULL,
    title VARCHAR(300) NOT NULL,
    summary VARCHAR(2000) NOT NULL DEFAULT '',
    requester_user_id BIGINT NOT NULL,
    requester_person_public_id UUID,
    requester_name VARCHAR(200),
    requester_org_name VARCHAR(200),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    data_classification VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    submitted_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    source_system VARCHAR(80) NOT NULL DEFAULT 'DWP',
    source_reference VARCHAR(160),
    reference_seed_key VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_request_number UNIQUE (tenant_id, request_number),
    CONSTRAINT uk_apr_request_scope UNIQUE (tenant_id, request_id),
    CONSTRAINT uk_apr_request_reference_seed UNIQUE (tenant_id, reference_seed_key),
    CONSTRAINT fk_apr_request_workflow_version FOREIGN KEY (tenant_id, workflow_version_id)
        REFERENCES apr_workflow_versions(tenant_id, workflow_version_id),
    CONSTRAINT fk_apr_request_form_version FOREIGN KEY (tenant_id, form_version_id)
        REFERENCES apr_form_versions(tenant_id, form_version_id),
    CONSTRAINT ck_apr_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO', 'APPROVED',
        'REJECTED', 'WITHDRAWN', 'CANCELLED')),
    CONSTRAINT ck_apr_request_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_apr_request_classification CHECK (
        data_classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_apr_request_temporal CHECK (
        completed_at IS NULL OR submitted_at IS NULL OR completed_at >= submitted_at)
);

CREATE TABLE apr_request_payloads (
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload_sha256 CHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT fk_apr_request_payload_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT ck_apr_request_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_apr_request_payload_schema CHECK (schema_version > 0)
);

CREATE TABLE apr_steps (
    step_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_key VARCHAR(100) NOT NULL,
    step_name VARCHAR(200) NOT NULL,
    sequence_number INTEGER NOT NULL,
    approval_mode VARCHAR(20) NOT NULL DEFAULT 'ANY',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_apr_step_sequence UNIQUE (tenant_id, request_id, sequence_number),
    CONSTRAINT uk_apr_step_scope UNIQUE (tenant_id, step_id),
    CONSTRAINT fk_apr_step_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT ck_apr_step_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_apr_step_mode CHECK (approval_mode IN ('ANY', 'ALL', 'SEQUENTIAL')),
    CONSTRAINT ck_apr_step_status CHECK (status IN (
        'WAITING', 'PENDING', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED'))
);

CREATE TABLE apr_tasks (
    task_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    step_id UUID NOT NULL,
    assignee_user_id BIGINT,
    assignee_person_public_id UUID,
    candidate_role VARCHAR(80),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    risk_score INTEGER NOT NULL DEFAULT 0,
    decision_reason VARCHAR(2000),
    claimed_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_apr_task_scope UNIQUE (tenant_id, task_id),
    CONSTRAINT fk_apr_task_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT fk_apr_task_step FOREIGN KEY (tenant_id, step_id)
        REFERENCES apr_steps(tenant_id, step_id) ON DELETE CASCADE,
    CONSTRAINT ck_apr_task_candidate CHECK (
        assignee_user_id IS NOT NULL OR candidate_role IS NOT NULL),
    CONSTRAINT ck_apr_task_status CHECK (status IN (
        'PENDING', 'CLAIMED', 'APPROVED', 'REJECTED', 'INFO_REQUESTED',
        'REASSIGNED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_apr_task_risk CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE TABLE apr_request_events (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(160),
    outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    message VARCHAR(2000),
    correlation_id VARCHAR(160),
    event_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_apr_event_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT ck_apr_event_actor CHECK (actor_type IN ('USER', 'SYSTEM', 'AGENT', 'SERVICE')),
    CONSTRAINT ck_apr_event_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT ck_apr_event_data CHECK (jsonb_typeof(event_data) = 'object')
);

CREATE TABLE apr_delegations (
    delegation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    delegator_user_id BIGINT NOT NULL,
    delegate_user_id BIGINT NOT NULL,
    scope_type VARCHAR(24) NOT NULL DEFAULT 'ALL',
    workflow_key VARCHAR(100),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_apr_delegation_identity CHECK (delegator_user_id <> delegate_user_id),
    CONSTRAINT ck_apr_delegation_scope CHECK (scope_type IN ('ALL', 'WORKFLOW')),
    CONSTRAINT ck_apr_delegation_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_apr_delegation_state CHECK (
        lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE TABLE apr_signature_providers (
    provider_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    provider_key VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    provider_type VARCHAR(30) NOT NULL,
    lifecycle_state VARCHAR(30) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED',
    capability_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    credential_reference VARCHAR(240),
    last_health_checked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_signature_provider UNIQUE (tenant_id, provider_key),
    CONSTRAINT ck_apr_signature_provider_type CHECK (
        provider_type IN ('INTERNAL_ATTESTATION', 'DOCUSIGN', 'ADOBE_SIGN', 'CUSTOM')),
    CONSTRAINT ck_apr_signature_provider_state CHECK (lifecycle_state IN (
        'ACTIVE', 'DISABLED', 'CONFIGURATION_REQUIRED', 'DEGRADED')),
    CONSTRAINT ck_apr_signature_provider_metadata CHECK (
        jsonb_typeof(capability_metadata) = 'object')
);

CREATE TABLE apr_policy_rules (
    policy_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    policy_key VARCHAR(100) NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    policy_type VARCHAR(30) NOT NULL,
    enforcement_mode VARCHAR(20) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    rule_payload JSONB NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_apr_policy_key UNIQUE (tenant_id, policy_key),
    CONSTRAINT ck_apr_policy_type CHECK (
        policy_type IN ('IDENTITY', 'DECISION', 'SLA', 'DATA', 'SEGREGATION_OF_DUTIES')),
    CONSTRAINT ck_apr_policy_mode CHECK (enforcement_mode IN ('BLOCK', 'WARN', 'MONITOR')),
    CONSTRAINT ck_apr_policy_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_apr_policy_payload CHECK (jsonb_typeof(rule_payload) = 'object'),
    CONSTRAINT ck_apr_policy_state CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED'))
);

CREATE TABLE apr_integration_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    request_id UUID,
    event_type VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(240),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_apr_integration_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_apr_integration_status CHECK (
        status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_apr_integration_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_apr_integration_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id)
);

CREATE TABLE sys_audit_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(255),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_apr_audit_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_apr_audit_outbox_status CHECK (
        status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_apr_audit_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_apr_request_requester
    ON apr_requests (tenant_id, requester_user_id, status, updated_at DESC);
CREATE INDEX idx_apr_request_status
    ON apr_requests (tenant_id, status, due_at, updated_at DESC);
CREATE INDEX idx_apr_task_assignee
    ON apr_tasks (tenant_id, assignee_user_id, status, due_at);
CREATE INDEX idx_apr_task_candidate
    ON apr_tasks (tenant_id, candidate_role, status, due_at)
    WHERE assignee_user_id IS NULL;
CREATE INDEX idx_apr_event_timeline
    ON apr_request_events (tenant_id, request_id, occurred_at DESC);
CREATE INDEX idx_apr_delegation_active
    ON apr_delegations (tenant_id, delegator_user_id, lifecycle_state, starts_at, ends_at);
CREATE INDEX idx_apr_integration_delivery
    ON apr_integration_outbox (status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');
CREATE INDEX idx_apr_audit_delivery
    ON sys_audit_outbox (status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');

CREATE OR REPLACE FUNCTION seed_approval_tenant(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO apr_tenants (tenant_id)
    VALUES (p_tenant_id)
    ON CONFLICT (tenant_id) DO UPDATE SET
        lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

    INSERT INTO apr_workflow_definitions (
        workflow_id, tenant_id, workflow_key, name_ko, name_en,
        description_ko, description_en, category, data_classification,
        lifecycle_state, current_version, sla_minutes, owner_group_ref,
        created_by, updated_by)
    SELECT md5('approval-workflow:' || p_tenant_id || ':' || seed.workflow_key)::uuid,
           p_tenant_id, seed.workflow_key, seed.name_ko, seed.name_en,
           seed.description_ko, seed.description_en, seed.category,
           seed.classification, seed.lifecycle_state, 1, seed.sla_minutes,
           seed.owner_group, 1, 1
      FROM (VALUES
        ('CAPEX_PURCHASE', '투자·구매 승인', 'Capital purchase approval',
         '예산, 구매 사유와 리스크를 함께 검토합니다.',
         'Review budget, business rationale, and risk together.',
         'FINANCE', 'CONFIDENTIAL', 'PUBLISHED', 1440, 'FINANCE_APPROVERS'),
        ('ACCESS_EXCEPTION', '접근 예외 승인', 'Access exception approval',
         '기간 제한 접근 예외와 보완 통제를 검토합니다.',
         'Review time-bound access exceptions and compensating controls.',
         'ACCESS', 'RESTRICTED', 'PUBLISHED', 240, 'SECURITY_APPROVERS'),
        ('SUPPLIER_ONBOARDING', '협력사 등록 승인', 'Supplier onboarding approval',
         '계약, 보안 및 지급 준비 상태를 순차 검토합니다.',
         'Review contract, security, and payment readiness in sequence.',
         'PROCUREMENT', 'CONFIDENTIAL', 'PUBLISHED', 2880, 'PROCUREMENT_APPROVERS'),
        ('GENERAL_DECISION', '일반 의사결정', 'General decision request',
         '조직별 경량 결재를 위한 확장 가능한 기본 절차입니다.',
         'An extensible baseline for lightweight organizational decisions.',
         'GENERAL', 'INTERNAL', 'DRAFT', 1440, 'APPROVAL_OPERATORS')
      ) seed(workflow_key, name_ko, name_en, description_ko, description_en,
             category, classification, lifecycle_state, sla_minutes, owner_group)
    ON CONFLICT (tenant_id, workflow_key) DO NOTHING;

    INSERT INTO apr_workflow_versions (
        workflow_version_id, tenant_id, workflow_id, version_number,
        definition, definition_sha256, lifecycle_state, effective_from,
        published_at, published_by, created_by)
    SELECT md5('approval-workflow-version:' || p_tenant_id || ':' || definition.workflow_key || ':1')::uuid,
           p_tenant_id, definition.workflow_id, 1,
           jsonb_build_object(
               'schemaVersion', 1,
               'steps', jsonb_build_array(jsonb_build_object(
                   'key', 'PRIMARY_REVIEW', 'mode', 'ANY',
                   'candidateRole', definition.owner_group_ref,
                   'slaMinutes', definition.sla_minutes)),
               'guardrails', jsonb_build_object(
                   'selfApproval', false, 'requireReasonOnReject', true,
                   'optimisticConcurrency', true)),
           encode(sha256(convert_to(definition.workflow_key || ':1', 'UTF8')), 'hex'),
           definition.lifecycle_state,
           CASE WHEN definition.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
           CASE WHEN definition.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
           CASE WHEN definition.lifecycle_state = 'PUBLISHED' THEN 1 ELSE NULL END,
           1
      FROM apr_workflow_definitions definition
     WHERE definition.tenant_id = p_tenant_id
    ON CONFLICT (tenant_id, workflow_id, version_number) DO NOTHING;

    INSERT INTO apr_forms (
        form_id, tenant_id, form_key, name_ko, name_en,
        lifecycle_state, current_version, created_by, updated_by)
    SELECT md5('approval-form:' || p_tenant_id || ':' || definition.workflow_key)::uuid,
           p_tenant_id, definition.workflow_key || '_FORM',
           definition.name_ko || ' 양식', definition.name_en || ' form',
           definition.lifecycle_state, 1, 1, 1
      FROM apr_workflow_definitions definition
     WHERE definition.tenant_id = p_tenant_id
    ON CONFLICT (tenant_id, form_key) DO NOTHING;

    INSERT INTO apr_form_versions (
        form_version_id, tenant_id, form_id, version_number,
        schema_payload, schema_sha256, lifecycle_state,
        published_at, published_by, created_by)
    SELECT md5('approval-form-version:' || p_tenant_id || ':' || form.form_key || ':1')::uuid,
           p_tenant_id, form.form_id, 1,
           jsonb_build_object(
               'schemaVersion', 1,
               'fields', jsonb_build_array(
                   jsonb_build_object('key', 'summary', 'type', 'TEXTAREA', 'required', true),
                   jsonb_build_object('key', 'amount', 'type', 'NUMBER', 'required', false),
                   jsonb_build_object('key', 'neededBy', 'type', 'DATE', 'required', false))),
           encode(sha256(convert_to(form.form_key || ':1', 'UTF8')), 'hex'),
           form.lifecycle_state,
           CASE WHEN form.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
           CASE WHEN form.lifecycle_state = 'PUBLISHED' THEN 1 ELSE NULL END,
           1
      FROM apr_forms form
     WHERE form.tenant_id = p_tenant_id
    ON CONFLICT (tenant_id, form_id, version_number) DO NOTHING;

    INSERT INTO apr_signature_providers (
        provider_id, tenant_id, provider_key, display_name, provider_type,
        lifecycle_state, capability_metadata, created_by, updated_by)
    VALUES
        (md5('approval-signature:' || p_tenant_id || ':internal')::uuid,
         p_tenant_id, 'INTERNAL_ATTESTATION', 'DWP Internal Attestation',
         'INTERNAL_ATTESTATION', 'ACTIVE',
         '{"evidence":"audit-event","identity":"verified-session"}'::jsonb, 1, 1),
        (md5('approval-signature:' || p_tenant_id || ':docusign')::uuid,
         p_tenant_id, 'DOCUSIGN', 'DocuSign', 'DOCUSIGN',
         'CONFIGURATION_REQUIRED', '{"gate":"D-18","secretsStored":false}'::jsonb, 1, 1),
        (md5('approval-signature:' || p_tenant_id || ':adobe')::uuid,
         p_tenant_id, 'ADOBE_SIGN', 'Adobe Acrobat Sign', 'ADOBE_SIGN',
         'CONFIGURATION_REQUIRED', '{"gate":"D-18","secretsStored":false}'::jsonb, 1, 1)
    ON CONFLICT (tenant_id, provider_key) DO NOTHING;

    INSERT INTO apr_policy_rules (
        policy_id, tenant_id, policy_key, name_ko, name_en,
        policy_type, enforcement_mode, severity, rule_payload,
        created_by, updated_by)
    VALUES
        (md5('approval-policy:' || p_tenant_id || ':self-approval')::uuid,
         p_tenant_id, 'BLOCK_SELF_APPROVAL', '자기 결재 차단', 'Block self approval',
         'SEGREGATION_OF_DUTIES', 'BLOCK', 'CRITICAL',
         '{"requesterCannotDecide":true}'::jsonb, 1, 1),
        (md5('approval-policy:' || p_tenant_id || ':reject-reason')::uuid,
         p_tenant_id, 'REQUIRE_REJECT_REASON', '반려 사유 필수', 'Require rejection reason',
         'DECISION', 'BLOCK', 'HIGH', '{"minimumLength":8}'::jsonb, 1, 1),
        (md5('approval-policy:' || p_tenant_id || ':evidence')::uuid,
         p_tenant_id, 'CAPTURE_DECISION_EVIDENCE', '결정 증적 보존', 'Retain decision evidence',
         'DATA', 'BLOCK', 'HIGH', '{"retentionClass":"EXTENDED"}'::jsonb, 1, 1),
        (md5('approval-policy:' || p_tenant_id || ':sla')::uuid,
         p_tenant_id, 'SLA_ESCALATION', 'SLA 에스컬레이션', 'SLA escalation',
         'SLA', 'WARN', 'MEDIUM', '{"warningPercent":75,"breachPercent":100}'::jsonb, 1, 1)
    ON CONFLICT (tenant_id, policy_key) DO NOTHING;
END;
$$;

CREATE OR REPLACE FUNCTION seed_approval_reference_data(
    p_tenant_id BIGINT,
    p_user_id BIGINT,
    p_person_public_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_workflow_version UUID;
    v_form_version UUID;
BEGIN
    PERFORM seed_approval_tenant(p_tenant_id);

    SELECT version.workflow_version_id, form_version.form_version_id
      INTO v_workflow_version, v_form_version
      FROM apr_workflow_definitions workflow
      JOIN apr_workflow_versions version
        ON version.tenant_id = workflow.tenant_id
       AND version.workflow_id = workflow.workflow_id
       AND version.version_number = workflow.current_version
      JOIN apr_forms form
        ON form.tenant_id = workflow.tenant_id
       AND form.form_key = workflow.workflow_key || '_FORM'
      JOIN apr_form_versions form_version
        ON form_version.tenant_id = form.tenant_id
       AND form_version.form_id = form.form_id
       AND form_version.version_number = form.current_version
     WHERE workflow.tenant_id = p_tenant_id
       AND workflow.workflow_key = 'CAPEX_PURCHASE';

    INSERT INTO apr_requests (
        request_id, tenant_id, request_number, workflow_version_id, form_version_id,
        title, summary, requester_user_id, requester_name, requester_org_name,
        status, priority, data_classification, submitted_at, due_at,
        reference_seed_key, created_by, updated_by)
    SELECT md5('approval-reference-request:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           p_tenant_id, 'REF-' || p_user_id || '-' || LPAD(seed.ordinal::text, 2, '0'),
           v_workflow_version, v_form_version, seed.title, seed.summary,
           p_user_id + 100000 + seed.ordinal, seed.requester_name, seed.requester_org,
           'IN_REVIEW', seed.priority, seed.classification,
           CURRENT_TIMESTAMP - seed.submitted_ago, CURRENT_TIMESTAMP + seed.due_in,
           'task:' || p_user_id || ':' || seed.ordinal, p_user_id, p_user_id
      FROM (VALUES
        (1, 'AI 데이터 플랫폼 GPU 증설', '예산 4.8억원과 3개 공급사 비교 검토',
         '김태연', 'Finance & Risk', 'URGENT', 'CONFIDENTIAL',
         INTERVAL '2 hours', INTERVAL '45 minutes'),
        (2, '신규 협력사 보안 예외', '망 분리 예외 30일과 보완 통제 검토',
         '박지호', 'Cloud Platform', 'HIGH', 'RESTRICTED',
         INTERVAL '5 hours', INTERVAL '4 hours'),
        (3, '고객 분석 환경 접근 연장', '접근 기한이 지났으며 재인증 근거가 필요합니다.',
         '최유진', 'Enterprise Transformation', 'URGENT', 'RESTRICTED',
         INTERVAL '1 day', INTERVAL '-2 hours'),
        (4, 'UX 리서치 도구 구독', '연간 라이선스 25석 갱신 요청',
         '최건우', 'Customer Experience', 'NORMAL', 'INTERNAL',
         INTERVAL '3 hours', INTERVAL '1 day')
      ) seed(ordinal, title, summary, requester_name, requester_org, priority,
             classification, submitted_ago, due_in)
    ON CONFLICT (tenant_id, reference_seed_key) DO NOTHING;

    INSERT INTO apr_steps (
        step_id, tenant_id, request_id, step_key, step_name,
        sequence_number, approval_mode, status, started_at, due_at)
    SELECT md5('approval-reference-step:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           p_tenant_id,
           md5('approval-reference-request:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           'PRIMARY_REVIEW', 'Primary review', 1, 'ANY', 'IN_PROGRESS',
           CURRENT_TIMESTAMP - seed.started_ago, CURRENT_TIMESTAMP + seed.due_in
      FROM (VALUES
        (1, INTERVAL '2 hours', INTERVAL '45 minutes'),
        (2, INTERVAL '5 hours', INTERVAL '4 hours'),
        (3, INTERVAL '1 day', INTERVAL '-2 hours'),
        (4, INTERVAL '3 hours', INTERVAL '1 day')
      ) seed(ordinal, started_ago, due_in)
    ON CONFLICT (tenant_id, request_id, sequence_number) DO NOTHING;

    INSERT INTO apr_tasks (
        task_id, tenant_id, request_id, step_id, assignee_user_id,
        assignee_person_public_id, status, risk_score, due_at)
    SELECT md5('approval-reference-task:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           p_tenant_id,
           md5('approval-reference-request:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           md5('approval-reference-step:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           p_user_id, p_person_public_id, 'PENDING', seed.risk_score,
           CURRENT_TIMESTAMP + seed.due_in
      FROM (VALUES
        (1, 86, INTERVAL '45 minutes'),
        (2, 72, INTERVAL '4 hours'),
        (3, 94, INTERVAL '-2 hours'),
        (4, 38, INTERVAL '1 day')
      ) seed(ordinal, risk_score, due_in)
    ON CONFLICT (task_id) DO NOTHING;

    INSERT INTO apr_request_payloads (
        tenant_id, request_id, payload, payload_sha256, schema_version)
    SELECT p_tenant_id, request.request_id,
           jsonb_build_object('summary', request.summary, 'reference', true),
           encode(sha256(convert_to(request.request_id::text, 'UTF8')), 'hex'), 1
      FROM apr_requests request
     WHERE request.tenant_id = p_tenant_id
       AND request.reference_seed_key LIKE 'task:' || p_user_id || ':%'
    ON CONFLICT (tenant_id, request_id) DO NOTHING;

    INSERT INTO apr_requests (
        request_id, tenant_id, request_number, workflow_version_id, form_version_id,
        title, summary, requester_user_id, requester_person_public_id,
        requester_name, requester_org_name, status, priority, data_classification,
        submitted_at, due_at, completed_at, reference_seed_key, created_by, updated_by)
    SELECT md5('approval-reference-own-request:' || p_tenant_id || ':' || p_user_id || ':' || seed.ordinal)::uuid,
           p_tenant_id, 'MY-' || p_user_id || '-' || LPAD(seed.ordinal::text, 2, '0'),
           v_workflow_version, v_form_version, seed.title, seed.summary,
           p_user_id, p_person_public_id, 'Current user', 'My organization',
           seed.status, seed.priority, seed.classification,
           CASE WHEN seed.status = 'DRAFT' THEN NULL ELSE CURRENT_TIMESTAMP - seed.submitted_ago END,
           CURRENT_TIMESTAMP + seed.due_in,
           CASE WHEN seed.status = 'APPROVED' THEN CURRENT_TIMESTAMP - INTERVAL '18 hours' ELSE NULL END,
           'own:' || p_user_id || ':' || seed.ordinal, p_user_id, p_user_id
      FROM (VALUES
        (1, '클라우드 비용 최적화 계약', '연간 약정 전환과 예상 절감액 검토',
         'IN_REVIEW', 'HIGH', 'CONFIDENTIAL', INTERVAL '6 hours', INTERVAL '18 hours'),
        (2, '글로벌 컨퍼런스 참가', '추가 증빙 요청에 응답이 필요합니다.',
         'NEEDS_INFO', 'NORMAL', 'INTERNAL', INTERVAL '1 day', INTERVAL '2 days'),
        (3, '전문 교육 과정 등록', '승인 완료 후 교육 등록이 진행 중입니다.',
         'APPROVED', 'NORMAL', 'INTERNAL', INTERVAL '2 days', INTERVAL '3 days'),
        (4, '팀 워크숍 예산', '작성 중인 결재 초안입니다.',
         'DRAFT', 'NORMAL', 'INTERNAL', INTERVAL '0 hours', INTERVAL '5 days')
      ) seed(ordinal, title, summary, status, priority, classification, submitted_ago, due_in)
    ON CONFLICT (tenant_id, reference_seed_key) DO NOTHING;

    INSERT INTO apr_request_payloads (
        tenant_id, request_id, payload, payload_sha256, schema_version)
    SELECT p_tenant_id, request.request_id,
           jsonb_build_object('summary', request.summary, 'reference', true),
           encode(sha256(convert_to(request.request_id::text, 'UTF8')), 'hex'), 1
      FROM apr_requests request
     WHERE request.tenant_id = p_tenant_id
       AND request.reference_seed_key LIKE 'own:' || p_user_id || ':%'
    ON CONFLICT (tenant_id, request_id) DO NOTHING;
END;
$$;

COMMENT ON TABLE apr_workflow_versions IS
    'Immutable, checksummed approval process snapshots used by in-flight requests.';
COMMENT ON TABLE apr_request_events IS
    'Append-only business evidence for every approval request transition.';
COMMENT ON TABLE apr_integration_outbox IS
    'Durable tenant-scoped integration events; external providers never run inside the request transaction.';
COMMENT ON TABLE apr_signature_providers IS
    'Provider metadata only. Credentials are external secret references and never persisted in this table.';
