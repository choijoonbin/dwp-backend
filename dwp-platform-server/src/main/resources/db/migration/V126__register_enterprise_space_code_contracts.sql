CREATE TEMP TABLE tmp_space_code_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    source_references VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_space_code_contract_manifest (
    code_set_key, display_name, description,
    contract_kind, allowed_values, source_references)
VALUES
    ('SPACE.TENANT.CREATION_POLICY', 'Space creation policy',
     'Tenant policy controlling who may initiate a new Space.',
     'SECURITY', ARRAY['ADMIN_ONLY', 'OPEN', 'POLICY_DRIVEN']::VARCHAR[],
     ARRAY['spc_tenants.creation_policy']::VARCHAR[]),
    ('SPACE.TENANT.LIFECYCLE_STATE', 'Space tenant lifecycle',
     'Tenant-level availability of the Space capability.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'SUSPENDED', 'RETIRED']::VARCHAR[],
     ARRAY['spc_tenants.lifecycle_state']::VARCHAR[]),
    ('SPACE.CREATION.DECISION_MODE', 'Space creation decision mode',
     'Decision mode shared by Space templates and creation requests.',
     'SECURITY', ARRAY['APPROVAL', 'AUTO', 'POLICY']::VARCHAR[],
     ARRAY['spc_templates.creation_mode', 'spc_space_requests.decision_mode']::VARCHAR[]),
    ('SPACE.DATA_CLASSIFICATION', 'Space data classification',
     'Sensitivity classification inherited by templates, Spaces, and content.',
     'SECURITY', ARRAY['CONFIDENTIAL', 'INTERNAL', 'PUBLIC', 'RESTRICTED']::VARCHAR[],
     ARRAY[
        'spc_templates.default_data_classification',
        'spc_spaces.data_classification',
        'spc_content_items.data_classification'
     ]::VARCHAR[]),
    ('SPACE.TEMPLATE.DEFAULT_MEMBER_ROLE', 'Space template default member role',
     'Least-privilege role assigned by a Space template.',
     'SECURITY', ARRAY['CONTRIBUTOR', 'EDITOR', 'VIEWER']::VARCHAR[],
     ARRAY['spc_templates.default_member_role']::VARCHAR[]),
    ('SPACE.VISIBILITY', 'Space visibility',
     'Discovery and admission boundary shared by templates, Spaces, and requests.',
     'SECURITY', ARRAY['HIDDEN', 'OPEN', 'PRIVATE', 'REQUEST']::VARCHAR[],
     ARRAY[
        'spc_templates.default_visibility',
        'spc_spaces.visibility',
        'spc_space_requests.requested_visibility'
     ]::VARCHAR[]),
    ('SPACE.TEMPLATE.LIFECYCLE_STATE', 'Space template lifecycle',
     'Authoring, publication, and retirement lifecycle for Space templates.',
     'STATE_MACHINE', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[],
     ARRAY['spc_templates.lifecycle_state']::VARCHAR[]),
    ('SPACE.PURPOSE_TYPE', 'Space purpose type',
     'Stable purpose taxonomy shared by templates and provisioned Spaces.',
     'REFERENCE', ARRAY['COMMUNITY', 'KNOWLEDGE', 'LEADERSHIP', 'OPERATIONS', 'PROJECT']::VARCHAR[],
     ARRAY['spc_templates.purpose_type', 'spc_spaces.purpose_type']::VARCHAR[]),
    ('SPACE.AI_POLICY', 'Space AI policy',
     'Retrieval and assistance boundary for governed AI inside a Space.',
     'SECURITY', ARRAY['DISABLED', 'MEMBER_SCOPED', 'RESTRICTED_SCOPED']::VARCHAR[],
     ARRAY['spc_spaces.ai_policy']::VARCHAR[]),
    ('SPACE.APP_POLICY', 'Space app policy',
     'Approval boundary for apps connected to a Space.',
     'SECURITY', ARRAY['ADMIN_REVIEW', 'OWNER_MANAGED', 'OWNER_REVIEW']::VARCHAR[],
     ARRAY['spc_spaces.app_policy']::VARCHAR[]),
    ('SPACE.CONTENT_POLICY', 'Space content policy',
     'Publication review boundary for Space content.',
     'SECURITY', ARRAY['COMPLIANCE_REVIEW', 'OPEN_PUBLISH', 'OWNER_REVIEW']::VARCHAR[],
     ARRAY['spc_spaces.content_policy']::VARCHAR[]),
    ('SPACE.LIFECYCLE_STATE', 'Space lifecycle',
     'Provisioning through archival and governed deletion lifecycle for a Space.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'ARCHIVED', 'DELETED', 'DELETION_PENDING', 'DRAFT']::VARCHAR[],
     ARRAY['spc_spaces.lifecycle_state']::VARCHAR[]),
    ('SPACE.CREATION_REQUEST.RISK_LEVEL', 'Space request risk level',
     'Risk tier used to route and control Space creation decisions.',
     'SECURITY', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[],
     ARRAY['spc_space_requests.risk_level']::VARCHAR[]),
    ('SPACE.CREATION_REQUEST.STATUS', 'Space creation request status',
     'Decision and provisioning lifecycle for a Space creation request.',
     'STATE_MACHINE', ARRAY['APPROVED', 'CANCELLED', 'PENDING', 'PROVISIONED', 'REJECTED']::VARCHAR[],
     ARRAY['spc_space_requests.status']::VARCHAR[]),
    ('SPACE.MEMBERSHIP.LIFECYCLE_STATE', 'Space membership lifecycle',
     'Admission, expiry, and revocation lifecycle for Space membership.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'EXPIRED', 'PENDING', 'REVOKED']::VARCHAR[],
     ARRAY['spc_memberships.lifecycle_state']::VARCHAR[]),
    ('SPACE.MEMBERSHIP.ROLE', 'Space membership role',
     'Least-privilege collaboration role held by a Space principal.',
     'SECURITY', ARRAY['CONTRIBUTOR', 'EDITOR', 'GUEST', 'MODERATOR', 'OWNER', 'VIEWER']::VARCHAR[],
     ARRAY['spc_memberships.member_role']::VARCHAR[]),
    ('SPACE.MEMBERSHIP.SOURCE', 'Space membership source',
     'Authoritative provenance for a Space membership.',
     'SECURITY', ARRAY['DIRECT', 'GROUP', 'PROVISIONING', 'REQUEST', 'TEMPLATE']::VARCHAR[],
     ARRAY['spc_memberships.membership_source']::VARCHAR[]),
    ('SPACE.PRINCIPAL.TYPE', 'Space principal type',
     'Human or group principal represented by membership and cardinality evidence.',
     'SECURITY', ARRAY['GROUP', 'USER']::VARCHAR[],
     ARRAY['spc_memberships.principal_type', 'spc_principal_cardinalities.principal_type']::VARCHAR[]),
    ('SPACE.CONTENT.TYPE', 'Space content type',
     'Content composition types supported by the Space canvas.',
     'PROTOCOL', ARRAY['APP_EMBED', 'CANVAS', 'DECISION', 'FILE', 'LINK', 'PAGE', 'POST']::VARCHAR[],
     ARRAY['spc_content_items.content_type']::VARCHAR[]),
    ('SPACE.CONTENT.LIFECYCLE_STATE', 'Space content lifecycle',
     'Authoring, review, publication, rejection, and archival lifecycle for Space content.',
     'STATE_MACHINE', ARRAY['ARCHIVED', 'DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED']::VARCHAR[],
     ARRAY['spc_content_items.lifecycle_state']::VARCHAR[]),
    ('SPACE.PUBLICATION_REVIEW.STRATEGY', 'Space publication reviewer strategy',
     'Governed reviewer authority selected for a publication decision.',
     'SECURITY', ARRAY['COMPLIANCE', 'SPACE_MODERATOR', 'SPACE_OWNER']::VARCHAR[],
     ARRAY['spc_publication_reviews.reviewer_strategy']::VARCHAR[]),
    ('SPACE.DECISION.STATUS', 'Space decision status',
     'Common pending and terminal states for publication and access decisions.',
     'STATE_MACHINE', ARRAY['APPROVED', 'CANCELLED', 'PENDING', 'REJECTED']::VARCHAR[],
     ARRAY['spc_publication_reviews.status', 'spc_access_requests.status']::VARCHAR[]),
    ('SPACE.APP_BINDING.DATA_ACCESS_SCOPE', 'Space app data access scope',
     'Data boundary granted to an app bound to a Space.',
     'SECURITY', ARRAY['EXPLICIT_RESOURCE', 'SPACE_ONLY', 'TENANT_READ']::VARCHAR[],
     ARRAY['spc_app_bindings.data_access_scope']::VARCHAR[]),
    ('SPACE.APP_BINDING.LIFECYCLE_STATE', 'Space app binding lifecycle',
     'Review and availability lifecycle for an app bound to a Space.',
     'STATE_MACHINE', ARRAY['ACTIVE', 'PENDING', 'RETIRED', 'SUSPENDED']::VARCHAR[],
     ARRAY['spc_app_bindings.lifecycle_state']::VARCHAR[]),
    ('SPACE.ACTIVITY.ACTOR_TYPE', 'Space activity actor type',
     'Human, agent, and system actors represented in Space activity evidence.',
     'OBSERVABILITY', ARRAY['AGENT', 'SYSTEM', 'USER']::VARCHAR[],
     ARRAY['spc_activity_events.actor_type']::VARCHAR[]),
    ('SPACE.LIFECYCLE_REVIEW.RECOMMENDATION', 'Space lifecycle recommendation',
     'Governed lifecycle recommendation derived from usage and policy evidence.',
     'STATE_MACHINE', ARRAY['ARCHIVE', 'DELETE', 'KEEP', 'REVIEW_ACCESS']::VARCHAR[],
     ARRAY['spc_lifecycle_reviews.recommendation']::VARCHAR[]),
    ('SPACE.LIFECYCLE_REVIEW.TYPE', 'Space lifecycle review type',
     'Control objective represented by a Space lifecycle review.',
     'SECURITY', ARRAY['ACCESS', 'ACTIVITY', 'RETENTION']::VARCHAR[],
     ARRAY['spc_lifecycle_reviews.review_type']::VARCHAR[]),
    ('SPACE.LIFECYCLE_REVIEW.STATUS', 'Space lifecycle review status',
     'Open, overdue, and terminal states for Space lifecycle reviews.',
     'STATE_MACHINE', ARRAY['CANCELLED', 'COMPLETED', 'OPEN', 'OVERDUE']::VARCHAR[],
     ARRAY['spc_lifecycle_reviews.status']::VARCHAR[]),
    ('SPACE.ACCESS_REQUEST.DECISION_MODE', 'Space access decision mode',
     'Policy or owner review path used to decide a Space access request.',
     'SECURITY', ARRAY['AUTO', 'OWNER_REVIEW']::VARCHAR[],
     ARRAY['spc_access_requests.decision_mode']::VARCHAR[]),
    ('SPACE.ACCESS_REQUEST.REQUESTED_ROLE', 'Space access requested role',
     'Least-privilege role available through member self-service access.',
     'SECURITY', ARRAY['CONTRIBUTOR', 'VIEWER']::VARCHAR[],
     ARRAY['spc_access_requests.requested_role']::VARCHAR[]),
    ('SPACE.EVENT.OUTBOX_STATUS', 'Space outbox event status',
     'Delivery lifecycle for Space audit and domain event outboxes.',
     'STATE_MACHINE', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[],
     ARRAY['sys_audit_outbox.status', 'sys_domain_event_outbox.status']::VARCHAR[]),
    ('SPACE.EVENT.INBOX_STATUS', 'Space inbox event status',
     'Idempotent processing lifecycle for inbound Space domain events.',
     'STATE_MACHINE', ARRAY[
        'DEAD', 'DEFERRED', 'DUPLICATE', 'FAILED', 'PROCESSING',
        'RECEIVED', 'REPLAY_PENDING', 'SUCCEEDED'
     ]::VARCHAR[], ARRAY['sys_domain_event_inbox.status']::VARCHAR[]),
    ('SPACE.EVENT.REPLAY_DIRECTION', 'Space event replay direction',
     'Event stream direction represented by replay audit evidence.',
     'OBSERVABILITY', ARRAY['INBOX', 'OUTBOX']::VARCHAR[],
     ARRAY['sys_domain_event_replay_audit.direction']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
SELECT code_set_key,
       'dwp-space-server',
       display_name,
       description,
       'SYSTEM',
       'CHECK',
       source_references[1],
       contract_kind,
       'ADMIN_ONLY'
  FROM tmp_space_code_contract_manifest
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
               WHEN 'ARCHIVE' THEN '보관'
               WHEN 'ARCHIVED' THEN '보관됨'
               WHEN 'APPROVAL' THEN '승인'
               WHEN 'APPROVED' THEN '승인됨'
               WHEN 'AUTO' THEN '자동'
               WHEN 'CANCELLED' THEN '취소됨'
               WHEN 'COMPLETED' THEN '완료됨'
               WHEN 'CONFIDENTIAL' THEN '기밀'
               WHEN 'CRITICAL' THEN '매우 높음'
               WHEN 'DEAD' THEN '처리 중단'
               WHEN 'DEFERRED' THEN '처리 연기'
               WHEN 'DELETE' THEN '삭제'
               WHEN 'DELETED' THEN '삭제됨'
               WHEN 'DELETION_PENDING' THEN '삭제 대기'
               WHEN 'DIRECT' THEN '직접 지정'
               WHEN 'DISABLED' THEN '사용 안 함'
               WHEN 'DRAFT' THEN '초안'
               WHEN 'DUPLICATE' THEN '중복'
               WHEN 'EXPIRED' THEN '만료됨'
               WHEN 'FAILED' THEN '실패'
               WHEN 'HIDDEN' THEN '검색 비공개'
               WHEN 'HIGH' THEN '높음'
               WHEN 'INTERNAL' THEN '내부'
               WHEN 'KEEP' THEN '유지'
               WHEN 'LOW' THEN '낮음'
               WHEN 'MEDIUM' THEN '보통'
               WHEN 'OPEN' THEN '공개'
               WHEN 'OUTBOX' THEN '발신함'
               WHEN 'OVERDUE' THEN '기한 초과'
               WHEN 'PENDING' THEN '대기 중'
               WHEN 'POLICY' THEN '정책 기반'
               WHEN 'PRIVATE' THEN '비공개'
               WHEN 'PROCESSING' THEN '처리 중'
               WHEN 'PROVISIONED' THEN '개설 완료'
               WHEN 'PUBLIC' THEN '공개'
               WHEN 'PUBLISHED' THEN '게시됨'
               WHEN 'RECEIVED' THEN '수신됨'
               WHEN 'REJECTED' THEN '거부됨'
               WHEN 'REPLAY_PENDING' THEN '재처리 대기'
               WHEN 'REQUEST' THEN '승인 후 참여'
               WHEN 'RESTRICTED' THEN '제한'
               WHEN 'RETIRED' THEN '폐기됨'
               WHEN 'REVOKED' THEN '회수됨'
               WHEN 'SENDING' THEN '전송 중'
               WHEN 'SUCCEEDED' THEN '성공'
               WHEN 'SUSPENDED' THEN '중지됨'
               WHEN 'USER' THEN '사용자'
               WHEN 'GROUP' THEN '그룹'
               WHEN 'SYSTEM' THEN '시스템'
               WHEN 'AGENT' THEN '에이전트'
               WHEN 'VIEWER' THEN '열람자'
               WHEN 'CONTRIBUTOR' THEN '기여자'
               WHEN 'EDITOR' THEN '편집자'
               WHEN 'MODERATOR' THEN '운영자'
               WHEN 'OWNER' THEN '소유자'
               WHEN 'GUEST' THEN '게스트'
               ELSE INITCAP(REPLACE(LOWER(value.code), '_', ' '))
           END,
           'en', INITCAP(REPLACE(LOWER(value.code), '_', ' '))),
       value.ordinality * 10,
       '{}'::jsonb,
       'ACTIVE'
  FROM tmp_space_code_contract_manifest manifest
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
       'dwp-space-server',
       'DATABASE_COLUMN',
       binding.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_space_code_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.source_references)
      AS binding(source_reference)
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES
    ('AUTH.SCOPED_ADMIN.RESOURCE_TYPE', 'SPACE', 'Space',
     '{"ko":"Space","en":"Space"}', 20, '{}', 'ACTIVE'),
    ('AUTH.RESOURCE_GRANT.SOURCE_TYPE', 'SPACE_ACCESS_REQUEST', 'Space access request',
     '{"ko":"Space 접근 요청","en":"Space access request"}', 40, '{}', 'ACTIVE'),
    ('AUTH.RESOURCE_GRANT.SOURCE_TYPE', 'SPACE_MEMBERSHIP', 'Space membership',
     '{"ko":"Space 멤버십","en":"Space membership"}', 50, '{}', 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE sys_code_bindings IS
    'Governed bindings from shared code semantics to concrete service database, API, UI, and behavior contracts.';
