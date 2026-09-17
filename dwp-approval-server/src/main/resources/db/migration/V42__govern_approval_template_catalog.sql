CREATE TABLE apr_form_templates (
    template_id UUID PRIMARY KEY,
    scope_kind VARCHAR(16) NOT NULL,
    tenant_id BIGINT REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80),
    template_key VARCHAR(100) NOT NULL,
    owner_group_ref VARCHAR(160) NOT NULL,
    category_key VARCHAR(100) NOT NULL,
    default_workflow_key VARCHAR(100) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    current_version INTEGER NOT NULL,
    source_template_id UUID REFERENCES apr_form_templates(template_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_apr_form_template_scoped_identity UNIQUE(
        tenant_id, management_resource_set_key, template_id),
    CONSTRAINT ck_apr_form_template_scope CHECK (
        (scope_kind = 'GLOBAL' AND tenant_id IS NULL AND management_resource_set_key IS NULL)
        OR (scope_kind = 'TENANT' AND tenant_id IS NOT NULL
            AND management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$')),
    CONSTRAINT ck_apr_form_template_key CHECK (template_key ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_apr_form_template_category CHECK (category_key ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_apr_form_template_workflow CHECK (default_workflow_key ~ '^[A-Z][A-Z0-9_]{1,99}$'),
    CONSTRAINT ck_apr_form_template_state CHECK (lifecycle_state IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT ck_apr_form_template_version CHECK (
        current_version > 0 AND version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_apr_form_template_source CHECK (source_template_id IS NULL OR source_template_id <> template_id)
);

CREATE UNIQUE INDEX uk_apr_form_template_global_key
    ON apr_form_templates(template_key) WHERE scope_kind = 'GLOBAL';
CREATE UNIQUE INDEX uk_apr_form_template_tenant_key
    ON apr_form_templates(tenant_id, management_resource_set_key, template_key)
    WHERE scope_kind = 'TENANT';
CREATE INDEX idx_apr_form_template_tenant_catalog
    ON apr_form_templates(tenant_id, management_resource_set_key, lifecycle_state, category_key, template_key)
    WHERE scope_kind = 'TENANT';

CREATE TABLE apr_form_template_versions (
    template_version_id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES apr_form_templates(template_id),
    version_number INTEGER NOT NULL,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    description_ko VARCHAR(1000) NOT NULL,
    description_en VARCHAR(1000) NOT NULL,
    schema_contract VARCHAR(80) NOT NULL,
    schema_payload JSONB NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    locales JSONB NOT NULL,
    tags JSONB NOT NULL,
    filter_metadata JSONB NOT NULL,
    change_summary_ko VARCHAR(1000) NOT NULL,
    change_summary_en VARCHAR(1000) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    released_at TIMESTAMPTZ,
    released_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by BIGINT NOT NULL,
    CONSTRAINT uk_apr_form_template_version UNIQUE(template_id, version_number),
    CONSTRAINT uk_apr_form_template_version_identity UNIQUE(template_id, template_version_id),
    CONSTRAINT ck_apr_form_template_version_number CHECK (version_number > 0),
    CONSTRAINT ck_apr_form_template_schema_contract CHECK (
        schema_contract = 'DWP_APPROVAL_FORM_TYPED_V3'
        AND schema_payload->>'schemaContract' = schema_contract
        AND schema_payload->>'schemaVersion' = '3'),
    CONSTRAINT ck_apr_form_template_schema_hash CHECK (
        schema_sha256 ~ '^[a-f0-9]{64}$'
        AND schema_sha256 = encode(sha256(convert_to(
            approval_typed_form_canonical_json(schema_payload), 'UTF8')), 'hex')),
    CONSTRAINT ck_apr_form_template_locales CHECK (
        jsonb_typeof(locales) = 'array' AND locales @> '["ko","en"]'::jsonb),
    CONSTRAINT ck_apr_form_template_tags CHECK (jsonb_typeof(tags) = 'array'),
    CONSTRAINT ck_apr_form_template_filter CHECK (jsonb_typeof(filter_metadata) = 'object'),
    CONSTRAINT ck_apr_form_template_version_state CHECK (
        lifecycle_state IN ('DRAFT','RELEASED','RETIRED')
        AND ((lifecycle_state = 'DRAFT' AND released_at IS NULL AND released_by IS NULL)
             OR (lifecycle_state <> 'DRAFT' AND released_at IS NOT NULL AND released_by IS NOT NULL))),
    CONSTRAINT ck_apr_form_template_payload_size CHECK (octet_length(schema_payload::text) <= 262144)
);

CREATE INDEX idx_apr_form_template_version_current
    ON apr_form_template_versions(template_id, lifecycle_state, version_number DESC);
CREATE INDEX idx_apr_form_template_version_tags
    ON apr_form_template_versions USING GIN(tags);
CREATE INDEX idx_apr_form_template_version_locales
    ON apr_form_template_versions USING GIN(locales);
CREATE INDEX idx_apr_form_template_version_filters
    ON apr_form_template_versions USING GIN(filter_metadata);

ALTER TABLE apr_form_templates
    ADD CONSTRAINT fk_apr_form_template_current_version
    FOREIGN KEY(template_id, current_version)
    REFERENCES apr_form_template_versions(template_id, version_number)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE apr_form_template_dependencies (
    template_id UUID NOT NULL,
    template_version_id UUID NOT NULL,
    dependency_kind VARCHAR(32) NOT NULL,
    dependency_key VARCHAR(160) NOT NULL,
    version_constraint VARCHAR(80) NOT NULL DEFAULT 'CURRENT',
    required BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY(template_version_id, dependency_kind, dependency_key),
    FOREIGN KEY(template_id, template_version_id)
        REFERENCES apr_form_template_versions(template_id, template_version_id),
    CONSTRAINT ck_apr_form_template_dependency_kind CHECK (
        dependency_kind IN ('CATEGORY_KEY','WORKFLOW_KEY','DATA_SOURCE_PROVIDER','ROLE_CODE')),
    CONSTRAINT ck_apr_form_template_dependency_key CHECK (
        dependency_key ~ '^[A-Z][A-Z0-9_.:-]{1,159}$'),
    CONSTRAINT ck_apr_form_template_dependency_version CHECK (
        version_constraint ~ '^(CURRENT|[1-9][0-9]{0,8}|>=[1-9][0-9]{0,8})$')
);

CREATE TABLE apr_form_template_command_receipts (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    command_kind VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_template_id UUID NOT NULL REFERENCES apr_form_templates(template_id),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id, management_resource_set_key, actor_user_id, command_kind, idempotency_key),
    FOREIGN KEY(tenant_id, management_resource_set_key, result_template_id)
        REFERENCES apr_form_templates(tenant_id, management_resource_set_key, template_id),
    CONSTRAINT ck_apr_form_template_receipt_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_form_template_receipt_kind CHECK (command_kind = 'CLONE_TEMPLATE'),
    CONSTRAINT ck_apr_form_template_receipt_key CHECK (
        idempotency_key ~ '^[A-Za-z0-9._:-]{1,200}$'),
    CONSTRAINT ck_apr_form_template_receipt_hash CHECK (request_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE FUNCTION protect_released_approval_form_template_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.lifecycle_state = 'RELEASED' THEN
        RAISE EXCEPTION 'Released Approval template versions are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_protect_released_approval_form_template_version
    BEFORE UPDATE OR DELETE ON apr_form_template_versions
    FOR EACH ROW EXECUTE FUNCTION protect_released_approval_form_template_version();

CREATE FUNCTION protect_approval_form_template_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Approval template dependencies and command receipts are append only'; END $$;
CREATE TRIGGER trg_protect_approval_form_template_dependency
    BEFORE UPDATE OR DELETE ON apr_form_template_dependencies
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_template_evidence();
CREATE TRIGGER trg_protect_approval_form_template_receipt
    BEFORE UPDATE OR DELETE ON apr_form_template_command_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_template_evidence();

CREATE FUNCTION enforce_approval_form_template_source_scope()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    source_scope VARCHAR(16);
    source_tenant BIGINT;
    source_resource_set VARCHAR(80);
BEGIN
    IF NEW.source_template_id IS NULL THEN RETURN NEW; END IF;
    SELECT scope_kind, tenant_id, management_resource_set_key
      INTO source_scope, source_tenant, source_resource_set
      FROM apr_form_templates WHERE template_id = NEW.source_template_id;
    IF NOT FOUND OR NEW.scope_kind <> 'TENANT'
       OR NOT (source_scope = 'GLOBAL'
               OR (source_scope = 'TENANT'
                   AND source_tenant = NEW.tenant_id
                   AND source_resource_set = NEW.management_resource_set_key)) THEN
        RAISE EXCEPTION 'Approval template source must be global or in the exact tenant management scope';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_enforce_approval_form_template_source_scope
    BEFORE INSERT OR UPDATE OF scope_kind, tenant_id, management_resource_set_key, source_template_id
    ON apr_form_templates FOR EACH ROW
    EXECUTE FUNCTION enforce_approval_form_template_source_scope();

CREATE FUNCTION protect_approval_form_template_source_provenance()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.source_template_id IS DISTINCT FROM NEW.source_template_id THEN
        RAISE EXCEPTION 'Approval template source provenance is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_protect_approval_form_template_source_provenance
    BEFORE UPDATE OF source_template_id ON apr_form_templates
    FOR EACH ROW EXECUTE FUNCTION protect_approval_form_template_source_provenance();

CREATE FUNCTION approval_form_v3_starter_field(
    p_key TEXT, p_label_ko TEXT, p_label_en TEXT, p_type TEXT,
    p_control TEXT, p_required BOOLEAN, p_options JSONB DEFAULT '[]'::jsonb)
RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object(
        'key', p_key,
        'type', p_type,
        'control', p_control,
        'label', jsonb_build_object('ko', p_label_ko, 'en', p_label_en),
        'help', jsonb_build_object('ko', p_label_ko || ' 정보를 입력하세요.',
                                   'en', 'Provide ' || lower(p_label_en) || '.'),
        'span', jsonb_build_object('desktop', 6, 'tablet', 12, 'mobile', 12),
        'required', p_required,
        'validation', '{}'::jsonb,
        'options', p_options,
        'viewRoles', jsonb_build_array('*'),
        'editRoles', jsonb_build_array('*'),
        'retention', jsonb_build_object('classification', 'INTERNAL',
            'retentionClass', 'STANDARD', 'legalHoldEligible', true),
        'export', jsonb_build_object('included', true,
            'label', jsonb_build_object('ko', p_label_ko, 'en', p_label_en),
            'format', CASE WHEN p_type IN ('NUMBER','CURRENCY','PERCENT','CALCULATED_NUMBER')
                           THEN 'NUMBER' WHEN p_type = 'DATE' THEN 'DATE'
                           WHEN p_type = 'DATETIME' THEN 'DATETIME' ELSE 'TEXT' END,
            'mask', 'NONE'));
$$;

CREATE FUNCTION approval_form_v3_starter_schema(
    p_name_ko TEXT, p_name_en TEXT, p_extra_fields JSONB)
RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object(
        'schemaContract', 'DWP_APPROVAL_FORM_TYPED_V3',
        'schemaVersion', 3,
        'locales', jsonb_build_array('ko','en'),
        'compatibility', jsonb_build_object('mode','INITIAL','minimumRuntime','3.0'),
        'pages', jsonb_build_array(jsonb_build_object(
            'key','request',
            'title',jsonb_build_object('ko',p_name_ko,'en',p_name_en),
            'sections',jsonb_build_array(jsonb_build_object(
                'key','details',
                'title',jsonb_build_object('ko','요청 정보','en','Request details'),
                'layout',jsonb_build_object('columns',12,'gap','STANDARD'),
                'fields', jsonb_build_array(approval_form_v3_starter_field(
                    'summary','요청 내용','Request summary','TEXTAREA','TEXTAREA',true)) || p_extra_fields
            ))
        )),
        'rules','[]'::jsonb,
        'calculations','[]'::jsonb,
        'scenarios','[]'::jsonb,
        'retention',jsonb_build_object('classification','INTERNAL',
            'retentionClass','STANDARD','legalHoldEligible',true),
        'export',jsonb_build_object('formatVersion','1','includeAuditTrail',true));
$$;

WITH starter(template_key,name_ko,name_en,description_ko,description_en,category_key,
             owner_group_ref,workflow_key,tags,extra_fields) AS (VALUES
    ('GENERAL_DECISION','일반 의사결정','General decision','범용 의사결정과 검토 요청','General-purpose decision and review request',
     'GENERAL','GENERAL_OPERATIONS','GENERAL_DECISION','["general","decision"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('decisionNeededBy','결정 필요일','Decision needed by','DATE','DATE_PICKER',true))),
    ('CAPEX_PURCHASE','설비 투자 구매','Capital purchase','자본 지출과 설비 구매 승인','Capital expenditure and equipment purchase approval',
     'FINANCE','FINANCE_CONTROLLERS','CAPEX_PURCHASE','["finance","capex","purchase"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('amount','요청 금액','Requested amount','CURRENCY','CURRENCY_INPUT',true),
                       approval_form_v3_starter_field('costCenter','코스트 센터','Cost center','TEXT','TEXT_INPUT',true))),
    ('EXPENSE_REIMBURSEMENT','경비 정산','Expense reimbursement','업무 경비 정산과 증빙 검토','Business expense reimbursement and evidence review',
     'FINANCE','FINANCE_CONTROLLERS','GENERAL_DECISION','["finance","expense"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('amount','정산 금액','Reimbursement amount','CURRENCY','CURRENCY_INPUT',true),
                       approval_form_v3_starter_field('expenseDate','지출일','Expense date','DATE','DATE_PICKER',true))),
    ('PURCHASE_REQUEST','구매 요청','Purchase request','물품과 서비스 구매 요청','Goods and services purchase request',
     'PROCUREMENT','PROCUREMENT_OPERATIONS','CAPEX_PURCHASE','["procurement","purchase"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('vendor','공급사','Vendor','TEXT','TEXT_INPUT',false),
                       approval_form_v3_starter_field('amount','예상 금액','Estimated amount','CURRENCY','CURRENCY_INPUT',true))),
    ('SUPPLIER_ONBOARDING','협력사 등록','Supplier onboarding','신규 협력사 등록과 위험 검토','New supplier onboarding and risk review',
     'PROCUREMENT','PROCUREMENT_OPERATIONS','SUPPLIER_ONBOARDING','["procurement","supplier"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('supplierName','협력사명','Supplier name','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('countryCode','국가 코드','Country code','TEXT','TEXT_INPUT',true))),
    ('CONTRACT_REVIEW','계약 검토','Contract review','계약 조건과 법무 검토 요청','Contract terms and legal review request',
     'PROCUREMENT','LEGAL_OPERATIONS','SUPPLIER_ONBOARDING','["legal","contract"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('counterparty','계약 상대방','Counterparty','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('contractValue','계약 금액','Contract value','CURRENCY','CURRENCY_INPUT',false))),
    ('ACCESS_EXCEPTION','접근 예외','Access exception','표준 정책을 벗어난 접근 예외 요청','Access exception outside standard policy',
     'ACCESS','SECURITY_GOVERNANCE','ACCESS_EXCEPTION','["security","access","exception"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('systemName','대상 시스템','Target system','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('endDate','종료일','End date','DATE','DATE_PICKER',true))),
    ('SYSTEM_ACCESS_REQUEST','시스템 접근 권한','System access request','업무 시스템 권한 신청','Business system access request',
     'ACCESS','SECURITY_GOVERNANCE','ACCESS_EXCEPTION','["security","access"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('systemName','대상 시스템','Target system','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('requestedRole','요청 역할','Requested role','TEXT','TEXT_INPUT',true))),
    ('CHANGE_REQUEST','변경 요청','Change request','서비스와 시스템 변경 승인','Service and system change approval',
     'GENERAL','CHANGE_ADVISORY_BOARD','GENERAL_DECISION','["change","operations"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('plannedAt','변경 예정 일시','Planned at','DATETIME','DATETIME_PICKER',true),
                       approval_form_v3_starter_field('rollbackPlan','복구 계획','Rollback plan','TEXTAREA','TEXTAREA',true))),
    ('SECURITY_EXCEPTION','보안 정책 예외','Security exception','보안 정책 예외와 보완 통제 승인','Security policy exception and compensating control approval',
     'ACCESS','SECURITY_GOVERNANCE','ACCESS_EXCEPTION','["security","exception","risk"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('control','보완 통제','Compensating control','TEXTAREA','TEXTAREA',true),
                       approval_form_v3_starter_field('expiresAt','만료 일시','Expires at','DATETIME','DATETIME_PICKER',true))),
    ('LEAVE_REQUEST','휴가 신청','Leave request','휴가 일정과 인수인계 승인','Leave schedule and handover approval',
     'PEOPLE','PEOPLE_OPERATIONS','GENERAL_DECISION','["people","leave"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('startDate','시작일','Start date','DATE','DATE_PICKER',true),
                       approval_form_v3_starter_field('endDate','종료일','End date','DATE','DATE_PICKER',true))),
    ('OVERTIME_REQUEST','초과 근무 신청','Overtime request','초과 근무 사전 승인','Overtime pre-approval request',
     'PEOPLE','PEOPLE_OPERATIONS','GENERAL_DECISION','["people","time"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('workDate','근무일','Work date','DATE','DATE_PICKER',true),
                       approval_form_v3_starter_field('hours','예상 시간','Expected hours','NUMBER','NUMBER_INPUT',true))),
    ('HEADCOUNT_REQUEST','인력 충원 요청','Headcount request','신규 또는 대체 인력 충원 승인','New or replacement headcount approval',
     'PEOPLE','PEOPLE_OPERATIONS','GENERAL_DECISION','["people","headcount"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('jobTitle','직무명','Job title','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('headcount','인원','Headcount','NUMBER','NUMBER_INPUT',true))),
    ('REMOTE_WORK_REQUEST','원격 근무 신청','Remote work request','원격 근무 기간과 업무 계획 승인','Remote-work period and work-plan approval',
     'PEOPLE','PEOPLE_OPERATIONS','GENERAL_DECISION','["people","remote-work"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('startDate','시작일','Start date','DATE','DATE_PICKER',true),
                       approval_form_v3_starter_field('location','근무 위치','Work location','TEXT','TEXT_INPUT',true))),
    ('TRAVEL_REQUEST','출장 신청','Travel request','출장 일정과 예산 승인','Business travel schedule and budget approval',
     'PEOPLE','PEOPLE_OPERATIONS','GENERAL_DECISION','["people","travel"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('destination','출장지','Destination','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('budget','예상 비용','Estimated cost','CURRENCY','CURRENCY_INPUT',true))),
    ('DOCUMENT_APPROVAL','문서 승인','Document approval','정책과 업무 문서 검토 승인','Policy and business document review approval',
     'GENERAL','GENERAL_OPERATIONS','GENERAL_DECISION','["document","review"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('documentTitle','문서 제목','Document title','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('effectiveDate','시행일','Effective date','DATE','DATE_PICKER',false))),
    ('DATA_EXPORT_REQUEST','데이터 반출 요청','Data export request','통제된 데이터 반출과 보존 승인','Governed data export and retention approval',
     'ACCESS','DATA_GOVERNANCE','ACCESS_EXCEPTION','["data","export","security"]'::jsonb,
     jsonb_build_array(approval_form_v3_starter_field('dataSet','데이터 세트','Data set','TEXT','TEXT_INPUT',true),
                       approval_form_v3_starter_field('classification','보안 등급','Classification','SINGLE_SELECT','SELECT',true,
                           '[{"value":"INTERNAL","label":{"ko":"내부","en":"Internal"}},{"value":"CONFIDENTIAL","label":{"ko":"기밀","en":"Confidential"}},{"value":"RESTRICTED","label":{"ko":"제한","en":"Restricted"}}]'::jsonb)))
), inserted_templates AS (
    INSERT INTO apr_form_templates(template_id,scope_kind,template_key,owner_group_ref,category_key,
        default_workflow_key,lifecycle_state,current_version,created_by,updated_by)
    SELECT md5('approval-global-template:' || template_key)::uuid,'GLOBAL',template_key,owner_group_ref,
           category_key,workflow_key,'ACTIVE',1,1,1 FROM starter
    RETURNING template_id,template_key
), inserted_versions AS (
    INSERT INTO apr_form_template_versions(template_version_id,template_id,version_number,name_ko,name_en,
        description_ko,description_en,schema_contract,schema_payload,schema_sha256,locales,tags,
        filter_metadata,change_summary_ko,change_summary_en,lifecycle_state,released_at,released_by,created_by)
    SELECT md5('approval-global-template-version:' || starter.template_key || ':1')::uuid,
           template.template_id,1,starter.name_ko,starter.name_en,starter.description_ko,starter.description_en,
           'DWP_APPROVAL_FORM_TYPED_V3',schema.payload,
           encode(sha256(convert_to(approval_typed_form_canonical_json(schema.payload),'UTF8')),'hex'),
           '["ko","en"]'::jsonb,starter.tags,
           jsonb_build_object('category',starter.category_key,'owner',starter.owner_group_ref,
                              'workflow',starter.workflow_key,'starter',true),
           '초기 엔터프라이즈 시작 양식','Initial enterprise starter template','RELEASED',clock_timestamp(),1,1
      FROM starter
      JOIN inserted_templates template USING(template_key)
      CROSS JOIN LATERAL (SELECT approval_form_v3_starter_schema(
          starter.name_ko,starter.name_en,starter.extra_fields) AS payload) schema
    RETURNING template_id,template_version_id
)
INSERT INTO apr_form_template_dependencies(template_id,template_version_id,dependency_kind,dependency_key)
SELECT version.template_id,version.template_version_id,dependency.kind,dependency.key
  FROM inserted_versions version
  JOIN starter ON version.template_version_id =
      md5('approval-global-template-version:' || starter.template_key || ':1')::uuid
  CROSS JOIN LATERAL (VALUES ('CATEGORY_KEY',starter.category_key),
                             ('WORKFLOW_KEY',starter.workflow_key)) dependency(kind,key);

DROP FUNCTION approval_form_v3_starter_schema(TEXT,TEXT,JSONB);
DROP FUNCTION approval_form_v3_starter_field(TEXT,TEXT,TEXT,TEXT,TEXT,BOOLEAN,JSONB);

COMMENT ON TABLE apr_form_templates IS
    'Global and tenant-scoped Approval template identities; installing a template can only create a draft.';
COMMENT ON TABLE apr_form_template_versions IS
    'Immutable released Approval template packages with bilingual filtering metadata.';
COMMENT ON TABLE apr_form_template_dependencies IS
    'Exact category, workflow, role, and data-source dependencies for one template version.';
