CREATE SEQUENCE svc_request_number_seq START WITH 1001;

CREATE TABLE svc_categories (
    service_category_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    category_key VARCHAR(50) NOT NULL,
    name_ko VARCHAR(120) NOT NULL,
    name_en VARCHAR(120) NOT NULL,
    description_ko VARCHAR(500) NOT NULL,
    description_en VARCHAR(500) NOT NULL,
    icon_key VARCHAR(50) NOT NULL,
    tone VARCHAR(20) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 100,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_svc_category_key UNIQUE (tenant_id, category_key),
    CONSTRAINT ck_svc_category_key CHECK (category_key ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_svc_category_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_svc_category_sort CHECK (sort_order BETWEEN 0 AND 10000)
);

CREATE TABLE svc_definitions (
    service_definition_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    service_key VARCHAR(80) NOT NULL,
    category_key VARCHAR(50) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    description_ko VARCHAR(1000) NOT NULL,
    description_en VARCHAR(1000) NOT NULL,
    owner_group VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    request_schema JSONB NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    sla_hours INTEGER NOT NULL,
    estimated_resolution_hours INTEGER NOT NULL,
    data_classification VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_svc_definition_key UNIQUE (tenant_id, service_key),
    CONSTRAINT uk_svc_definition_tenant_id UNIQUE (tenant_id, service_definition_id),
    CONSTRAINT fk_svc_definition_category FOREIGN KEY (tenant_id, category_key)
        REFERENCES svc_categories(tenant_id, category_key),
    CONSTRAINT ck_svc_definition_key CHECK (service_key ~ '^[a-z][a-z0-9.-]{2,79}$'),
    CONSTRAINT ck_svc_definition_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_svc_definition_schema CHECK (jsonb_typeof(request_schema) = 'object'),
    CONSTRAINT ck_svc_definition_tags CHECK (jsonb_typeof(tags) = 'array'),
    CONSTRAINT ck_svc_definition_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_svc_definition_sla CHECK (sla_hours BETWEEN 1 AND 8760),
    CONSTRAINT ck_svc_definition_estimate
        CHECK (estimated_resolution_hours BETWEEN 1 AND 8760),
    CONSTRAINT ck_svc_definition_classification
        CHECK (data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

CREATE TABLE svc_requests (
    service_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    request_number VARCHAR(24) NOT NULL,
    requester_user_id BIGINT NOT NULL,
    service_definition_id BIGINT NOT NULL,
    service_key VARCHAR(80) NOT NULL,
    service_name_ko VARCHAR(160) NOT NULL,
    service_name_en VARCHAR(160) NOT NULL,
    summary VARCHAR(240) NOT NULL,
    request_payload JSONB NOT NULL,
    request_schema_snapshot JSONB NOT NULL,
    schema_version INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    data_classification VARCHAR(20) NOT NULL,
    assigned_group VARCHAR(160) NOT NULL,
    assigned_to VARCHAR(160),
    submitted_at TIMESTAMPTZ,
    sla_due_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    idempotency_key UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_svc_request_number UNIQUE (tenant_id, request_number),
    CONSTRAINT uk_svc_request_idempotency
        UNIQUE (tenant_id, requester_user_id, idempotency_key),
    CONSTRAINT uk_svc_request_tenant_id UNIQUE (tenant_id, service_request_id),
    CONSTRAINT fk_svc_request_definition
        FOREIGN KEY (tenant_id, service_definition_id)
        REFERENCES svc_definitions(tenant_id, service_definition_id),
    CONSTRAINT ck_svc_request_payload CHECK (jsonb_typeof(request_payload) = 'object'),
    CONSTRAINT ck_svc_request_schema CHECK (jsonb_typeof(request_schema_snapshot) = 'object'),
    CONSTRAINT ck_svc_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'TRIAGED', 'IN_PROGRESS',
        'AWAITING_REQUESTER', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_svc_request_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_svc_request_classification
        CHECK (data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_svc_request_submission
        CHECK ((status = 'DRAFT' AND submitted_at IS NULL AND sla_due_at IS NULL)
            OR status <> 'DRAFT')
);

CREATE TABLE svc_request_timeline (
    service_request_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    service_request_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT,
    note VARCHAR(2000),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_svc_timeline_request
        FOREIGN KEY (tenant_id, service_request_id)
        REFERENCES svc_requests(tenant_id, service_request_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_svc_timeline_actor CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_svc_timeline_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX idx_svc_definition_discovery
    ON svc_definitions (tenant_id, lifecycle_state, featured DESC, category_key, name_ko);
CREATE INDEX idx_svc_request_requester
    ON svc_requests (tenant_id, requester_user_id, status, updated_at DESC);
CREATE INDEX idx_svc_request_operations
    ON svc_requests (tenant_id, status, sla_due_at, updated_at DESC);
CREATE INDEX idx_svc_request_timeline
    ON svc_request_timeline (tenant_id, service_request_id, occurred_at);

COMMENT ON TABLE svc_definitions IS
    'Versioned employee-service catalog definitions. Request schema is snapshotted at submission.';
COMMENT ON TABLE svc_requests IS
    'Tenant-scoped employee service requests with idempotency, optimistic locking, assignment, and SLA evidence.';
COMMENT ON TABLE svc_request_timeline IS
    'Append-only request lifecycle evidence for user, service agent, and system actions.';

INSERT INTO svc_categories (
    tenant_id, category_key, name_ko, name_en, description_ko, description_en,
    icon_key, tone, sort_order, created_by, updated_by)
SELECT tenant.tenant_id, seed.category_key, seed.name_ko, seed.name_en,
       seed.description_ko, seed.description_en, seed.icon_key, seed.tone,
       seed.sort_order, 1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    ('TECHNOLOGY', '기술 지원', 'Technology', '계정, 기기와 업무 도구 지원',
     'Accounts, devices, and workplace technology', 'monitor-cog', 'BLUE', 10),
    ('PEOPLE', '구성원 지원', 'People', '증명서, 학습과 구성원 서비스',
     'Documents, learning, and people services', 'users-round', 'GREEN', 20),
    ('WORKPLACE', '업무 환경', 'Workplace', '공간, 방문과 시설 서비스',
     'Spaces, visitors, and facilities', 'building-2', 'TEAL', 30),
    ('FINANCE', '재무 지원', 'Finance', '법인카드와 비용 관련 지원',
     'Corporate card and expense support', 'wallet-cards', 'AMBER', 40),
    ('PROCUREMENT', '구매 지원', 'Procurement', '구매 요청과 공급사 온보딩',
     'Purchasing intake and supplier onboarding', 'shopping-bag', 'CORAL', 50)
 ) seed(category_key, name_ko, name_en, description_ko, description_en,
        icon_key, tone, sort_order)
ON CONFLICT (tenant_id, category_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko, name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    icon_key = EXCLUDED.icon_key, tone = EXCLUDED.tone,
    sort_order = EXCLUDED.sort_order, lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP, updated_by = 1;

INSERT INTO svc_definitions (
    tenant_id, service_key, category_key, name_ko, name_en,
    description_ko, description_en, owner_group, lifecycle_state,
    request_schema, schema_version, sla_hours, estimated_resolution_hours,
    data_classification, featured, tags, created_by, updated_by)
SELECT tenant.tenant_id, seed.service_key, seed.category_key, seed.name_ko, seed.name_en,
       seed.description_ko, seed.description_en, seed.owner_group, 'ACTIVE',
       seed.request_schema::jsonb, 1, seed.sla_hours, seed.estimate_hours,
       seed.classification, seed.featured, seed.tags::jsonb, 1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    ('technology.software-access', 'TECHNOLOGY', '업무 소프트웨어 접근', 'Software access',
     '업무에 필요한 소프트웨어와 라이선스를 요청합니다.',
     'Request approved software and licenses for your work.', 'IT Service Desk',
     '{"fields":[{"key":"softwareName","type":"TEXT","labelKo":"소프트웨어 이름","labelEn":"Software name","required":true},{"key":"businessReason","type":"TEXTAREA","labelKo":"업무 목적","labelEn":"Business reason","required":true},{"key":"neededBy","type":"DATE","labelKo":"필요 일자","labelEn":"Needed by","required":false}]}',
     24, 16, 'INTERNAL', TRUE, '["access","software"]'),
    ('technology.device-help', 'TECHNOLOGY', '업무 기기 지원', 'Device help',
     '노트북, 모니터와 업무 기기의 문제를 접수합니다.',
     'Get help with laptops, monitors, and workplace devices.', 'IT Service Desk',
     '{"fields":[{"key":"deviceType","type":"SELECT","labelKo":"기기 유형","labelEn":"Device type","required":true,"options":["LAPTOP","MONITOR","MOBILE","OTHER"]},{"key":"issue","type":"TEXTAREA","labelKo":"문제 설명","labelEn":"Issue","required":true},{"key":"assetTag","type":"TEXT","labelKo":"자산 번호","labelEn":"Asset tag","required":false}]}',
     8, 4, 'INTERNAL', TRUE, '["device","support"]'),
    ('technology.account-help', 'TECHNOLOGY', '계정 및 로그인 지원', 'Account and sign-in help',
     '업무 계정, MFA 또는 로그인 문제를 요청합니다.',
     'Request help with a work account, MFA, or sign-in.', 'Identity Operations',
     '{"fields":[{"key":"systemName","type":"TEXT","labelKo":"대상 시스템","labelEn":"System","required":true},{"key":"issueType","type":"SELECT","labelKo":"문제 유형","labelEn":"Issue type","required":true,"options":["SIGN_IN","MFA","LOCKED","OTHER"]},{"key":"details","type":"TEXTAREA","labelKo":"상세 내용","labelEn":"Details","required":true}]}',
     4, 2, 'CONFIDENTIAL', FALSE, '["identity","account"]'),
    ('people.employment-certificate', 'PEOPLE', '재직 증명서', 'Employment certificate',
     '제출 목적과 언어에 맞는 재직 증명서를 요청합니다.',
     'Request an employment certificate for a stated purpose and language.', 'People Operations',
     '{"fields":[{"key":"purpose","type":"TEXT","labelKo":"제출 목적","labelEn":"Purpose","required":true},{"key":"language","type":"SELECT","labelKo":"발급 언어","labelEn":"Language","required":true,"options":["KO","EN"]},{"key":"copies","type":"NUMBER","labelKo":"부수","labelEn":"Copies","required":true}]}',
     24, 8, 'CONFIDENTIAL', TRUE, '["certificate","people"]'),
    ('people.learning-support', 'PEOPLE', '학습 및 교육 지원', 'Learning support',
     '직무 교육, 자격증 또는 외부 학습 지원을 문의합니다.',
     'Ask for support with training, certification, or external learning.', 'Talent Growth',
     '{"fields":[{"key":"programName","type":"TEXT","labelKo":"과정명","labelEn":"Program","required":true},{"key":"learningGoal","type":"TEXTAREA","labelKo":"학습 목표","labelEn":"Learning goal","required":true},{"key":"estimatedCost","type":"NUMBER","labelKo":"예상 비용","labelEn":"Estimated cost","required":false}]}',
     72, 48, 'INTERNAL', FALSE, '["learning","growth"]'),
    ('workplace.facility-help', 'WORKPLACE', '시설 불편 접수', 'Facility support',
     '업무 공간의 시설 문제나 안전 위험을 접수합니다.',
     'Report a workplace facility issue or safety concern.', 'Workplace Operations',
     '{"fields":[{"key":"location","type":"TEXT","labelKo":"위치","labelEn":"Location","required":true},{"key":"issue","type":"TEXTAREA","labelKo":"불편 내용","labelEn":"Issue","required":true},{"key":"safetyRisk","type":"CHECKBOX","labelKo":"안전 위험 여부","labelEn":"Safety risk","required":false}]}',
     8, 4, 'INTERNAL', TRUE, '["facility","safety"]'),
    ('workplace.visitor-access', 'WORKPLACE', '방문자 출입 요청', 'Visitor access',
     '외부 방문자의 출입과 안내를 사전 요청합니다.',
     'Arrange access and arrival guidance for an external visitor.', 'Workplace Operations',
     '{"fields":[{"key":"visitorName","type":"TEXT","labelKo":"방문자 이름","labelEn":"Visitor name","required":true},{"key":"company","type":"TEXT","labelKo":"소속 회사","labelEn":"Company","required":true},{"key":"visitDate","type":"DATE","labelKo":"방문 일자","labelEn":"Visit date","required":true},{"key":"hostContact","type":"TEXT","labelKo":"방문 담당자","labelEn":"Host contact","required":true}]}',
     8, 4, 'CONFIDENTIAL', FALSE, '["visitor","access"]'),
    ('finance.corporate-card', 'FINANCE', '법인카드 지원', 'Corporate card support',
     '법인카드 발급, 한도 또는 사용 문제를 접수합니다.',
     'Request help with corporate card issuance, limits, or usage.', 'Finance Shared Services',
     '{"fields":[{"key":"requestType","type":"SELECT","labelKo":"요청 유형","labelEn":"Request type","required":true,"options":["NEW_CARD","LIMIT","TRANSACTION","OTHER"]},{"key":"businessReason","type":"TEXTAREA","labelKo":"업무 사유","labelEn":"Business reason","required":true}]}',
     48, 24, 'CONFIDENTIAL', FALSE, '["card","finance"]'),
    ('procurement.purchase-intake', 'PROCUREMENT', '구매 사전 검토', 'Purchase intake',
     '구매 목적과 예상 금액을 제출하고 다음 절차를 안내받습니다.',
     'Submit the purpose and estimated value of a purchase for routing.', 'Procurement Operations',
     '{"fields":[{"key":"itemOrService","type":"TEXT","labelKo":"구매 품목 또는 서비스","labelEn":"Item or service","required":true},{"key":"businessReason","type":"TEXTAREA","labelKo":"구매 목적","labelEn":"Business reason","required":true},{"key":"estimatedAmount","type":"NUMBER","labelKo":"예상 금액","labelEn":"Estimated amount","required":true},{"key":"neededBy","type":"DATE","labelKo":"필요 일자","labelEn":"Needed by","required":true}]}',
     72, 48, 'CONFIDENTIAL', TRUE, '["purchase","intake"]')
 ) seed(service_key, category_key, name_ko, name_en, description_ko, description_en,
        owner_group, request_schema, sla_hours, estimate_hours, classification,
        featured, tags)
ON CONFLICT (tenant_id, service_key) DO UPDATE SET
    category_key = EXCLUDED.category_key,
    name_ko = EXCLUDED.name_ko, name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    owner_group = EXCLUDED.owner_group,
    lifecycle_state = 'ACTIVE', request_schema = EXCLUDED.request_schema,
    sla_hours = EXCLUDED.sla_hours,
    estimated_resolution_hours = EXCLUDED.estimated_resolution_hours,
    data_classification = EXCLUDED.data_classification,
    featured = EXCLUDED.featured, tags = EXCLUDED.tags,
    updated_at = CURRENT_TIMESTAMP, updated_by = 1;

UPDATE adm_workspace_apps
   SET name_ko = '서비스 센터', name_en = 'Services',
       description_ko = 'IT, 구성원, 업무 환경, 재무 및 구매 요청을 한곳에서 처리합니다.',
       description_en = 'Discover and track IT, people, workplace, finance, and procurement services.',
       owner_name = 'Shared Services', category = 'SERVICE', launch_mode = 'NATIVE',
       launch_target = '/services', icon_key = 'services',
       health_state = 'HEALTHY', lifecycle_state = 'ACTIVE',
       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = 1
 WHERE app_key = 'ref-app-service';
