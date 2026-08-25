-- Owner-service enforcement for the Auth-selected Approval management scope.
-- Existing tenant objects are the canonical RS_APPROVALS root. New management
-- objects inherit the exact selected resource-set key in application SQL.

CREATE TABLE apr_management_scope_schema_fence (
    fence_key VARCHAR(80) PRIMARY KEY,
    minimum_reader_capability VARCHAR(120) NOT NULL,
    non_root_writes_activated_at TIMESTAMPTZ,
    activated_by_release VARCHAR(120),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_apr_management_scope_fence_key CHECK (
        fence_key = 'APPROVAL_MANAGEMENT_SCOPE_V1'),
    CONSTRAINT ck_apr_management_scope_release CHECK (
        (non_root_writes_activated_at IS NULL AND activated_by_release IS NULL)
        OR (non_root_writes_activated_at IS NOT NULL
            AND activated_by_release = 'approval-management-scope-v1'))
);

INSERT INTO apr_management_scope_schema_fence (
    fence_key, minimum_reader_capability)
VALUES ('APPROVAL_MANAGEMENT_SCOPE_V1', 'approval-management-scope-v1');

ALTER TABLE apr_workflow_definitions
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';
ALTER TABLE apr_forms
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';
ALTER TABLE apr_form_categories
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';
ALTER TABLE apr_policy_rules
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';
ALTER TABLE apr_signature_providers
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';
ALTER TABLE apr_requests
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';
ALTER TABLE apr_integration_outbox
    ADD COLUMN management_resource_set_key VARCHAR(80)
        NOT NULL DEFAULT 'RS_APPROVALS';

-- Workflow-scoped delegations must bind to the immutable workflow identity.
-- workflow_key remains a display/projection field only; it is no longer an
-- authorization join key now that keys are unique per management boundary.
ALTER TABLE apr_delegations
    ADD COLUMN workflow_id UUID;

UPDATE apr_delegations delegation
   SET workflow_id = workflow.workflow_id
  FROM apr_workflow_definitions workflow
 WHERE delegation.tenant_id = workflow.tenant_id
   AND delegation.scope_type = 'WORKFLOW'
   AND delegation.workflow_key = workflow.workflow_key
   AND workflow.management_resource_set_key = 'RS_APPROVALS';

ALTER TABLE apr_delegations
    ADD CONSTRAINT fk_apr_delegation_workflow FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES apr_workflow_definitions(tenant_id, workflow_id),
    ADD CONSTRAINT ck_apr_delegation_workflow_binding CHECK (
        (scope_type = 'ALL' AND workflow_id IS NULL AND workflow_key IS NULL)
        OR (scope_type = 'WORKFLOW' AND workflow_id IS NOT NULL
            AND workflow_key IS NOT NULL));

ALTER TABLE apr_workflow_definitions
    ADD CONSTRAINT ck_apr_workflow_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');
ALTER TABLE apr_forms
    ADD CONSTRAINT ck_apr_form_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');
ALTER TABLE apr_form_categories
    ADD CONSTRAINT ck_apr_form_category_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');
ALTER TABLE apr_policy_rules
    ADD CONSTRAINT ck_apr_policy_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');
ALTER TABLE apr_signature_providers
    ADD CONSTRAINT ck_apr_signature_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');
ALTER TABLE apr_requests
    ADD CONSTRAINT ck_apr_request_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');
ALTER TABLE apr_integration_outbox
    ADD CONSTRAINT ck_apr_integration_management_resource_set
        CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$');

-- Evidence ledgers carry tenant_id themselves, so bind that tenant to the
-- referenced parent instead of trusting globally unique IDs alone.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM apr_policy_rule_versions version
          JOIN apr_policy_rules policy ON policy.policy_id = version.policy_id
         WHERE version.tenant_id <> policy.tenant_id)
       OR EXISTS (
        SELECT 1
          FROM apr_recovery_auditor_assignment_events event
          JOIN apr_integration_outbox outbox ON outbox.outbox_id = event.outbox_id
         WHERE event.tenant_id <> outbox.tenant_id) THEN
        RAISE EXCEPTION 'Existing Approval ledger tenant links are inconsistent';
    END IF;
END
$$;

ALTER TABLE apr_policy_rules
    ADD CONSTRAINT uk_apr_policy_tenant_identity UNIQUE (tenant_id, policy_id);
ALTER TABLE apr_policy_rule_versions
    DROP CONSTRAINT apr_policy_rule_versions_policy_id_fkey,
    ADD CONSTRAINT fk_apr_policy_version_tenant FOREIGN KEY (tenant_id, policy_id)
        REFERENCES apr_policy_rules(tenant_id, policy_id);
ALTER TABLE apr_integration_outbox
    ADD CONSTRAINT uk_apr_integration_tenant_identity UNIQUE (tenant_id, outbox_id);
ALTER TABLE apr_recovery_auditor_assignment_events
    DROP CONSTRAINT apr_recovery_auditor_assignment_events_outbox_id_fkey,
    ADD CONSTRAINT fk_apr_recovery_event_tenant FOREIGN KEY (tenant_id, outbox_id)
        REFERENCES apr_integration_outbox(tenant_id, outbox_id);

-- V13 predates object-scoped management sets and therefore pinned assignment
-- evidence to RS_APPROVALS.  V14 upgrades the invariant to the exact event
-- scope while preserving all existing root-bound rows.
ALTER TABLE apr_integration_outbox
    DROP CONSTRAINT ck_apr_recovery_assignment_evidence,
    ADD CONSTRAINT ck_apr_recovery_assignment_evidence CHECK (
        (recovery_auditor_assignment_state = 'ASSIGNED'
            AND assigned_auditor_user_id IS NOT NULL
            AND recovery_auditor_resource_set_key = management_resource_set_key
            AND recovery_auditor_assignment_revision IS NOT NULL
            AND btrim(recovery_auditor_assignment_revision) <> ''
            AND recovery_auditor_assigned_at IS NOT NULL
            AND recovery_auditor_assignment_locked_by IS NULL
            AND recovery_auditor_assignment_locked_until IS NULL)
        OR (recovery_auditor_assignment_state IN (
                'PENDING', 'ASSIGNING', 'RETRY', 'EXHAUSTED', 'NOT_REQUIRED')
            AND assigned_auditor_user_id IS NULL
            AND recovery_auditor_resource_set_key IS NULL
            AND recovery_auditor_assignment_revision IS NULL
            AND recovery_auditor_assigned_at IS NULL)
        OR recovery_auditor_assignment_state = 'LEGACY_UNASSIGNED');

-- Object keys are unique inside a management boundary. UUID-based foreign
-- keys remain tenant-bound and the link trigger below prevents cross-boundary
-- composition.
ALTER TABLE apr_workflow_definitions
    DROP CONSTRAINT uk_apr_workflow_key,
    ADD CONSTRAINT uk_apr_workflow_key UNIQUE
        (tenant_id, management_resource_set_key, workflow_key);
ALTER TABLE apr_forms
    DROP CONSTRAINT uk_apr_form_key,
    ADD CONSTRAINT uk_apr_form_key UNIQUE
        (tenant_id, management_resource_set_key, form_key);
ALTER TABLE apr_form_categories
    DROP CONSTRAINT uk_apr_form_category_key,
    ADD CONSTRAINT uk_apr_form_category_key UNIQUE
        (tenant_id, management_resource_set_key, category_key);
ALTER TABLE apr_policy_rules
    DROP CONSTRAINT uk_apr_policy_key,
    ADD CONSTRAINT uk_apr_policy_key UNIQUE
        (tenant_id, management_resource_set_key, policy_key);
ALTER TABLE apr_signature_providers
    DROP CONSTRAINT uk_apr_signature_provider,
    ADD CONSTRAINT uk_apr_signature_provider UNIQUE
        (tenant_id, management_resource_set_key, provider_key);

CREATE INDEX idx_apr_workflow_management_scope
    ON apr_workflow_definitions
        (tenant_id, management_resource_set_key, lifecycle_state, updated_at DESC);
CREATE INDEX idx_apr_form_management_scope
    ON apr_forms
        (tenant_id, management_resource_set_key, lifecycle_state, updated_at DESC);
CREATE INDEX idx_apr_form_category_management_scope
    ON apr_form_categories
        (tenant_id, management_resource_set_key, lifecycle_state, sort_order);
CREATE INDEX idx_apr_policy_management_scope
    ON apr_policy_rules
        (tenant_id, management_resource_set_key, lifecycle_state, policy_key);
CREATE INDEX idx_apr_signature_management_scope
    ON apr_signature_providers
        (tenant_id, management_resource_set_key, lifecycle_state, provider_key);
CREATE INDEX idx_apr_request_management_scope
    ON apr_requests
        (tenant_id, management_resource_set_key, status, updated_at DESC);
CREATE INDEX idx_apr_integration_management_scope
    ON apr_integration_outbox
        (tenant_id, management_resource_set_key, status, updated_at DESC);
CREATE INDEX idx_apr_delegation_workflow_identity
    ON apr_delegations
        (tenant_id, workflow_id, delegate_user_id, lifecycle_state)
    WHERE scope_type = 'WORKFLOW';

-- V11's compatibility trigger predates per-resource-set category keys. Never
-- resolve GENERAL across management boundaries when a legacy writer omits it.
CREATE OR REPLACE FUNCTION assign_approval_form_general_category()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.category_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT category.category_id
      INTO NEW.category_id
      FROM apr_form_categories category
     WHERE category.tenant_id = NEW.tenant_id
       AND category.management_resource_set_key = NEW.management_resource_set_key
       AND category.category_key = 'GENERAL';

    IF NEW.category_id IS NULL THEN
        RAISE EXCEPTION
            'Tenant % management scope % must initialize the governed Approval form catalog',
            NEW.tenant_id, NEW.management_resource_set_key;
    END IF;
    RETURN NEW;
END;
$$;

-- A task is a child of one request through one of that request's steps.  The
-- former independent request/step foreign keys allowed a same-tenant task to
-- combine a request from one management scope with a step from another.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM apr_tasks task
          JOIN apr_steps step
            ON step.tenant_id = task.tenant_id
           AND step.step_id = task.step_id
         WHERE step.request_id <> task.request_id) THEN
        RAISE EXCEPTION 'Existing Approval task request/step links are inconsistent';
    END IF;
END
$$;

-- Reinstall root seed logic with V14's scope-qualified natural keys. Root
-- catalog provisioning remains idempotent while non-root keys may coexist.
CREATE OR REPLACE FUNCTION seed_approval_tenant(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_tenant_state VARCHAR(24);
BEGIN
    INSERT INTO apr_tenants (tenant_id)
    VALUES (p_tenant_id)
    ON CONFLICT (tenant_id) DO NOTHING;

    SELECT lifecycle_state INTO v_tenant_state
      FROM apr_tenants
     WHERE tenant_id = p_tenant_id
     FOR UPDATE;
    IF v_tenant_state IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Approval tenant % is not active', p_tenant_id
            USING ERRCODE = 'check_violation';
    END IF;

    -- V11 requires every form insert to resolve the tenant's GENERAL
    -- category. Seed the root catalog before this function creates forms;
    -- the final catalog pass remains responsible for routes and enrichment.
    PERFORM seed_approval_form_catalog(p_tenant_id);

    INSERT INTO apr_workflow_definitions (
        workflow_id, tenant_id, workflow_key, name_ko, name_en,
        description_ko, description_en, category, data_classification,
        lifecycle_state, current_version, sla_minutes, owner_group_ref,
        management_resource_set_key, created_by, updated_by)
    SELECT md5('approval-workflow:' || p_tenant_id || ':' || seed.workflow_key)::uuid,
           p_tenant_id, seed.workflow_key, seed.name_ko, seed.name_en,
           seed.description_ko, seed.description_en, seed.category,
           seed.classification, seed.lifecycle_state, 1, seed.sla_minutes,
           seed.owner_group, 'RS_APPROVALS', 1, 1
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
    ON CONFLICT (tenant_id, management_resource_set_key, workflow_key) DO NOTHING;

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
       AND definition.management_resource_set_key = 'RS_APPROVALS'
    ON CONFLICT (tenant_id, workflow_id, version_number) DO NOTHING;

    INSERT INTO apr_forms (
        form_id, tenant_id, form_key, name_ko, name_en,
        lifecycle_state, current_version, category_id,
        management_resource_set_key,
        created_by, updated_by)
    SELECT md5('approval-form:' || p_tenant_id || ':' || definition.workflow_key)::uuid,
           p_tenant_id, definition.workflow_key || '_FORM',
           definition.name_ko || ' 양식', definition.name_en || ' form',
           definition.lifecycle_state, 1,
           (SELECT category.category_id
              FROM apr_form_categories category
             WHERE category.tenant_id = p_tenant_id
               AND category.management_resource_set_key = 'RS_APPROVALS'
               AND category.category_key = 'GENERAL'),
           'RS_APPROVALS', 1, 1
      FROM apr_workflow_definitions definition
     WHERE definition.tenant_id = p_tenant_id
       AND definition.management_resource_set_key = 'RS_APPROVALS'
    ON CONFLICT (tenant_id, management_resource_set_key, form_key) DO NOTHING;

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
       AND form.management_resource_set_key = 'RS_APPROVALS'
    ON CONFLICT (tenant_id, form_id, version_number) DO NOTHING;

    INSERT INTO apr_signature_providers (
        provider_id, tenant_id, provider_key, display_name, provider_type,
        lifecycle_state, capability_metadata, management_resource_set_key,
        created_by, updated_by)
    VALUES
        (md5('approval-signature:' || p_tenant_id || ':internal')::uuid,
         p_tenant_id, 'INTERNAL_ATTESTATION', 'DWP Internal Attestation',
         'INTERNAL_ATTESTATION', 'ACTIVE',
         '{"evidence":"audit-event","identity":"verified-session"}'::jsonb, 'RS_APPROVALS', 1, 1),
        (md5('approval-signature:' || p_tenant_id || ':docusign')::uuid,
         p_tenant_id, 'DOCUSIGN', 'DocuSign', 'DOCUSIGN',
         'CONFIGURATION_REQUIRED', '{"gate":"D-18","secretsStored":false}'::jsonb, 'RS_APPROVALS', 1, 1),
        (md5('approval-signature:' || p_tenant_id || ':adobe')::uuid,
         p_tenant_id, 'ADOBE_SIGN', 'Adobe Acrobat Sign', 'ADOBE_SIGN',
         'CONFIGURATION_REQUIRED', '{"gate":"D-18","secretsStored":false}'::jsonb, 'RS_APPROVALS', 1, 1)
    ON CONFLICT (tenant_id, management_resource_set_key, provider_key) DO NOTHING;

    INSERT INTO apr_policy_rules (
        policy_id, tenant_id, policy_key, name_ko, name_en,
        policy_type, enforcement_mode, severity, rule_payload,
        management_resource_set_key, created_by, updated_by)
    VALUES
        (md5('approval-policy:' || p_tenant_id || ':self-approval')::uuid,
         p_tenant_id, 'BLOCK_SELF_APPROVAL', '자기 결재 차단', 'Block self approval',
         'SEGREGATION_OF_DUTIES', 'BLOCK', 'CRITICAL',
         '{"requesterCannotDecide":true}'::jsonb, 'RS_APPROVALS', 1, 1),
        (md5('approval-policy:' || p_tenant_id || ':reject-reason')::uuid,
         p_tenant_id, 'REQUIRE_REJECT_REASON', '반려 사유 필수', 'Require rejection reason',
         'DECISION', 'BLOCK', 'HIGH', '{"minimumLength":8}'::jsonb, 'RS_APPROVALS', 1, 1),
        (md5('approval-policy:' || p_tenant_id || ':evidence')::uuid,
         p_tenant_id, 'CAPTURE_DECISION_EVIDENCE', '결정 증적 보존', 'Retain decision evidence',
         'DATA', 'BLOCK', 'HIGH', '{"retentionClass":"EXTENDED"}'::jsonb, 'RS_APPROVALS', 1, 1),
        (md5('approval-policy:' || p_tenant_id || ':sla')::uuid,
         p_tenant_id, 'SLA_ESCALATION', 'SLA 에스컬레이션', 'SLA escalation',
         'SLA', 'WARN', 'MEDIUM', '{"warningPercent":75,"breachPercent":100}'::jsonb, 'RS_APPROVALS', 1, 1)
     ON CONFLICT (tenant_id, management_resource_set_key, policy_key) DO NOTHING;

    PERFORM seed_approval_form_catalog(p_tenant_id);
END;
$$;

-- V5's catalog seed also needs the root natural-key boundary after V14.
CREATE OR REPLACE FUNCTION seed_approval_product_templates(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    template RECORD;
    workflow_record RECORD;
    form_record RECORD;
BEGIN
    FOR template IN
        SELECT *
          FROM (VALUES
            (
                'CAPEX_PURCHASE',
                jsonb_build_object(
                    'schemaVersion', 2,
                    'steps', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'BUDGET_REVIEW', 'name', '예산 검토', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 360),
                        jsonb_build_object(
                            'key', 'PROCUREMENT_REVIEW', 'name', '구매 조건 검토', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 360),
                        jsonb_build_object(
                            'key', 'FINAL_APPROVAL', 'name', '최종 승인', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 720)),
                    'guardrails', jsonb_build_object(
                        'selfApproval', false, 'requireReasonOnReject', true,
                        'optimisticConcurrency', true)),
                jsonb_build_object(
                    'schemaVersion', 2,
                    'fields', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'summary', 'labelKo', '요청 내용', 'labelEn', 'Request summary',
                            'helpKo', '투자 목적과 기대 효과, 주요 리스크를 요약하세요.',
                            'helpEn', 'Summarize the purpose, expected outcome, and key risks.',
                            'type', 'TEXTAREA', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'amount', 'labelKo', '요청 금액', 'labelEn', 'Requested amount',
                            'helpKo', '세금과 부대비용을 포함한 총액을 입력하세요.',
                            'helpEn', 'Enter the total amount including taxes and incidental costs.',
                            'type', 'NUMBER', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'currency', 'labelKo', '통화', 'labelEn', 'Currency',
                            'helpKo', '계약 및 예산 기준 통화를 선택하세요.',
                            'helpEn', 'Select the contract and budget currency.',
                            'type', 'SELECT', 'required', true,
                            'options', jsonb_build_array('KRW', 'USD', 'EUR', 'JPY')),
                        jsonb_build_object(
                            'key', 'costCenter', 'labelKo', '코스트 센터', 'labelEn', 'Cost center',
                            'helpKo', '비용이 귀속될 조직의 코스트 센터를 입력하세요.',
                            'helpEn', 'Enter the cost center that owns the expense.',
                            'type', 'TEXT', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'vendor', 'labelKo', '공급사', 'labelEn', 'Vendor',
                            'helpKo', '계약 예정 공급사 또는 비교 대상 공급사를 입력하세요.',
                            'helpEn', 'Enter the proposed or shortlisted vendor.',
                            'type', 'TEXT', 'required', false, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'neededBy', 'labelKo', '필요 일자', 'labelEn', 'Needed by',
                            'helpKo', '구매 또는 서비스가 실제 필요한 날짜를 선택하세요.',
                            'helpEn', 'Select the date the purchase or service is needed.',
                            'type', 'DATE', 'required', true, 'options', '[]'::jsonb)))
            ),
            (
                'ACCESS_EXCEPTION',
                jsonb_build_object(
                    'schemaVersion', 2,
                    'steps', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'SECURITY_REVIEW', 'name', '보안 검토', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 120),
                        jsonb_build_object(
                            'key', 'OWNER_APPROVAL', 'name', '업무 책임자 승인', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 120)),
                    'guardrails', jsonb_build_object(
                        'selfApproval', false, 'requireReasonOnReject', true,
                        'optimisticConcurrency', true)),
                jsonb_build_object(
                    'schemaVersion', 2,
                    'fields', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'summary', 'labelKo', '예외 요청 사유', 'labelEn', 'Exception rationale',
                            'helpKo', '표준 권한으로 해결할 수 없는 이유와 업무 영향을 설명하세요.',
                            'helpEn', 'Explain why standard access is insufficient and the business impact.',
                            'type', 'TEXTAREA', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'systemName', 'labelKo', '대상 시스템', 'labelEn', 'Target system',
                            'helpKo', '접근이 필요한 시스템 또는 데이터 영역을 입력하세요.',
                            'helpEn', 'Enter the system or data domain requiring access.',
                            'type', 'TEXT', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'accessRole', 'labelKo', '요청 권한', 'labelEn', 'Requested access',
                            'helpKo', '필요한 역할과 권한 범위를 최소 권한 원칙에 맞게 작성하세요.',
                            'helpEn', 'Describe the least-privilege role and permission scope.',
                            'type', 'TEXT', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'startDate', 'labelKo', '시작일', 'labelEn', 'Start date',
                            'helpKo', '예외 권한의 시작일을 선택하세요.',
                            'helpEn', 'Select the exception start date.',
                            'type', 'DATE', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'endDate', 'labelKo', '종료일', 'labelEn', 'End date',
                            'helpKo', '자동 회수를 위한 종료일을 선택하세요.',
                            'helpEn', 'Select the expiry date for automatic revocation.',
                            'type', 'DATE', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'compensatingControl', 'labelKo', '보완 통제', 'labelEn', 'Compensating control',
                            'helpKo', '모니터링, 로그 검토 등 예외 기간에 적용할 통제를 작성하세요.',
                            'helpEn', 'Describe monitoring or review controls applied during the exception.',
                            'type', 'TEXTAREA', 'required', true, 'options', '[]'::jsonb)))
            ),
            (
                'SUPPLIER_ONBOARDING',
                jsonb_build_object(
                    'schemaVersion', 2,
                    'steps', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'PROCUREMENT_REVIEW', 'name', '구매 검토', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 720),
                        jsonb_build_object(
                            'key', 'SECURITY_REVIEW', 'name', '보안 검토', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 720),
                        jsonb_build_object(
                            'key', 'PAYMENT_READINESS', 'name', '지급 준비 확인', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 1440)),
                    'guardrails', jsonb_build_object(
                        'selfApproval', false, 'requireReasonOnReject', true,
                        'optimisticConcurrency', true)),
                jsonb_build_object(
                    'schemaVersion', 2,
                    'fields', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'summary', 'labelKo', '등록 목적', 'labelEn', 'Onboarding rationale',
                            'helpKo', '협력사 활용 목적과 예상 거래 범위를 작성하세요.',
                            'helpEn', 'Describe the supplier purpose and expected engagement.',
                            'type', 'TEXTAREA', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'supplierName', 'labelKo', '협력사명', 'labelEn', 'Supplier name',
                            'helpKo', '계약 및 세금 문서의 법적 회사명을 입력하세요.',
                            'helpEn', 'Enter the legal entity name used on contract and tax documents.',
                            'type', 'TEXT', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'countryCode', 'labelKo', '국가 코드', 'labelEn', 'Country code',
                            'helpKo', '협력사 법인 소재지의 ISO 국가 코드를 입력하세요.',
                            'helpEn', 'Enter the ISO country code of the supplier legal entity.',
                            'type', 'TEXT', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'contractValue', 'labelKo', '예상 계약 금액', 'labelEn', 'Expected contract value',
                            'helpKo', '초기 계약 기간 기준 예상 총액을 입력하세요.',
                            'helpEn', 'Enter the expected total for the initial contract term.',
                            'type', 'NUMBER', 'required', false, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'dataAccessLevel', 'labelKo', '데이터 접근 등급', 'labelEn', 'Data access level',
                            'helpKo', '협력사가 접근할 수 있는 최고 데이터 등급을 선택하세요.',
                            'helpEn', 'Select the highest data classification available to the supplier.',
                            'type', 'SELECT', 'required', true,
                            'options', jsonb_build_array('NONE', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
                        jsonb_build_object(
                            'key', 'targetDate', 'labelKo', '업무 개시 예정일', 'labelEn', 'Target start date',
                            'helpKo', '등록과 필수 검토가 완료되어야 하는 날짜를 선택하세요.',
                            'helpEn', 'Select the date onboarding and mandatory reviews must be complete.',
                            'type', 'DATE', 'required', true, 'options', '[]'::jsonb)))
            ),
            (
                'GENERAL_DECISION',
                jsonb_build_object(
                    'schemaVersion', 2,
                    'steps', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'PRIMARY_REVIEW', 'name', '의사결정 검토', 'mode', 'ANY',
                            'candidateRole', 'APPROVAL_OPERATOR', 'slaMinutes', 1440)),
                    'guardrails', jsonb_build_object(
                        'selfApproval', false, 'requireReasonOnReject', true,
                        'optimisticConcurrency', true)),
                jsonb_build_object(
                    'schemaVersion', 2,
                    'fields', jsonb_build_array(
                        jsonb_build_object(
                            'key', 'summary', 'labelKo', '의사결정 요청', 'labelEn', 'Decision request',
                            'helpKo', '배경, 선택지, 권고안과 필요한 결정을 작성하세요.',
                            'helpEn', 'Describe the context, options, recommendation, and decision needed.',
                            'type', 'TEXTAREA', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'decisionOwner', 'labelKo', '업무 책임자', 'labelEn', 'Business owner',
                            'helpKo', '결정 결과를 실행할 책임자의 이메일 또는 사번을 입력하세요.',
                            'helpEn', 'Enter the email or employee number of the accountable owner.',
                            'type', 'USER', 'required', true, 'options', '[]'::jsonb),
                        jsonb_build_object(
                            'key', 'neededBy', 'labelKo', '결정 필요일', 'labelEn', 'Decision needed by',
                            'helpKo', '결정이 완료되어야 하는 날짜를 선택하세요.',
                            'helpEn', 'Select the date by which the decision is required.',
                            'type', 'DATE', 'required', true, 'options', '[]'::jsonb)))
            )
          ) seed(workflow_key, definition, schema_payload)
    LOOP
        SELECT workflow_id, lifecycle_state
          INTO workflow_record
          FROM apr_workflow_definitions
         WHERE tenant_id = p_tenant_id
           AND management_resource_set_key = 'RS_APPROVALS'
           AND workflow_key = template.workflow_key;

        SELECT form_id, lifecycle_state
          INTO form_record
          FROM apr_forms
         WHERE tenant_id = p_tenant_id
           AND management_resource_set_key = 'RS_APPROVALS'
           AND form_key = template.workflow_key || '_FORM';

        IF workflow_record.workflow_id IS NOT NULL AND form_record.form_id IS NOT NULL THEN
            INSERT INTO apr_workflow_versions (
                workflow_version_id, tenant_id, workflow_id, version_number,
                definition, definition_sha256, lifecycle_state, effective_from,
                published_at, published_by, created_by)
            VALUES (
                md5('approval-workflow-version:' || p_tenant_id || ':' || template.workflow_key || ':2')::uuid,
                p_tenant_id, workflow_record.workflow_id, 2,
                template.definition,
                encode(sha256(convert_to(template.definition::text, 'UTF8')), 'hex'),
                workflow_record.lifecycle_state,
                CASE WHEN workflow_record.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                CASE WHEN workflow_record.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                CASE WHEN workflow_record.lifecycle_state = 'PUBLISHED' THEN 1 ELSE NULL END,
                1)
            ON CONFLICT (tenant_id, workflow_id, version_number) DO NOTHING;

            INSERT INTO apr_form_versions (
                form_version_id, tenant_id, form_id, version_number,
                schema_payload, schema_sha256, lifecycle_state,
                published_at, published_by, created_by)
            VALUES (
                md5('approval-form-version:' || p_tenant_id || ':' || template.workflow_key || '_FORM:2')::uuid,
                p_tenant_id, form_record.form_id, 2,
                template.schema_payload,
                encode(sha256(convert_to(template.schema_payload::text, 'UTF8')), 'hex'),
                form_record.lifecycle_state,
                CASE WHEN form_record.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                CASE WHEN form_record.lifecycle_state = 'PUBLISHED' THEN 1 ELSE NULL END,
                1)
            ON CONFLICT (tenant_id, form_id, version_number) DO NOTHING;

            UPDATE apr_workflow_definitions
               SET current_version = CASE WHEN current_version = 1 THEN 2 ELSE current_version END,
                   updated_at = CASE WHEN current_version = 1 THEN CURRENT_TIMESTAMP ELSE updated_at END
             WHERE tenant_id = p_tenant_id
               AND workflow_id = workflow_record.workflow_id;

            UPDATE apr_forms
               SET current_version = CASE WHEN current_version = 1 THEN 2 ELSE current_version END,
                   updated_at = CASE WHEN current_version = 1 THEN CURRENT_TIMESTAMP ELSE updated_at END
             WHERE tenant_id = p_tenant_id
               AND form_id = form_record.form_id;
        END IF;
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION seed_approval_form_catalog(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO apr_form_categories (
        category_id, tenant_id, category_key, name_ko, name_en,
        description_ko, description_en, icon_key, sort_order,
        management_resource_set_key, created_by, updated_by)
    SELECT md5('approval-form-category:' || p_tenant_id || ':' || seed.category_key)::uuid,
           p_tenant_id, seed.category_key, seed.name_ko, seed.name_en,
           seed.description_ko, seed.description_en, seed.icon_key, seed.sort_order,
           'RS_APPROVALS', 1, 1
      FROM (VALUES
        ('GENERAL', '공통 업무', 'General operations',
         '조직 전반에서 사용하는 일반 의사결정 양식입니다.',
         'General decision forms used across the organization.', 'files', 10),
        ('PEOPLE', '인사·구성원', 'People and workforce',
         '구성원 생애주기와 인사 운영을 위한 양식입니다.',
         'Forms for workforce lifecycle and people operations.', 'users', 20),
        ('FINANCE', '재무·투자', 'Finance and investment',
         '예산, 비용, 투자 의사결정을 위한 통제 양식입니다.',
         'Governed forms for budget, expense, and investment decisions.', 'landmark', 30),
        ('PROCUREMENT', '구매·협력사', 'Procurement and suppliers',
         '구매 요청과 협력사 온보딩을 위한 양식입니다.',
         'Forms for purchasing and supplier onboarding.', 'package-check', 40),
        ('ACCESS', '접근·보안', 'Access and security',
         '권한 요청과 보안 예외를 위한 제한 양식입니다.',
         'Restricted forms for access requests and security exceptions.', 'shield-check', 50)
      ) seed(category_key, name_ko, name_en, description_ko, description_en, icon_key, sort_order)
    ON CONFLICT (tenant_id, management_resource_set_key, category_key) DO NOTHING;

    UPDATE apr_forms form
       SET category_id = category.category_id,
           description_ko = CASE WHEN form.description_ko = '' THEN workflow.description_ko
                                 ELSE form.description_ko END,
           description_en = CASE WHEN form.description_en = '' THEN workflow.description_en
                                 ELSE form.description_en END,
           owner_group_ref = COALESCE(form.owner_group_ref, workflow.owner_group_ref)
      FROM apr_workflow_definitions workflow
      JOIN apr_form_categories category
        ON category.tenant_id = workflow.tenant_id
       AND category.category_key = workflow.category
       AND category.management_resource_set_key = 'RS_APPROVALS'
       AND workflow.management_resource_set_key = 'RS_APPROVALS'
     WHERE form.tenant_id = p_tenant_id
       AND form.management_resource_set_key = 'RS_APPROVALS'
       AND workflow.tenant_id = form.tenant_id
       AND form.form_key = workflow.workflow_key || '_FORM'
       AND (form.category_id IS NULL OR form.description_ko = '' OR form.description_en = '');

    UPDATE apr_forms form
       SET category_id = category.category_id
      FROM apr_form_categories category
     WHERE form.tenant_id = p_tenant_id
       AND category.tenant_id = form.tenant_id
       AND category.category_key = 'GENERAL'
       AND category.management_resource_set_key = 'RS_APPROVALS'
       AND form.management_resource_set_key = 'RS_APPROVALS'
       AND form.category_id IS NULL;

    INSERT INTO apr_form_workflow_bindings (
        binding_id, tenant_id, form_id, workflow_id, binding_type,
        condition_payload, priority, lifecycle_state, effective_from, created_by, updated_by)
    SELECT md5('approval-form-route:' || p_tenant_id || ':' || form.form_key || ':' || workflow.workflow_key)::uuid,
           p_tenant_id, form.form_id, workflow.workflow_id, 'DEFAULT',
           '{}'::jsonb, 100, 'ACTIVE',
           CASE WHEN form.lifecycle_state = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
           1, 1
      FROM apr_forms form
      JOIN apr_workflow_definitions workflow
        ON workflow.tenant_id = form.tenant_id
       AND form.form_key = workflow.workflow_key || '_FORM'
       AND workflow.management_resource_set_key = form.management_resource_set_key
     WHERE form.tenant_id = p_tenant_id
       AND form.management_resource_set_key = 'RS_APPROVALS'
    ON CONFLICT (tenant_id, form_id, workflow_id) DO NOTHING;
END;
$$;

ALTER TABLE apr_steps
    ADD CONSTRAINT uk_apr_step_request_identity UNIQUE
        (tenant_id, request_id, step_id);
ALTER TABLE apr_tasks
    DROP CONSTRAINT fk_apr_task_step,
    ADD CONSTRAINT fk_apr_task_step_request FOREIGN KEY
        (tenant_id, request_id, step_id)
        REFERENCES apr_steps(tenant_id, request_id, step_id) ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION apr_assert_management_scope_links()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_left_scope VARCHAR(120);
    v_right_scope VARCHAR(120);
BEGIN
    IF TG_TABLE_NAME <> 'apr_delegations' THEN
        IF TG_OP = 'UPDATE'
           AND NEW.management_resource_set_key <> OLD.management_resource_set_key THEN
            RAISE EXCEPTION 'Approval management resource-set binding is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_TABLE_NAME = 'apr_form_categories' THEN
        IF NEW.parent_category_id IS NOT NULL THEN
            SELECT management_resource_set_key INTO v_left_scope
              FROM apr_form_categories
             WHERE tenant_id = NEW.tenant_id
               AND category_id = NEW.parent_category_id;
            IF v_left_scope IS DISTINCT FROM NEW.management_resource_set_key THEN
                RAISE EXCEPTION 'Approval category parent crosses management resource sets'
                    USING ERRCODE = 'foreign_key_violation';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'apr_forms' THEN
        IF NEW.category_id IS NOT NULL THEN
            SELECT management_resource_set_key INTO v_left_scope
              FROM apr_form_categories
             WHERE tenant_id = NEW.tenant_id
               AND category_id = NEW.category_id;
            IF v_left_scope IS DISTINCT FROM NEW.management_resource_set_key THEN
                RAISE EXCEPTION 'Approval form category crosses management resource sets'
                    USING ERRCODE = 'foreign_key_violation';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'apr_requests' THEN
        SELECT definition.management_resource_set_key
          INTO v_left_scope
          FROM apr_workflow_versions version
          JOIN apr_workflow_definitions definition
            ON definition.tenant_id = version.tenant_id
           AND definition.workflow_id = version.workflow_id
         WHERE version.tenant_id = NEW.tenant_id
           AND version.workflow_version_id = NEW.workflow_version_id;
        SELECT form.management_resource_set_key
          INTO v_right_scope
          FROM apr_form_versions version
          JOIN apr_forms form
            ON form.tenant_id = version.tenant_id
           AND form.form_id = version.form_id
         WHERE version.tenant_id = NEW.tenant_id
           AND version.form_version_id = NEW.form_version_id;
        IF v_left_scope IS DISTINCT FROM NEW.management_resource_set_key
           OR v_right_scope IS DISTINCT FROM NEW.management_resource_set_key THEN
            RAISE EXCEPTION 'Approval request assets cross management resource sets'
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'apr_integration_outbox' THEN
        IF NEW.request_id IS NOT NULL THEN
            SELECT request.management_resource_set_key
              INTO v_left_scope
              FROM apr_requests request
             WHERE request.tenant_id = NEW.tenant_id
               AND request.request_id = NEW.request_id;
            IF v_left_scope IS DISTINCT FROM NEW.management_resource_set_key THEN
                RAISE EXCEPTION 'Approval integration event crosses management resource sets'
                    USING ERRCODE = 'foreign_key_violation';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'apr_delegations' THEN
        IF TG_OP = 'UPDATE'
           AND (NEW.scope_type IS DISTINCT FROM OLD.scope_type
                OR NEW.workflow_id IS DISTINCT FROM OLD.workflow_id
                OR NEW.workflow_key IS DISTINCT FROM OLD.workflow_key) THEN
            RAISE EXCEPTION 'Approval delegation scope identity is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.scope_type = 'WORKFLOW' THEN
            SELECT workflow_key INTO v_left_scope
              FROM apr_workflow_definitions workflow
             WHERE workflow.tenant_id = NEW.tenant_id
               AND workflow.workflow_id = NEW.workflow_id;
            IF v_left_scope IS DISTINCT FROM NEW.workflow_key THEN
                RAISE EXCEPTION 'Approval delegation workflow identity is inconsistent'
                    USING ERRCODE = 'foreign_key_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION apr_assert_management_binding_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_form_scope VARCHAR(80);
    v_workflow_scope VARCHAR(80);
BEGIN
    SELECT management_resource_set_key INTO v_form_scope
      FROM apr_forms
     WHERE tenant_id = NEW.tenant_id AND form_id = NEW.form_id;
    SELECT management_resource_set_key INTO v_workflow_scope
      FROM apr_workflow_definitions
     WHERE tenant_id = NEW.tenant_id AND workflow_id = NEW.workflow_id;
    IF v_form_scope IS DISTINCT FROM v_workflow_scope THEN
        RAISE EXCEPTION 'Approval form route crosses management resource sets'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_apr_workflow_management_scope
    BEFORE UPDATE OF management_resource_set_key ON apr_workflow_definitions
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_form_management_scope
    BEFORE INSERT OR UPDATE OF category_id, management_resource_set_key ON apr_forms
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_form_category_management_scope
    BEFORE INSERT OR UPDATE OF parent_category_id, management_resource_set_key
    ON apr_form_categories
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_policy_management_scope
    BEFORE UPDATE OF management_resource_set_key ON apr_policy_rules
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_signature_management_scope
    BEFORE UPDATE OF management_resource_set_key ON apr_signature_providers
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_request_management_scope
    BEFORE INSERT OR UPDATE OF workflow_version_id, form_version_id,
        management_resource_set_key ON apr_requests
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_integration_management_scope
    BEFORE INSERT OR UPDATE OF request_id, management_resource_set_key
    ON apr_integration_outbox
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_delegation_workflow_scope
    BEFORE INSERT OR UPDATE OF workflow_id, workflow_key, scope_type
    ON apr_delegations
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_scope_links();
CREATE TRIGGER trg_apr_form_workflow_management_scope
    BEFORE INSERT OR UPDATE OF form_id, workflow_id ON apr_form_workflow_bindings
    FOR EACH ROW EXECUTE FUNCTION apr_assert_management_binding_scope();

-- Existing data must be internally consistent before scoped enforcement starts.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM apr_forms form
          JOIN apr_form_categories category
            ON category.tenant_id = form.tenant_id
           AND category.category_id = form.category_id
         WHERE form.management_resource_set_key
               <> category.management_resource_set_key)
       OR EXISTS (
        SELECT 1
          FROM apr_form_workflow_bindings binding
          JOIN apr_forms form
            ON form.tenant_id = binding.tenant_id
           AND form.form_id = binding.form_id
          JOIN apr_workflow_definitions workflow
            ON workflow.tenant_id = binding.tenant_id
           AND workflow.workflow_id = binding.workflow_id
         WHERE form.management_resource_set_key
               <> workflow.management_resource_set_key)
       OR EXISTS (
        SELECT 1
          FROM apr_delegations delegation
         WHERE delegation.scope_type = 'WORKFLOW'
           AND delegation.workflow_id IS NULL) THEN
        RAISE EXCEPTION 'Existing Approval management scope links are inconsistent';
    END IF;
END
$$;
