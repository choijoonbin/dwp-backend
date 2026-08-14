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
           AND workflow_key = template.workflow_key;

        SELECT form_id, lifecycle_state
          INTO form_record
          FROM apr_forms
         WHERE tenant_id = p_tenant_id
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

DO $$
DECLARE
    tenant RECORD;
BEGIN
    FOR tenant IN SELECT tenant_id FROM apr_tenants LOOP
        PERFORM seed_approval_product_templates(tenant.tenant_id);
    END LOOP;
END;
$$;

COMMENT ON FUNCTION seed_approval_product_templates(BIGINT) IS
    'Creates versioned, business-specific approval routes and multilingual form metadata after base tenant provisioning.';
