-- Persisted JSON and API-only choices are contracts even when they are not
-- represented by a database CHECK in the owning service.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('AUTH.BUILT_IN_ROLE', 'dwp-auth-server', 'Built-in role',
     'Reserved product roles shared across service authorization boundaries.',
     'SYSTEM', 'DOMAIN_CATALOG', 'sys_builtin_role_catalog.role_code', 'SECURITY'),
    ('AUTH.PERMISSION_ACTION', 'dwp-auth-server', 'Permission action',
     'Stable actions assignable to authorization resources.',
     'SYSTEM', 'DOMAIN_CATALOG', 'com_permissions.code', 'SECURITY'),
    ('PEOPLE.APPROVAL_ROLE', 'dwp-people-server', 'Organization approval role',
     'Authentication roles accepted by organization scenario approval workflows.',
     'SYSTEM', 'FOREIGN_KEY', 'ppl_approval_role_catalog.role_code', 'SECURITY'),
    ('PLATFORM.API_HISTORY.WINDOW', 'dwp-platform-server', 'API history window',
     'Supported observation windows for API history queries.',
     'SYSTEM', 'TYPED_CONTRACT', 'ApiHistoryWindow', 'OBSERVABILITY'),
    ('PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER', 'dwp-platform-server',
     'API observation point filter',
     'Observation points and the aggregate filter token accepted by the API history contract.',
     'SYSTEM', 'TYPED_CONTRACT', 'ApiHistoryObservationPoint', 'OBSERVABILITY'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'dwp-platform-server',
     'API outcome filter',
     'API outcomes and the aggregate filter token accepted by the API history contract.',
     'SYSTEM', 'TYPED_CONTRACT', 'ApiHistoryOutcomeFilter', 'OBSERVABILITY'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'dwp-platform-server',
     'HTTP method filter',
     'HTTP methods and the aggregate filter token exposed by API history.',
     'SYSTEM', 'EXTERNAL_STANDARD', 'ApiHistoryHttpMethodFilter', 'PROTOCOL'),
    ('PLATFORM.AUDIT.WINDOW', 'dwp-platform-server', 'Audit window',
     'Supported observation windows for audit control queries.',
     'SYSTEM', 'TYPED_CONTRACT', 'AuditWindow', 'OBSERVABILITY'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'dwp-platform-server', 'Audit category filter',
     'Governed audit categories and the aggregate filter token.',
     'SYSTEM', 'TYPED_CONTRACT', 'AuditCriteria.CATEGORIES', 'OBSERVABILITY'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'dwp-platform-server', 'Audit severity filter',
     'Governed audit severities and the aggregate filter token.',
     'SYSTEM', 'TYPED_CONTRACT', 'AuditCriteria.SEVERITIES', 'OBSERVABILITY'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'dwp-platform-server', 'Audit outcome filter',
     'Governed audit outcomes and the aggregate filter token.',
     'SYSTEM', 'TYPED_CONTRACT', 'AuditCriteria.OUTCOMES', 'OBSERVABILITY'),
    ('PLATFORM.HOME_WIDGET', 'dwp-platform-server', 'Home widget',
     'Deployable widgets accepted by the persisted personal home layout.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.WIDGET_KEYS', 'REFERENCE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('AUTH.BUILT_IN_ROLE', 'ADMIN', 'Administrator', '{"ko":"관리자","en":"Administrator"}', 10, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'PLATFORM_ADMIN', 'Platform administrator', '{"ko":"플랫폼 관리자","en":"Platform administrator"}', 20, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'TENANT_ADMIN', 'Tenant administrator', '{"ko":"테넌트 관리자","en":"Tenant administrator"}', 30, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'WORKSPACE_MEMBER', 'Workspace member', '{"ko":"워크스페이스 구성원","en":"Workspace member"}', 40, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'HR_ADMIN', 'HR administrator', '{"ko":"HR 관리자","en":"HR administrator"}', 50, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'PEOPLE_ADMIN', 'People administrator', '{"ko":"인사 서비스 관리자","en":"People administrator"}', 60, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'AUDITOR', 'Auditor', '{"ko":"감사자","en":"Auditor"}', 70, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'AUDIT_ADMIN', 'Audit administrator', '{"ko":"감사 관리자","en":"Audit administrator"}', 80, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'PROVIDER_ADMIN', 'Provider administrator', '{"ko":"프로바이더 관리자","en":"Provider administrator"}', 90, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'PROVIDER_OPERATOR', 'Provider operator', '{"ko":"프로바이더 운영자","en":"Provider operator"}', 100, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'PROVIDER_SUPPORT', 'Provider support', '{"ko":"프로바이더 지원 담당자","en":"Provider support"}', 110, '{}'),
    ('AUTH.BUILT_IN_ROLE', 'PROVIDER_AUDITOR', 'Provider auditor', '{"ko":"프로바이더 감사자","en":"Provider auditor"}', 120, '{}'),
    ('AUTH.PERMISSION_ACTION', 'VIEW', 'View', '{"ko":"조회","en":"View"}', 10, '{}'),
    ('AUTH.PERMISSION_ACTION', 'CREATE', 'Create', '{"ko":"생성","en":"Create"}', 20, '{}'),
    ('AUTH.PERMISSION_ACTION', 'UPDATE', 'Update', '{"ko":"수정","en":"Update"}', 30, '{}'),
    ('AUTH.PERMISSION_ACTION', 'DELETE', 'Delete', '{"ko":"삭제","en":"Delete"}', 40, '{}'),
    ('AUTH.PERMISSION_ACTION', 'MANAGE', 'Manage', '{"ko":"관리","en":"Manage"}', 50, '{}'),
    ('AUTH.PERMISSION_ACTION', 'EXECUTE', 'Execute', '{"ko":"실행","en":"Execute"}', 60, '{}'),
    ('AUTH.PERMISSION_ACTION', 'APPROVE', 'Approve', '{"ko":"승인","en":"Approve"}', 70, '{}'),
    ('AUTH.PERMISSION_ACTION', 'EXPORT', 'Export', '{"ko":"내보내기","en":"Export"}', 80, '{}'),
    ('PEOPLE.APPROVAL_ROLE', 'HR_ADMIN', 'HR administrator', '{"ko":"HR 관리자","en":"HR administrator"}', 10, '{}'),
    ('PEOPLE.APPROVAL_ROLE', 'PEOPLE_ADMIN', 'People administrator', '{"ko":"인사 서비스 관리자","en":"People administrator"}', 20, '{}'),
    ('PEOPLE.APPROVAL_ROLE', 'TENANT_ADMIN', 'Tenant administrator', '{"ko":"테넌트 관리자","en":"Tenant administrator"}', 30, '{}'),
    ('PEOPLE.APPROVAL_ROLE', 'PLATFORM_ADMIN', 'Platform administrator', '{"ko":"플랫폼 관리자","en":"Platform administrator"}', 40, '{}'),
    ('PEOPLE.APPROVAL_ROLE', 'ADMIN', 'Administrator', '{"ko":"관리자","en":"Administrator"}', 50, '{}'),
    ('PLATFORM.API_HISTORY.WINDOW', 'H1', 'Last hour', '{"ko":"최근 1시간","en":"Last hour"}', 10, '{"durationHours":1}'),
    ('PLATFORM.API_HISTORY.WINDOW', 'H6', 'Last 6 hours', '{"ko":"최근 6시간","en":"Last 6 hours"}', 20, '{"durationHours":6}'),
    ('PLATFORM.API_HISTORY.WINDOW', 'H24', 'Last 24 hours', '{"ko":"최근 24시간","en":"Last 24 hours"}', 30, '{"durationHours":24}'),
    ('PLATFORM.API_HISTORY.WINDOW', 'D7', 'Last 7 days', '{"ko":"최근 7일","en":"Last 7 days"}', 40, '{"durationDays":7}'),
    ('PLATFORM.API_HISTORY.WINDOW', 'D30', 'Last 30 days', '{"ko":"최근 30일","en":"Last 30 days"}', 50, '{"durationDays":30}'),
    ('PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER', 'ALL', 'All observation points', '{"ko":"전체","en":"All"}', 10, '{"aggregate":true}'),
    ('PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER', 'GATEWAY', 'Gateway', '{"ko":"게이트웨이","en":"Gateway"}', 20, '{}'),
    ('PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER', 'SERVICE', 'Service', '{"ko":"서비스","en":"Service"}', 30, '{}'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'ALL', 'All outcomes', '{"ko":"전체","en":"All"}', 10, '{"aggregate":true}'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'SUCCESS', 'Success', '{"ko":"성공","en":"Success"}', 20, '{}'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'REDIRECTION', 'Redirection', '{"ko":"리디렉션","en":"Redirection"}', 30, '{}'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'CLIENT_ERROR', 'Client error', '{"ko":"클라이언트 오류","en":"Client error"}', 40, '{}'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'SERVER_ERROR', 'Server error', '{"ko":"서버 오류","en":"Server error"}', 50, '{}'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'CANCELLED', 'Cancelled', '{"ko":"취소됨","en":"Cancelled"}', 60, '{}'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'ALL', 'All methods', '{"ko":"전체","en":"All"}', 10, '{"aggregate":true}'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'GET', 'GET', '{}', 20, '{}'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'POST', 'POST', '{}', 30, '{}'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'PUT', 'PUT', '{}', 40, '{}'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'PATCH', 'PATCH', '{}', 50, '{}'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'DELETE', 'DELETE', '{}', 60, '{}'),
    ('PLATFORM.AUDIT.WINDOW', 'H24', 'Last 24 hours', '{"ko":"최근 24시간","en":"Last 24 hours"}', 10, '{"durationHours":24}'),
    ('PLATFORM.AUDIT.WINDOW', 'D7', 'Last 7 days', '{"ko":"최근 7일","en":"Last 7 days"}', 20, '{"durationDays":7}'),
    ('PLATFORM.AUDIT.WINDOW', 'D30', 'Last 30 days', '{"ko":"최근 30일","en":"Last 30 days"}', 30, '{"durationDays":30}'),
    ('PLATFORM.AUDIT.WINDOW', 'D90', 'Last 90 days', '{"ko":"최근 90일","en":"Last 90 days"}', 40, '{"durationDays":90}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'ALL', 'All categories', '{"ko":"전체","en":"All"}', 10, '{"aggregate":true}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'ADMIN_CHANGE', 'Administrative change', '{"ko":"관리 변경","en":"Administrative change"}', 20, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'AUTHENTICATION', 'Authentication', '{"ko":"인증","en":"Authentication"}', 30, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'AUTHORIZATION', 'Authorization', '{"ko":"인가","en":"Authorization"}', 40, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'DATA_ACCESS', 'Data access', '{"ko":"데이터 접근","en":"Data access"}', 50, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'DATA_EXPORT', 'Data export', '{"ko":"데이터 내보내기","en":"Data export"}', 60, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'PROVISIONING', 'Provisioning', '{"ko":"프로비저닝","en":"Provisioning"}', 70, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'AI_ACTION', 'AI action', '{"ko":"AI 작업","en":"AI action"}', 80, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'POLICY_DENIED', 'Policy denied', '{"ko":"정책 거부","en":"Policy denied"}', 90, '{}'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'SYSTEM_EVENT', 'System event', '{"ko":"시스템 이벤트","en":"System event"}', 100, '{}'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'ALL', 'All severities', '{"ko":"전체","en":"All"}', 10, '{"aggregate":true}'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'INFO', 'Information', '{"ko":"정보","en":"Information"}', 20, '{}'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'LOW', 'Low', '{"ko":"낮음","en":"Low"}', 30, '{}'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'MEDIUM', 'Medium', '{"ko":"보통","en":"Medium"}', 40, '{}'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'HIGH', 'High', '{"ko":"높음","en":"High"}', 50, '{}'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'CRITICAL', 'Critical', '{"ko":"심각","en":"Critical"}', 60, '{}'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'ALL', 'All outcomes', '{"ko":"전체","en":"All"}', 10, '{"aggregate":true}'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'SUCCESS', 'Success', '{"ko":"성공","en":"Success"}', 20, '{}'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'DENIED', 'Denied', '{"ko":"거부","en":"Denied"}', 30, '{}'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'FAILED', 'Failed', '{"ko":"실패","en":"Failed"}', 40, '{}'),
    ('PLATFORM.HOME_WIDGET', 'announcements', 'Announcements', '{"ko":"공지사항","en":"Announcements"}', 10, '{"canHide":false}'),
    ('PLATFORM.HOME_WIDGET', 'daily-brief', 'Daily brief', '{"ko":"데일리 브리프","en":"Daily brief"}', 20, '{"canHide":true}'),
    ('PLATFORM.HOME_WIDGET', 'focus', 'Focus now', '{"ko":"집중 업무","en":"Focus now"}', 30, '{"canHide":true}'),
    ('PLATFORM.HOME_WIDGET', 'schedule', 'Schedule', '{"ko":"일정","en":"Schedule"}', 40, '{"canHide":true}'),
    ('PLATFORM.HOME_WIDGET', 'activity', 'Live activity', '{"ko":"실시간 활동","en":"Live activity"}', 50, '{"canHide":true}');

-- The personal preference API stores lower-case JSON values. Keep registry
-- codes byte-for-byte identical to the runtime contract.
UPDATE sys_code_values
   SET code = LOWER(code), updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key IN (
       'PLATFORM.PREFERENCE.COLOR_MODE',
       'PLATFORM.PREFERENCE.DENSITY');

UPDATE sys_code_sets
   SET source_reference = 'PersonalPreferenceService.MODES / appearance.mode',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';

UPDATE sys_code_sets
   SET source_reference = 'PersonalPreferenceService.DENSITIES / appearance.density',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.PREFERENCE.DENSITY';

UPDATE sys_code_bindings binding
   SET usage_type = 'API_CONTRACT', updated_at = CURRENT_TIMESTAMP
  FROM sys_code_sets code_set
 WHERE binding.code_set_key = code_set.code_set_key
   AND code_set.validation_source = 'TYPED_CONTRACT'
   AND binding.usage_type = 'DATABASE_COLUMN'
   AND binding.enforcement_type = 'TYPED_CONTRACT';

UPDATE sys_code_bindings
   SET source_reference = 'PersonalPreferenceService.MODES / appearance.mode',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE'
   AND consumer_service = 'dwp-platform-server';

UPDATE sys_code_bindings
   SET source_reference = 'PersonalPreferenceService.DENSITIES / appearance.density',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.PREFERENCE.DENSITY'
   AND consumer_service = 'dwp-platform-server';

DELETE FROM sys_code_bindings
 WHERE code_set_key = 'PEOPLE.ORGANIZATION_ROLE'
   AND source_reference = 'ppl_organization_scenario_approvals.required_role_code';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('AUTH.BUILT_IN_ROLE', 'dwp-auth-server', 'DATABASE_COLUMN', 'com_roles.builtin_role_code', 'FOREIGN_KEY'),
    ('AUTH.BUILT_IN_ROLE', 'dwp-auth-server', 'BEHAVIOR', 'AuthenticatedUserResolver.ADMIN_ROLES', 'TYPED_CONTRACT'),
    ('AUTH.BUILT_IN_ROLE', 'dwp-platform-server', 'BEHAVIOR', 'PlatformSecurityFilter.ADMIN_ROLES', 'TYPED_CONTRACT'),
    ('AUTH.BUILT_IN_ROLE', 'dwp-people-server', 'BEHAVIOR', 'PeopleSecurityFilter and organization authorization', 'TYPED_CONTRACT'),
    ('AUTH.BUILT_IN_ROLE', 'dwp-provider-server', 'BEHAVIOR', 'ProviderSecurityFilter.PROVIDER_ROLES', 'TYPED_CONTRACT'),
    ('AUTH.BUILT_IN_ROLE', 'dwp-frontend', 'BEHAVIOR', 'routes/sections and account-menu role guards', 'TYPED_CONTRACT'),
    ('AUTH.PERMISSION_ACTION', 'dwp-auth-server', 'DATABASE_COLUMN', 'com_role_permissions.permission_id', 'FOREIGN_KEY'),
    ('AUTH.PERMISSION_ACTION', 'dwp-frontend', 'BEHAVIOR', 'usePermissions permission action', 'TYPED_CONTRACT'),
    ('PEOPLE.APPROVAL_ROLE', 'dwp-people-server', 'DATABASE_COLUMN', 'ppl_organization_scenario_approvals.required_role_code', 'FOREIGN_KEY'),
    ('PEOPLE.APPROVAL_ROLE', 'dwp-people-server', 'BEHAVIOR', 'OrganizationScenarioService approval role guards', 'TYPED_CONTRACT'),
    ('PLATFORM.API_HISTORY.WINDOW', 'dwp-platform-server', 'API_CONTRACT', 'ApiHistoryWindow', 'TYPED_CONTRACT'),
    ('PLATFORM.API_HISTORY.WINDOW', 'dwp-frontend', 'UI_SELECTION', 'api-monitoring/window', 'CATALOG_LOOKUP'),
    ('PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER', 'dwp-platform-server', 'API_CONTRACT', 'ApiHistoryObservationPoint', 'TYPED_CONTRACT'),
    ('PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER', 'dwp-frontend', 'UI_SELECTION', 'api-monitoring/observationPoint', 'CATALOG_LOOKUP'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'dwp-platform-server', 'API_CONTRACT', 'ApiHistoryOutcomeFilter', 'TYPED_CONTRACT'),
    ('PLATFORM.API_HISTORY.OUTCOME_FILTER', 'dwp-frontend', 'UI_SELECTION', 'api-monitoring/outcome', 'CATALOG_LOOKUP'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'dwp-platform-server', 'API_CONTRACT', 'ApiHistoryHttpMethodFilter', 'TYPED_CONTRACT'),
    ('PLATFORM.API_HISTORY.HTTP_METHOD_FILTER', 'dwp-frontend', 'UI_SELECTION', 'api-monitoring/httpMethod', 'CATALOG_LOOKUP'),
    ('PLATFORM.AUDIT.WINDOW', 'dwp-platform-server', 'API_CONTRACT', 'AuditWindow', 'TYPED_CONTRACT'),
    ('PLATFORM.AUDIT.WINDOW', 'dwp-frontend', 'UI_SELECTION', 'audit-overview/window', 'CATALOG_LOOKUP'),
    ('PLATFORM.AUDIT.WINDOW', 'dwp-frontend', 'UI_SELECTION', 'audit-explorer/window', 'CATALOG_LOOKUP'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'dwp-platform-server', 'API_CONTRACT', 'AuditCriteria.CATEGORIES', 'TYPED_CONTRACT'),
    ('PLATFORM.AUDIT.CATEGORY_FILTER', 'dwp-frontend', 'UI_SELECTION', 'audit-explorer/category', 'CATALOG_LOOKUP'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'dwp-platform-server', 'API_CONTRACT', 'AuditCriteria.SEVERITIES', 'TYPED_CONTRACT'),
    ('PLATFORM.AUDIT.SEVERITY_FILTER', 'dwp-frontend', 'UI_SELECTION', 'audit-explorer/severity', 'CATALOG_LOOKUP'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'dwp-platform-server', 'API_CONTRACT', 'AuditCriteria.OUTCOMES', 'TYPED_CONTRACT'),
    ('PLATFORM.AUDIT.OUTCOME_FILTER', 'dwp-frontend', 'UI_SELECTION', 'audit-explorer/outcome', 'CATALOG_LOOKUP'),
    ('PLATFORM.HOME_WIDGET', 'dwp-platform-server', 'API_CONTRACT', 'HomePreferenceService.WIDGET_KEYS', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_WIDGET', 'dwp-frontend', 'BEHAVIOR', 'home-widget-registry', 'CATALOG_LOOKUP'),
    ('PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.RISK_TIER', 'dwp-agent-runtime', 'API_CONTRACT', 'agent-plan/riskTier', 'TYPED_CONTRACT'),
    ('PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.RISK_TIER', 'dwp-frontend', 'BEHAVIOR', 'agent-plan-api/AgentRiskTier', 'TYPED_CONTRACT'),
    ('PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT', 'dwp-platform-server', 'API_CONTRACT', 'AuditControlService export format', 'TYPED_CONTRACT'),
    ('PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT', 'dwp-frontend', 'UI_SELECTION', 'audit-explorer/exportFormat', 'CATALOG_LOOKUP');
