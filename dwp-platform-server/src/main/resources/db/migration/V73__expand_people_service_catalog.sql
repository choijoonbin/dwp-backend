UPDATE svc_categories
   SET name_ko = 'HR 및 구성원 지원',
       name_en = 'HR and people support',
       description_ko = '증명서, 인사정보, 급여, 복리후생과 성장 지원',
       description_en = 'Documents, personal data, pay, benefits, and growth support',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE category_key = 'PEOPLE';

INSERT INTO svc_definitions (
    tenant_id, service_key, category_key, name_ko, name_en,
    description_ko, description_en, owner_group, lifecycle_state,
    request_schema, schema_version, sla_hours, estimated_resolution_hours,
    data_classification, featured, tags, created_by, updated_by)
SELECT tenant.tenant_id, seed.service_key, 'PEOPLE', seed.name_ko, seed.name_en,
       seed.description_ko, seed.description_en, seed.owner_group, 'ACTIVE',
       seed.request_schema::jsonb, 1, seed.sla_hours, seed.estimate_hours,
       seed.classification, seed.featured, seed.tags::jsonb, 1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    ('people.personal-information-change', '인사정보 변경 요청', 'Personal information change',
     '법적 이름, 연락처, 주소 등 검증이 필요한 인사정보 변경을 요청합니다.',
     'Request a verified change to legal name, contact details, address, or other HR data.',
     'People Operations',
     '{"fields":[{"key":"changeType","type":"SELECT","labelKo":"변경 유형","labelEn":"Change type","required":true,"options":["LEGAL_NAME","CONTACT","ADDRESS","EMERGENCY_CONTACT","OTHER"]},{"key":"effectiveDate","type":"DATE","labelKo":"효력 시작일","labelEn":"Effective date","required":true},{"key":"details","type":"TEXTAREA","labelKo":"변경 내용과 근거","labelEn":"Change details and evidence","required":true}]}',
     48, 24, 'RESTRICTED', TRUE, '["hr","profile","personal-data"]'),
    ('people.payroll-inquiry', '급여 문의', 'Payroll inquiry',
     '급여 명세, 공제, 지급 일정 등 민감한 급여 문의를 보안 채널로 접수합니다.',
     'Submit a confidential question about a pay statement, deduction, or payment schedule.',
     'Payroll Operations',
     '{"fields":[{"key":"payPeriod","type":"TEXT","labelKo":"급여 기간","labelEn":"Pay period","required":true},{"key":"inquiryType","type":"SELECT","labelKo":"문의 유형","labelEn":"Inquiry type","required":true,"options":["STATEMENT","DEDUCTION","PAYMENT_DATE","TAX","OTHER"]},{"key":"details","type":"TEXTAREA","labelKo":"문의 내용","labelEn":"Inquiry details","required":true}]}',
     24, 8, 'RESTRICTED', TRUE, '["hr","payroll","pay"]'),
    ('people.benefits-life-event', '복리후생 생애사건 신고', 'Benefits life event',
     '결혼, 출생, 부양가족 변경 등 보장 변경이 필요한 생애사건을 신고합니다.',
     'Report a marriage, birth, dependent change, or another event that may change coverage.',
     'Benefits Operations',
     '{"fields":[{"key":"eventType","type":"SELECT","labelKo":"생애사건","labelEn":"Life event","required":true,"options":["MARRIAGE","BIRTH_ADOPTION","DEPENDENT_CHANGE","LOSS_OF_COVERAGE","OTHER"]},{"key":"eventDate","type":"DATE","labelKo":"발생일","labelEn":"Event date","required":true},{"key":"details","type":"TEXTAREA","labelKo":"추가 설명","labelEn":"Additional details","required":true}]}',
     48, 24, 'RESTRICTED', TRUE, '["hr","benefits","life-event"]'),
    ('people.onboarding-transition-help', '입사·전환 여정 지원', 'Onboarding and transition support',
     '입사, 부서 이동, 휴직 복귀 또는 퇴사 여정의 미완료 작업을 요청합니다.',
     'Get help with incomplete tasks in onboarding, transfer, return, or offboarding journeys.',
     'People Operations',
     '{"fields":[{"key":"journeyType","type":"SELECT","labelKo":"여정 유형","labelEn":"Journey type","required":true,"options":["ONBOARDING","TRANSFER","RETURN_FROM_LEAVE","OFFBOARDING"]},{"key":"neededBy","type":"DATE","labelKo":"필요 일자","labelEn":"Needed by","required":false},{"key":"details","type":"TEXTAREA","labelKo":"도움이 필요한 내용","labelEn":"Support needed","required":true}]}',
     24, 16, 'CONFIDENTIAL', FALSE, '["hr","journey","onboarding"]')
 ) seed(service_key, name_ko, name_en, description_ko, description_en,
        owner_group, request_schema, sla_hours, estimate_hours, classification,
        featured, tags)
ON CONFLICT (tenant_id, service_key) DO UPDATE SET
    category_key = EXCLUDED.category_key,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    owner_group = EXCLUDED.owner_group,
    lifecycle_state = 'ACTIVE',
    request_schema = EXCLUDED.request_schema,
    schema_version = GREATEST(svc_definitions.schema_version, EXCLUDED.schema_version),
    sla_hours = EXCLUDED.sla_hours,
    estimated_resolution_hours = EXCLUDED.estimated_resolution_hours,
    data_classification = EXCLUDED.data_classification,
    featured = EXCLUDED.featured,
    tags = EXCLUDED.tags,
    version = svc_definitions.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;
