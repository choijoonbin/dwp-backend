INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.SCOPE_TYPE', 'dwp-auth-server',
     'Access review scope', 'Population selected for an access review campaign.',
     'SYSTEM', 'CHECK', 'com_access_review_campaigns.scope_type', 'SECURITY'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.REVIEWER_STRATEGY', 'dwp-auth-server',
     'Access review reviewer strategy', 'Authority responsible for review decisions.',
     'SYSTEM', 'CHECK', 'com_access_review_campaigns.reviewer_strategy', 'SECURITY'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.LIFECYCLE_STATE', 'dwp-auth-server',
     'Access review campaign lifecycle', 'Lifecycle of an access certification campaign.',
     'SYSTEM', 'CHECK', 'com_access_review_campaigns.lifecycle_state', 'STATE_MACHINE'),
    ('AUTH.ACCESS_REVIEW_ITEM.ACCESS_SOURCE_TYPE', 'dwp-auth-server',
     'Access review source', 'Direct or inherited source of reviewed access.',
     'SYSTEM', 'CHECK', 'com_access_review_items.access_source_type', 'SECURITY'),
    ('AUTH.ACCESS_REVIEW_ITEM.DECISION', 'dwp-auth-server',
     'Access review decision', 'Reviewer certification decision.',
     'SYSTEM', 'CHECK', 'com_access_review_items.decision', 'STATE_MACHINE'),
    ('AUTH.ACCESS_REVIEW_ITEM.REMEDIATION_STATE', 'dwp-auth-server',
     'Access review remediation state', 'Execution state of a revoke decision.',
     'SYSTEM', 'CHECK', 'com_access_review_items.remediation_state', 'STATE_MACHINE'),
    ('AUTH.ACCESS_REMEDIATION_TASK.ACTION_TYPE', 'dwp-auth-server',
     'Access remediation action', 'Governed action required to remove reviewed access.',
     'SYSTEM', 'CHECK', 'sys_access_remediation_tasks.action_type', 'SECURITY'),
    ('AUTH.ACCESS_REMEDIATION_TASK.LIFECYCLE_STATE', 'dwp-auth-server',
     'Access remediation lifecycle', 'Lifecycle of an access remediation task.',
     'SYSTEM', 'CHECK', 'sys_access_remediation_tasks.lifecycle_state', 'STATE_MACHINE'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.LIFECYCLE_TYPE', 'dwp-auth-server',
     'Identity lifecycle type', 'Joiner, mover, leaver and related identity transitions.',
     'SYSTEM', 'CHECK', 'sys_identity_lifecycle_events.lifecycle_type', 'STATE_MACHINE'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.SOURCE_TYPE', 'dwp-auth-server',
     'Identity lifecycle source', 'Authoritative source of an identity lifecycle event.',
     'SYSTEM', 'CHECK', 'sys_identity_lifecycle_events.source_type', 'PROTOCOL'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.PROCESSING_STATE', 'dwp-auth-server',
     'Identity lifecycle processing state', 'Outcome of lifecycle event application.',
     'SYSTEM', 'CHECK', 'sys_identity_lifecycle_events.processing_state', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.SCOPE_TYPE', 'TENANT', 'Tenant', '{"ko":"테넌트","en":"Tenant"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.SCOPE_TYPE', 'ROLE', 'Role', '{"ko":"역할","en":"Role"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.SCOPE_TYPE', 'GROUP', 'Group', '{"ko":"그룹","en":"Group"}', 30, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.REVIEWER_STRATEGY', 'TENANT_ADMIN', 'Tenant administrator', '{"ko":"테넌트 관리자","en":"Tenant administrator"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.REVIEWER_STRATEGY', 'NAMED_REVIEWER', 'Named reviewer', '{"ko":"지정 검토자","en":"Named reviewer"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.LIFECYCLE_STATE', 'DRAFT', 'Draft', '{"ko":"초안","en":"Draft"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.LIFECYCLE_STATE', 'ACTIVE', 'Active', '{"ko":"진행 중","en":"Active"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.LIFECYCLE_STATE', 'COMPLETED', 'Completed', '{"ko":"완료","en":"Completed"}', 30, '{}'),
    ('AUTH.ACCESS_REVIEW_CAMPAIGN.LIFECYCLE_STATE', 'CANCELLED', 'Cancelled', '{"ko":"취소","en":"Cancelled"}', 40, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.ACCESS_SOURCE_TYPE', 'DIRECT', 'Direct', '{"ko":"직접","en":"Direct"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.ACCESS_SOURCE_TYPE', 'GROUP', 'Group', '{"ko":"그룹 상속","en":"Group"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.DECISION', 'PENDING', 'Pending', '{"ko":"대기","en":"Pending"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.DECISION', 'APPROVE', 'Approve', '{"ko":"유지","en":"Approve"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.DECISION', 'REVOKE', 'Revoke', '{"ko":"회수","en":"Revoke"}', 30, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.REMEDIATION_STATE', 'NOT_REQUIRED', 'Not required', '{"ko":"불필요","en":"Not required"}', 10, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.REMEDIATION_STATE', 'PENDING', 'Pending', '{"ko":"대기","en":"Pending"}', 20, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.REMEDIATION_STATE', 'APPLIED', 'Applied', '{"ko":"적용","en":"Applied"}', 30, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.REMEDIATION_STATE', 'MANUAL_REQUIRED', 'Manual required', '{"ko":"수동 조치 필요","en":"Manual required"}', 40, '{}'),
    ('AUTH.ACCESS_REVIEW_ITEM.REMEDIATION_STATE', 'FAILED', 'Failed', '{"ko":"실패","en":"Failed"}', 50, '{}'),
    ('AUTH.ACCESS_REMEDIATION_TASK.ACTION_TYPE', 'REMOVE_DIRECT_ROLE', 'Remove direct role', '{"ko":"직접 역할 회수","en":"Remove direct role"}', 10, '{}'),
    ('AUTH.ACCESS_REMEDIATION_TASK.ACTION_TYPE', 'REVIEW_GROUP_MEMBERSHIP', 'Review group membership', '{"ko":"그룹 멤버십 검토","en":"Review group membership"}', 20, '{}'),
    ('AUTH.ACCESS_REMEDIATION_TASK.LIFECYCLE_STATE', 'OPEN', 'Open', '{"ko":"열림","en":"Open"}', 10, '{}'),
    ('AUTH.ACCESS_REMEDIATION_TASK.LIFECYCLE_STATE', 'COMPLETED', 'Completed', '{"ko":"완료","en":"Completed"}', 20, '{}'),
    ('AUTH.ACCESS_REMEDIATION_TASK.LIFECYCLE_STATE', 'FAILED', 'Failed', '{"ko":"실패","en":"Failed"}', 30, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.LIFECYCLE_TYPE', 'JOINER', 'Joiner', '{"ko":"입사","en":"Joiner"}', 10, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.LIFECYCLE_TYPE', 'MOVER', 'Mover', '{"ko":"이동","en":"Mover"}', 20, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.LIFECYCLE_TYPE', 'LEAVER', 'Leaver', '{"ko":"퇴사","en":"Leaver"}', 30, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.LIFECYCLE_TYPE', 'REHIRE', 'Rehire', '{"ko":"재입사","en":"Rehire"}', 40, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.LIFECYCLE_TYPE', 'UPDATE', 'Update', '{"ko":"정보 변경","en":"Update"}', 50, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.SOURCE_TYPE', 'HRIS', 'HRIS', '{"ko":"HRIS","en":"HRIS"}', 10, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.SOURCE_TYPE', 'SCIM', 'SCIM', '{"ko":"SCIM","en":"SCIM"}', 20, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.SOURCE_TYPE', 'LOCAL', 'Local', '{"ko":"로컬","en":"Local"}', 30, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.PROCESSING_STATE', 'APPLIED', 'Applied', '{"ko":"적용","en":"Applied"}', 10, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.PROCESSING_STATE', 'PARTIAL', 'Partial', '{"ko":"부분 적용","en":"Partial"}', 20, '{}'),
    ('AUTH.IDENTITY_LIFECYCLE_EVENT.PROCESSING_STATE', 'FAILED', 'Failed', '{"ko":"실패","en":"Failed"}', 30, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
SELECT code_set_key, 'dwp-auth-server', 'DATABASE_COLUMN', source_reference, 'CHECK'
FROM sys_code_sets
WHERE code_set_key LIKE 'AUTH.ACCESS_REVIEW_%'
   OR code_set_key LIKE 'AUTH.ACCESS_REMEDIATION_%'
   OR code_set_key LIKE 'AUTH.IDENTITY_LIFECYCLE_%';
