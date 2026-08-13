CREATE TEMP TABLE tmp_product_extension_code_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    source_references VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_product_extension_code_contract_manifest (
    code_set_key, owner_service, display_name, description,
    contract_kind, allowed_values, source_references)
VALUES
    ('AUTH.SCOPED_ADMIN.RESOURCE_LIFECYCLE_STATE', 'dwp-auth-server',
     'Scoped administration resource lifecycle',
     'Availability lifecycle shared by scoped administration responsibilities and resource sets.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[], ARRAY[
        'sys_admin_responsibility_catalog.lifecycle_state',
        'com_admin_resource_sets.lifecycle_state',
        'com_admin_resource_set_members.lifecycle_state']::VARCHAR[]),
    ('AUTH.SCOPED_ADMIN.RESOURCE_TYPE', 'dwp-auth-server',
     'Scoped administration resource type',
     'Resource classes that can be assigned to a delegated administration boundary.',
     'SECURITY', ARRAY['APP']::VARCHAR[],
     ARRAY['com_admin_resource_sets.resource_type']::VARCHAR[]),
    ('AUTH.SCOPED_ADMIN.ASSIGNMENT_SOURCE', 'dwp-auth-server',
     'Scoped administration assignment source',
     'Authoritative provenance for a delegated administration assignment.',
     'SECURITY', ARRAY['MANUAL', 'GROUP', 'IAM', 'PROVISIONING', 'AGENT']::VARCHAR[],
     ARRAY['com_admin_role_assignments.assignment_source']::VARCHAR[]),
    ('AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE', 'dwp-auth-server',
     'Scoped administration assignment lifecycle',
     'Approval, activation, denial, revocation, and expiry lifecycle for delegated administration.',
     'STATE_MACHINE', ARRAY[
        'PENDING_APPROVAL', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED'
     ]::VARCHAR[], ARRAY['com_admin_role_assignments.lifecycle_state']::VARCHAR[]),
    ('AUTH.SCOPED_ADMIN.PRINCIPAL_TYPE', 'dwp-auth-server',
     'Scoped administration principal type',
     'Human and workload identities eligible for a delegated administration responsibility.',
     'SECURITY', ARRAY['USER', 'GROUP', 'SERVICE', 'AGENT']::VARCHAR[],
     ARRAY['com_admin_role_assignments.principal_type']::VARCHAR[]),
    ('AUTH.SCOPED_ADMIN.RISK_TIER', 'dwp-auth-server',
     'Scoped administration risk tier',
     'Control strength required for a delegated administration responsibility.',
     'SECURITY', ARRAY['L1', 'L2', 'L3']::VARCHAR[],
     ARRAY['sys_admin_responsibility_catalog.risk_tier']::VARCHAR[]),
    ('AUTH.RESOURCE_GRANT.EFFECT', 'dwp-auth-server',
     'Runtime resource grant effect',
     'Explicit authorization effect issued by the runtime entitlement authority.',
     'SECURITY', ARRAY['ALLOW']::VARCHAR[],
     ARRAY['com_principal_resource_grants.effect']::VARCHAR[]),
    ('AUTH.RESOURCE_GRANT.LIFECYCLE_STATE', 'dwp-auth-server',
     'Runtime resource grant lifecycle',
     'Effective, revoked, and expired lifecycle for runtime resource grants.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'REVOKED', 'EXPIRED']::VARCHAR[],
     ARRAY['com_principal_resource_grants.lifecycle_state']::VARCHAR[]),
    ('AUTH.RESOURCE_GRANT.PRINCIPAL_TYPE', 'dwp-auth-server',
     'Runtime resource grant principal type',
     'Human identity subjects eligible to receive a runtime resource grant.',
     'SECURITY', ARRAY['USER', 'GROUP']::VARCHAR[],
     ARRAY['com_principal_resource_grants.principal_type']::VARCHAR[]),
    ('AUTH.RESOURCE_GRANT.SOURCE_TYPE', 'dwp-auth-server',
     'Runtime resource grant source',
     'Governed workflow that issued a runtime resource grant.',
     'SECURITY', ARRAY[
        'APP_ACCESS_REQUEST', 'ADMIN_DIRECT', 'ACCESS_PACKAGE'
     ]::VARCHAR[], ARRAY['com_principal_resource_grants.source_type']::VARCHAR[]),
    ('AUTH.IDENTITY_AUDIT.ACTOR_TYPE', 'dwp-auth-server',
     'Identity audit actor type',
     'Human and non-human actors recorded in identity audit evidence.',
     'OBSERVABILITY', ARRAY['USER', 'SERVICE', 'SYSTEM']::VARCHAR[],
     ARRAY['sys_identity_audit_events.actor_type']::VARCHAR[]),
    ('PLATFORM.COMMUNICATION.REACTION', 'dwp-platform-server',
     'Communication reaction',
     'Moderation-safe lightweight reactions available for published communications.',
     'PROTOCOL', ARRAY['CELEBRATE', 'INSIGHTFUL', 'SUPPORT']::VARCHAR[],
     ARRAY['sys_announcement_reactions.reaction_code']::VARCHAR[]),
    ('PLATFORM.SERVICE.CATEGORY_LIFECYCLE_STATE', 'dwp-platform-server',
     'Employee service category lifecycle',
     'Discovery availability lifecycle for employee service categories.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[],
     ARRAY['svc_categories.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.SERVICE.DEFINITION_LIFECYCLE_STATE', 'dwp-platform-server',
     'Employee service definition lifecycle',
     'Authoring, publication, and retirement lifecycle for employee services.',
     'STATE_MACHINE', ARRAY['DRAFT', 'ACTIVE', 'RETIRED']::VARCHAR[],
     ARRAY['svc_definitions.lifecycle_state']::VARCHAR[]),
    ('PLATFORM.SERVICE.DATA_CLASSIFICATION', 'dwp-platform-server',
     'Employee service data classification',
     'Sensitivity classification snapshotted from a service definition into each request.',
     'SECURITY', ARRAY[
        'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'
     ]::VARCHAR[], ARRAY[
        'svc_definitions.data_classification',
        'svc_requests.data_classification']::VARCHAR[]),
    ('PLATFORM.SERVICE.REQUEST_STATUS', 'dwp-platform-server',
     'Employee service request status',
     'Draft, intake, fulfillment, resolution, and cancellation lifecycle for service requests.',
     'STATE_MACHINE', ARRAY[
        'DRAFT', 'SUBMITTED', 'TRIAGED', 'IN_PROGRESS',
        'AWAITING_REQUESTER', 'RESOLVED', 'CLOSED', 'CANCELLED'
     ]::VARCHAR[], ARRAY['svc_requests.status']::VARCHAR[]),
    ('PLATFORM.SERVICE.REQUEST_PRIORITY', 'dwp-platform-server',
     'Employee service request priority',
     'Operational urgency used for service queue triage and SLA decisions.',
     'REFERENCE', ARRAY['LOW', 'NORMAL', 'HIGH', 'URGENT']::VARCHAR[],
     ARRAY['svc_requests.priority']::VARCHAR[]),
    ('PLATFORM.SERVICE.TIMELINE_ACTOR_TYPE', 'dwp-platform-server',
     'Employee service timeline actor type',
     'Human, agent, and system actors recorded in append-only request evidence.',
     'OBSERVABILITY', ARRAY['USER', 'AGENT', 'SYSTEM']::VARCHAR[],
     ARRAY['svc_request_timeline.actor_type']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
SELECT code_set_key, owner_service, display_name, description,
       'SYSTEM', 'CHECK', source_references[1], contract_kind, 'ADMIN_ONLY'
  FROM tmp_product_extension_code_contract_manifest
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = EXCLUDED.configuration_level,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = EXCLUDED.runtime_visibility,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
SELECT manifest.code_set_key,
       value.code,
       INITCAP(REPLACE(LOWER(value.code), '_', ' ')),
       jsonb_build_object(
           'ko', CASE value.code
               WHEN 'ACTIVE' THEN '활성'
               WHEN 'INACTIVE' THEN '비활성'
               WHEN 'RETIRED' THEN '폐기됨'
               WHEN 'DRAFT' THEN '초안'
               WHEN 'PENDING_APPROVAL' THEN '승인 대기'
               WHEN 'DENIED' THEN '거부됨'
               WHEN 'REVOKED' THEN '회수됨'
               WHEN 'EXPIRED' THEN '만료됨'
               WHEN 'PUBLIC' THEN '공개'
               WHEN 'INTERNAL' THEN '내부'
               WHEN 'CONFIDENTIAL' THEN '기밀'
               WHEN 'RESTRICTED' THEN '제한'
               WHEN 'SUBMITTED' THEN '접수됨'
               WHEN 'TRIAGED' THEN '분류됨'
               WHEN 'IN_PROGRESS' THEN '처리 중'
               WHEN 'AWAITING_REQUESTER' THEN '요청자 응답 대기'
               WHEN 'RESOLVED' THEN '해결됨'
               WHEN 'CLOSED' THEN '종료됨'
               WHEN 'CANCELLED' THEN '취소됨'
               WHEN 'LOW' THEN '낮음'
               WHEN 'NORMAL' THEN '보통'
               WHEN 'HIGH' THEN '높음'
               WHEN 'URGENT' THEN '긴급'
               WHEN 'USER' THEN '사용자'
               WHEN 'GROUP' THEN '그룹'
               WHEN 'SERVICE' THEN '서비스'
               WHEN 'SYSTEM' THEN '시스템'
               WHEN 'AGENT' THEN '에이전트'
               WHEN 'MANUAL' THEN '수동'
               WHEN 'PROVISIONING' THEN '프로비저닝'
               WHEN 'ALLOW' THEN '허용'
               WHEN 'APP' THEN '앱'
               WHEN 'APP_ACCESS_REQUEST' THEN '앱 접근 요청'
               WHEN 'ADMIN_DIRECT' THEN '관리자 직접 부여'
               WHEN 'ACCESS_PACKAGE' THEN '접근 패키지'
               WHEN 'CELEBRATE' THEN '축하해요'
               WHEN 'INSIGHTFUL' THEN '유익해요'
               WHEN 'SUPPORT' THEN '응원해요'
               ELSE INITCAP(REPLACE(LOWER(value.code), '_', ' '))
           END,
           'en', INITCAP(REPLACE(LOWER(value.code), '_', ' '))),
       value.ordinality * 10,
       '{}'::jsonb,
       'ACTIVE'
  FROM tmp_product_extension_code_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
      WITH ORDINALITY AS value(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       'DATABASE_COLUMN',
       binding.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_product_extension_code_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.source_references)
      AS binding(source_reference)
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE sys_code_sets IS
    'Central registry for governed code contracts across DWP services; every enum-like database CHECK must be bound with the same active values.';
