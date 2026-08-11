-- Register provider data-governance machine values in the global product contract registry.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PROVIDER.DATA_ASSET.LIFECYCLE_STATE', 'dwp-provider-server',
     'Data asset lifecycle', 'Governed lifecycle of a curated database asset annotation.',
     'SYSTEM', 'CHECK', 'prv_data_asset_annotations.lifecycle_state', 'STATE_MACHINE'),
    ('PROVIDER.DATA_ASSET.CRITICALITY', 'dwp-provider-server',
     'Data asset criticality', 'Operational impact tier assigned to a governed data asset.',
     'SYSTEM', 'CHECK', 'prv_data_asset_annotations.criticality', 'REFERENCE'),
    ('PROVIDER.DATA_ASSET.CLASSIFICATION', 'dwp-provider-server',
     'Data asset classification', 'Maximum information sensitivity of a governed data asset.',
     'SYSTEM', 'CHECK', 'prv_data_asset_annotations.data_classification', 'SECURITY'),
    ('PROVIDER.DATA_ASSET.REVIEW_STATE', 'dwp-provider-server',
     'Data asset review state', 'Stewardship review state for discovered and curated assets.',
     'SYSTEM', 'CHECK', 'prv_data_asset_annotations.review_state', 'STATE_MACHINE'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'dwp-provider-server',
     'Data lineage edge type', 'Process mechanism represented by a cross-service lineage edge.',
     'SYSTEM', 'CHECK', 'prv_data_lineage_edges.edge_type', 'PROTOCOL'),
    ('PROVIDER.DATA_LINEAGE.LIFECYCLE_STATE', 'dwp-provider-server',
     'Data lineage lifecycle', 'Availability lifecycle for a curated lineage contract.',
     'SYSTEM', 'CHECK', 'prv_data_lineage_edges.lifecycle_state', 'STATE_MACHINE'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_SEVERITY', 'dwp-provider-server',
     'Schema finding severity', 'Prioritization level returned by the data-governance scan API.',
     'SYSTEM', 'TYPED_CONTRACT', 'DataGovernanceDtos.Finding.severity', 'OBSERVABILITY'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'dwp-provider-server',
     'Schema finding category', 'Stable diagnostic category returned by the data-governance scan API.',
     'SYSTEM', 'TYPED_CONTRACT', 'DataGovernanceDtos.Finding.category', 'OBSERVABILITY'),
    ('PROVIDER.DATA_GOVERNANCE.SOURCE_STATUS', 'dwp-provider-server',
     'Metadata source status', 'Availability state of a configured metadata source.',
     'SYSTEM', 'TYPED_CONTRACT', 'DataGovernanceDtos.DatabaseSummary.status', 'OBSERVABILITY'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'dwp-provider-server',
     'Provider audit event category', 'Canonical operating domain of a provider audit event.',
     'SYSTEM', 'CHECK', 'prv_audit_events.event_category', 'OBSERVABILITY')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PROVIDER.DATA_ASSET.LIFECYCLE_STATE', 'ACTIVE', 'Active', '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('PROVIDER.DATA_ASSET.LIFECYCLE_STATE', 'PLANNED', 'Planned', '{"ko":"계획","en":"Planned"}', 20, '{}'),
    ('PROVIDER.DATA_ASSET.LIFECYCLE_STATE', 'DEPRECATED', 'Deprecated', '{"ko":"사용 중단 예정","en":"Deprecated"}', 30, '{}'),
    ('PROVIDER.DATA_ASSET.LIFECYCLE_STATE', 'RETIRED', 'Retired', '{"ko":"종료","en":"Retired"}', 40, '{}'),
    ('PROVIDER.DATA_ASSET.CRITICALITY', 'LOW', 'Low', '{"ko":"낮음","en":"Low"}', 10, '{}'),
    ('PROVIDER.DATA_ASSET.CRITICALITY', 'MEDIUM', 'Medium', '{"ko":"보통","en":"Medium"}', 20, '{}'),
    ('PROVIDER.DATA_ASSET.CRITICALITY', 'HIGH', 'High', '{"ko":"높음","en":"High"}', 30, '{}'),
    ('PROVIDER.DATA_ASSET.CRITICALITY', 'CRITICAL', 'Critical', '{"ko":"핵심","en":"Critical"}', 40, '{}'),
    ('PROVIDER.DATA_ASSET.CLASSIFICATION', 'PUBLIC', 'Public', '{"ko":"공개","en":"Public"}', 10, '{}'),
    ('PROVIDER.DATA_ASSET.CLASSIFICATION', 'INTERNAL', 'Internal', '{"ko":"내부","en":"Internal"}', 20, '{}'),
    ('PROVIDER.DATA_ASSET.CLASSIFICATION', 'CONFIDENTIAL', 'Confidential', '{"ko":"기밀","en":"Confidential"}', 30, '{}'),
    ('PROVIDER.DATA_ASSET.CLASSIFICATION', 'RESTRICTED', 'Restricted', '{"ko":"제한","en":"Restricted"}', 40, '{}'),
    ('PROVIDER.DATA_ASSET.REVIEW_STATE', 'DISCOVERED', 'Discovered', '{"ko":"발견","en":"Discovered"}', 10, '{}'),
    ('PROVIDER.DATA_ASSET.REVIEW_STATE', 'REVIEW_REQUIRED', 'Review required', '{"ko":"검토 필요","en":"Review required"}', 20, '{}'),
    ('PROVIDER.DATA_ASSET.REVIEW_STATE', 'VERIFIED', 'Verified', '{"ko":"검증 완료","en":"Verified"}', 30, '{}'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'PROVISIONING', 'Provisioning', '{"ko":"프로비저닝","en":"Provisioning"}', 10, '{}'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'EVENT', 'Event', '{"ko":"이벤트","en":"Event"}', 20, '{}'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'REPLICATION', 'Replication', '{"ko":"복제","en":"Replication"}', 30, '{}'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'REFERENCE', 'Reference', '{"ko":"참조","en":"Reference"}', 40, '{}'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'AGGREGATION', 'Aggregation', '{"ko":"집계","en":"Aggregation"}', 50, '{}'),
    ('PROVIDER.DATA_LINEAGE.LIFECYCLE_STATE', 'ACTIVE', 'Active', '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('PROVIDER.DATA_LINEAGE.LIFECYCLE_STATE', 'RETIRED', 'Retired', '{"ko":"종료","en":"Retired"}', 20, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_SEVERITY', 'CRITICAL', 'Critical', '{"ko":"치명","en":"Critical"}', 10, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_SEVERITY', 'HIGH', 'High', '{"ko":"높음","en":"High"}', 20, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_SEVERITY', 'MEDIUM', 'Medium', '{"ko":"보통","en":"Medium"}', 30, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_SEVERITY', 'LOW', 'Low', '{"ko":"낮음","en":"Low"}', 40, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'SOURCE_UNAVAILABLE', 'Source unavailable', '{"ko":"원본 연결 불가","en":"Source unavailable"}', 10, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'OWNERSHIP_REVIEW', 'Ownership review', '{"ko":"소유권 검토","en":"Ownership review"}', 20, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'MISSING_PRIMARY_KEY', 'Missing primary key', '{"ko":"기본키 누락","en":"Missing primary key"}', 30, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'MISSING_DOCUMENTATION', 'Missing documentation', '{"ko":"문서화 누락","en":"Missing documentation"}', 40, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'TIMEZONE_AMBIGUITY', 'Time-zone ambiguity', '{"ko":"시간대 모호성","en":"Time-zone ambiguity"}', 50, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'UNINDEXED_FOREIGN_KEY', 'Unindexed foreign key', '{"ko":"외래키 인덱스 검토","en":"Unindexed foreign key"}', 60, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'DUPLICATE_FOREIGN_KEY', 'Duplicate foreign key', '{"ko":"중복 외래키","en":"Duplicate foreign key"}', 70, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'ANNOTATION_DRIFT', 'Annotation drift', '{"ko":"주석 드리프트","en":"Annotation drift"}', 80, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'LINEAGE_DRIFT', 'Lineage drift', '{"ko":"리니지 드리프트","en":"Lineage drift"}', 90, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.SOURCE_STATUS', 'AVAILABLE', 'Available', '{"ko":"정상","en":"Available"}', 10, '{}'),
    ('PROVIDER.DATA_GOVERNANCE.SOURCE_STATUS', 'UNAVAILABLE', 'Unavailable', '{"ko":"연결 불가","en":"Unavailable"}', 20, '{}'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'ADMINISTRATION', 'Administration', '{"ko":"관리","en":"Administration"}', 10, '{}'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'PRIVILEGED_ACCESS', 'Privileged access', '{"ko":"특권 접근","en":"Privileged access"}', 20, '{}'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'SERVICE_HEALTH', 'Service health', '{"ko":"서비스 상태","en":"Service health"}', 30, '{}'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'CHANGE_MANAGEMENT', 'Change management', '{"ko":"변경 관리","en":"Change management"}', 40, '{}'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'TENANT_LIFECYCLE', 'Tenant lifecycle', '{"ko":"테넌트 수명주기","en":"Tenant lifecycle"}', 50, '{}'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'DATA_GOVERNANCE', 'Data governance', '{"ko":"데이터 거버넌스","en":"Data governance"}', 60, '{}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
VALUES (
    'PROVIDER.PERMISSION', 'DATA_GOVERNANCE_READ', 'Read data governance catalog',
    '{"ko":"데이터 거버넌스 조회","en":"Read data governance catalog"}',
    '{"riskTier":"L2","scope":"GLOBAL_PRODUCT"}', 37, TRUE, 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    behavior_metadata = EXCLUDED.behavior_metadata,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PROVIDER.DATA_ASSET.LIFECYCLE_STATE', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_data_asset_annotations.lifecycle_state', 'CHECK'),
    ('PROVIDER.DATA_ASSET.CRITICALITY', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_data_asset_annotations.criticality', 'CHECK'),
    ('PROVIDER.DATA_ASSET.CLASSIFICATION', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_data_asset_annotations.data_classification', 'CHECK'),
    ('PROVIDER.DATA_ASSET.REVIEW_STATE', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_data_asset_annotations.review_state', 'CHECK'),
    ('PROVIDER.DATA_LINEAGE.EDGE_TYPE', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_data_lineage_edges.edge_type', 'CHECK'),
    ('PROVIDER.DATA_LINEAGE.LIFECYCLE_STATE', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_data_lineage_edges.lifecycle_state', 'CHECK'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_SEVERITY', 'dwp-provider-server', 'API_CONTRACT', 'DataGovernanceDtos.Finding.severity', 'TYPED_CONTRACT'),
    ('PROVIDER.DATA_GOVERNANCE.FINDING_CATEGORY', 'dwp-provider-server', 'API_CONTRACT', 'DataGovernanceDtos.Finding.category', 'TYPED_CONTRACT'),
    ('PROVIDER.DATA_GOVERNANCE.SOURCE_STATUS', 'dwp-provider-server', 'API_CONTRACT', 'DataGovernanceDtos.DatabaseSummary.status', 'TYPED_CONTRACT'),
    ('PROVIDER.AUDIT_EVENT_CATEGORY', 'dwp-provider-server', 'DATABASE_COLUMN', 'prv_audit_events.event_category', 'CHECK')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;
