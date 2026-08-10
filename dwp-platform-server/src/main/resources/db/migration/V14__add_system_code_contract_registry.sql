CREATE TABLE sys_code_sets (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    configuration_level VARCHAR(20) NOT NULL,
    validation_source VARCHAR(30) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_code_sets_key
        CHECK (code_set_key = UPPER(BTRIM(code_set_key))
            AND code_set_key ~ '^[A-Z][A-Z0-9_.]{2,99}$'),
    CONSTRAINT ck_sys_code_sets_configuration
        CHECK (configuration_level IN ('SYSTEM', 'EXTENSIBLE', 'USER')),
    CONSTRAINT ck_sys_code_sets_validation
        CHECK (validation_source IN (
            'CHECK', 'FOREIGN_KEY', 'DOMAIN_CATALOG',
            'TYPED_CONTRACT', 'EXTERNAL_STANDARD')),
    CONSTRAINT ck_sys_code_sets_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_sys_code_sets_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE sys_code_values (
    code_set_key VARCHAR(100) NOT NULL REFERENCES sys_code_sets(code_set_key),
    code VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    label_i18n JSONB NOT NULL DEFAULT '{}'::jsonb,
    behavior_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    sort_order INTEGER NOT NULL DEFAULT 0,
    predefined BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    introduced_schema_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (code_set_key, code),
    CONSTRAINT ck_sys_code_values_code
        CHECK (code = BTRIM(code) AND LENGTH(code) BETWEEN 1 AND 100),
    CONSTRAINT ck_sys_code_values_labels
        CHECK (jsonb_typeof(label_i18n) = 'object'),
    CONSTRAINT ck_sys_code_values_behavior
        CHECK (jsonb_typeof(behavior_metadata) = 'object'),
    CONSTRAINT ck_sys_code_values_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_sys_code_values_schema_version
        CHECK (introduced_schema_version > 0)
);

CREATE TABLE sys_code_bindings (
    code_binding_id BIGSERIAL PRIMARY KEY,
    code_set_key VARCHAR(100) NOT NULL REFERENCES sys_code_sets(code_set_key),
    consumer_service VARCHAR(80) NOT NULL,
    usage_type VARCHAR(30) NOT NULL,
    source_reference VARCHAR(300) NOT NULL,
    enforcement_type VARCHAR(30) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_code_bindings_usage
        UNIQUE (code_set_key, consumer_service, usage_type, source_reference),
    CONSTRAINT ck_sys_code_bindings_usage
        CHECK (usage_type IN (
            'DATABASE_COLUMN', 'API_CONTRACT', 'UI_SELECTION', 'BEHAVIOR')),
    CONSTRAINT ck_sys_code_bindings_enforcement
        CHECK (enforcement_type IN (
            'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT')),
    CONSTRAINT ck_sys_code_bindings_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference)
VALUES
    ('AUTH.LOGIN_TYPE', 'dwp-auth-server', 'Login type', 'Interactive login choices.', 'SYSTEM', 'FOREIGN_KEY', 'sys_login_type_catalog.login_type'),
    ('AUTH.IDENTITY_PROVIDER_TYPE', 'dwp-auth-server', 'Identity provider type', 'Identity account protocol types.', 'SYSTEM', 'FOREIGN_KEY', 'sys_identity_provider_type_catalog.provider_type'),
    ('AUTH.ROLE_TYPE', 'dwp-auth-server', 'Role type', 'System and tenant-created role classes.', 'SYSTEM', 'CHECK', 'com_roles.role_type'),
    ('AUTH.ROLE_STATUS', 'dwp-auth-server', 'Role status', 'Assignable role availability.', 'SYSTEM', 'CHECK', 'com_roles.status'),
    ('AUTH.PERMISSION_EFFECT', 'dwp-auth-server', 'Permission effect', 'Access grant or explicit denial.', 'SYSTEM', 'CHECK', 'com_role_permissions.effect'),
    ('AUTH.RESOURCE_TYPE', 'dwp-auth-server', 'Resource type', 'Authorization resource classes.', 'EXTENSIBLE', 'FOREIGN_KEY', 'com_resource_type_catalog.resource_type'),
    ('AUTH.GROUP_ASSIGNMENT_TYPE', 'dwp-auth-server', 'Group assignment type', 'Active and eligible group grants.', 'SYSTEM', 'CHECK', 'com_group_role_assignments.assignment_type'),
    ('AUTH.ACCESS_SCOPE', 'dwp-auth-server', 'Access scope', 'Tenant, organization, and resource grant scopes.', 'SYSTEM', 'CHECK', 'com_group_role_assignments.scope_type'),
    ('PEOPLE.HRIS_SOURCE_TYPE', 'dwp-people-server', 'HRIS source type', 'Supported workforce source systems.', 'EXTENSIBLE', 'CHECK', 'int_source_systems.system_type'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'dwp-people-server', 'HRIS connector type', 'Supported workforce connector adapters.', 'EXTENSIBLE', 'CHECK', 'int_connector_instances.connector_type'),
    ('PEOPLE.HRIS_AUTH_MODE', 'dwp-people-server', 'Connector authentication mode', 'Credential transport used by HRIS connectors.', 'SYSTEM', 'CHECK', 'int_connector_instances.auth_mode'),
    ('PEOPLE.SYNC_MODE', 'dwp-people-server', 'Synchronization mode', 'Workforce synchronization execution modes.', 'SYSTEM', 'CHECK', 'int_sync_runs.sync_mode'),
    ('PEOPLE.POSITION_TYPE', 'dwp-people-server', 'Position type', 'Position planning behavior classes.', 'SYSTEM', 'CHECK', 'ppl_positions.position_type'),
    ('PEOPLE.POSITION_CRITICALITY', 'dwp-people-server', 'Position criticality', 'Business impact of a vacant position.', 'SYSTEM', 'CHECK', 'ppl_positions.criticality'),
    ('PEOPLE.POSITION_STATUS', 'dwp-people-server', 'Position status', 'Position occupancy lifecycle.', 'SYSTEM', 'CHECK', 'ppl_positions.position_status'),
    ('PEOPLE.WORKER_TYPE', 'dwp-people-server', 'Worker type', 'Canonical workforce relationship classes.', 'SYSTEM', 'CHECK', 'ppl_workers.worker_type'),
    ('PEOPLE.WORKER_STATUS', 'dwp-people-server', 'Worker status', 'Canonical worker lifecycle.', 'SYSTEM', 'CHECK', 'ppl_workers.worker_status'),
    ('PEOPLE.ASSIGNMENT_STATUS', 'dwp-people-server', 'Assignment status', 'Effective assignment lifecycle.', 'SYSTEM', 'CHECK', 'ppl_assignments.assignment_status'),
    ('PEOPLE.RELATIONSHIP_TYPE', 'dwp-people-server', 'Reporting relationship type', 'Solid, matrix, and functional reporting lines.', 'SYSTEM', 'CHECK', 'ppl_organization_relationships.relationship_type'),
    ('PEOPLE.CHANGE_REASON', 'dwp-people-server', 'Assignment change reason', 'Tenant-extensible workforce event reasons.', 'EXTENSIBLE', 'DOMAIN_CATALOG', 'ppl_assignment_change_reason_catalog.reason_code'),
    ('PEOPLE.ORGANIZATION_ROLE', 'dwp-people-server', 'Organization role', 'Governed roles held for an organization.', 'EXTENSIBLE', 'CHECK', 'ppl_organization_role_assignments.role_code'),
    ('PLATFORM.REFERENCE_LIFECYCLE', 'dwp-platform-server', 'Reference lifecycle', 'Lifecycle shared by tenant reference data.', 'SYSTEM', 'TYPED_CONTRACT', 'ReferenceLifecycle'),
    ('PLATFORM.REGISTRY_TYPE', 'dwp-platform-server', 'Registry type', 'Deployable runtime registry entry classes.', 'SYSTEM', 'TYPED_CONTRACT', 'RegistryType'),
    ('PLATFORM.RISK_TIER', 'dwp-platform-server', 'Platform risk tier', 'Risk classification for runtime artifacts.', 'SYSTEM', 'TYPED_CONTRACT', 'RiskTier'),
    ('PLATFORM.ANNOUNCEMENT_SEVERITY', 'dwp-platform-server', 'Announcement severity', 'Tenant announcement presentation and urgency.', 'SYSTEM', 'TYPED_CONTRACT', 'AnnouncementSeverity'),
    ('PLATFORM.ANNOUNCEMENT_AUDIENCE', 'dwp-platform-server', 'Announcement audience', 'Tenant-wide or role-targeted delivery.', 'SYSTEM', 'TYPED_CONTRACT', 'AnnouncementAudienceType'),
    ('PLATFORM.BACKGROUND_POSITION', 'dwp-platform-server', 'Background position', 'Tenant home image focal alignment.', 'SYSTEM', 'CHECK', 'adm_home_experiences.background_position'),
    ('PLATFORM.PREFERENCE.COLOR_MODE', 'dwp-platform-server', 'Color mode', 'Personal appearance preference.', 'SYSTEM', 'TYPED_CONTRACT', 'personal-preference.appearance.colorMode'),
    ('PLATFORM.PREFERENCE.DENSITY', 'dwp-platform-server', 'Interface density', 'Personal control spacing preference.', 'SYSTEM', 'TYPED_CONTRACT', 'personal-preference.appearance.density'),
    ('PROVIDER.SERVICE_TIER', 'dwp-provider-server', 'Service tier', 'Commercial and operational service class.', 'SYSTEM', 'CHECK', 'prv_tenants.service_tier'),
    ('PROVIDER.ISOLATION_MODEL', 'dwp-provider-server', 'Isolation model', 'Tenant data-plane isolation model.', 'SYSTEM', 'CHECK', 'prv_tenants.isolation_model'),
    ('PROVIDER.RISK_TIER', 'dwp-provider-server', 'Provider risk tier', 'Provider operation risk classification.', 'SYSTEM', 'CHECK', 'prv_operations.risk_tier'),
    ('PROVIDER.OPERATION_STATE', 'dwp-provider-server', 'Operation state', 'Provider workflow lifecycle.', 'SYSTEM', 'CHECK', 'prv_operations.lifecycle_state'),
    ('PROVIDER.INCIDENT_SEVERITY', 'dwp-provider-server', 'Incident severity', 'Customer impact severity.', 'SYSTEM', 'CHECK', 'prv_service_incidents.severity'),
    ('PROVIDER.INCIDENT_STATE', 'dwp-provider-server', 'Incident state', 'Service incident response lifecycle.', 'SYSTEM', 'CHECK', 'prv_service_incidents.lifecycle_state'),
    ('PROVIDER.HEALTH_STATE', 'dwp-provider-server', 'Service health state', 'Observed runtime service health.', 'SYSTEM', 'CHECK', 'prv_service_health_observations.health_state'),
    ('PROVIDER.SLO_COMPLIANCE_STATE', 'dwp-provider-server', 'SLO compliance state', 'Error budget and SLO posture.', 'SYSTEM', 'CHECK', 'prv_service_level_snapshots.compliance_state'),
    ('PROVIDER.MAINTENANCE_SCOPE', 'dwp-provider-server', 'Maintenance scope', 'Target boundary for planned maintenance.', 'SYSTEM', 'CHECK', 'prv_maintenance_windows.scope_type'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'dwp-provider-server', 'Maintenance impact', 'Expected customer-facing maintenance impact.', 'SYSTEM', 'CHECK', 'prv_maintenance_windows.impact_type'),
    ('PROVIDER.SUPPORT_ACCESS_MODE', 'dwp-provider-server', 'Support access mode', 'Standard and emergency support access.', 'SYSTEM', 'CHECK', 'prv_support_sessions.access_mode'),
    ('PROVIDER.TENANT_STATE', 'dwp-provider-server', 'Provider tenant state', 'Customer tenant lifecycle.', 'SYSTEM', 'CHECK', 'prv_tenants.lifecycle_state'),
    ('PROVIDER.ONBOARDING_STATE', 'dwp-provider-server', 'Onboarding state', 'Cross-service tenant onboarding lifecycle.', 'SYSTEM', 'CHECK', 'prv_tenants.onboarding_state'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'dwp-provider-server', 'Service instance state', 'Per-tenant service runtime lifecycle.', 'SYSTEM', 'CHECK', 'prv_tenant_service_instances.lifecycle_state'),
    ('PROVIDER.DOMAIN_VERIFICATION_STATE', 'dwp-provider-server', 'Domain verification state', 'Tenant domain ownership lifecycle.', 'SYSTEM', 'CHECK', 'prv_tenant_domains.verification_state'),
    ('PROVIDER.OPERATION_TYPE', 'dwp-provider-server', 'Operation type', 'Provider workflow handlers and planned remediations.', 'EXTENSIBLE', 'DOMAIN_CATALOG', 'prv_operation_type_catalog.operation_type'),
    ('PROVIDER.PERMISSION', 'dwp-provider-server', 'Provider permission', 'Provider operator capabilities.', 'EXTENSIBLE', 'DOMAIN_CATALOG', 'prv_operator_permission_catalog.permission_code');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('AUTH.LOGIN_TYPE', 'LOCAL', 'Company email and password', '{"ko":"회사 이메일 및 비밀번호","en":"Company email and password"}', 10, '{}'),
    ('AUTH.LOGIN_TYPE', 'SSO', 'Enterprise single sign-on', '{"ko":"기업 SSO","en":"Enterprise single sign-on"}', 20, '{}'),
    ('AUTH.IDENTITY_PROVIDER_TYPE', 'LOCAL', 'Local credential', '{}', 10, '{}'),
    ('AUTH.IDENTITY_PROVIDER_TYPE', 'OIDC', 'OpenID Connect', '{}', 20, '{}'),
    ('AUTH.ROLE_TYPE', 'SYSTEM', 'System role', '{}', 10, '{}'),
    ('AUTH.ROLE_TYPE', 'CUSTOM', 'Custom role', '{}', 20, '{}'),
    ('AUTH.ROLE_STATUS', 'ACTIVE', 'Active', '{}', 10, '{}'),
    ('AUTH.ROLE_STATUS', 'INACTIVE', 'Inactive', '{}', 20, '{}'),
    ('AUTH.PERMISSION_EFFECT', 'ALLOW', 'Allow', '{}', 10, '{}'),
    ('AUTH.PERMISSION_EFFECT', 'DENY', 'Deny', '{}', 20, '{}'),
    ('AUTH.RESOURCE_TYPE', 'APP', 'Application', '{}', 10, '{}'),
    ('AUTH.RESOURCE_TYPE', 'NAVIGATION', 'Navigation', '{}', 20, '{}'),
    ('AUTH.RESOURCE_TYPE', 'API', 'API', '{}', 30, '{}'),
    ('AUTH.RESOURCE_TYPE', 'ACTION', 'Action', '{}', 40, '{}'),
    ('AUTH.RESOURCE_TYPE', 'DATA', 'Data', '{}', 50, '{}'),
    ('AUTH.RESOURCE_TYPE', 'ADMIN', 'Administration', '{}', 60, '{}'),
    ('AUTH.GROUP_ASSIGNMENT_TYPE', 'ACTIVE', 'Active assignment', '{}', 10, '{}'),
    ('AUTH.GROUP_ASSIGNMENT_TYPE', 'ELIGIBLE', 'Eligible assignment', '{}', 20, '{}'),
    ('AUTH.ACCESS_SCOPE', 'TENANT', 'Tenant', '{}', 10, '{}'),
    ('AUTH.ACCESS_SCOPE', 'ORG_UNIT', 'Organization unit', '{}', 20, '{}'),
    ('AUTH.ACCESS_SCOPE', 'RESOURCE', 'Resource', '{}', 30, '{}'),
    ('PEOPLE.HRIS_SOURCE_TYPE', 'WORKDAY', 'Workday', '{}', 10, '{}'),
    ('PEOPLE.HRIS_SOURCE_TYPE', 'ORACLE_HCM', 'Oracle HCM', '{}', 20, '{}'),
    ('PEOPLE.HRIS_SOURCE_TYPE', 'SAP_HCM', 'SAP SuccessFactors', '{}', 30, '{}'),
    ('PEOPLE.HRIS_SOURCE_TYPE', 'SCIM', 'SCIM', '{}', 40, '{}'),
    ('PEOPLE.HRIS_SOURCE_TYPE', 'CUSTOM', 'Custom', '{}', 50, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'WORKDAY_REST', 'Workday REST', '{}', 10, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'WORKDAY_SOAP', 'Workday SOAP', '{}', 20, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'ORACLE_HCM_REST', 'Oracle HCM REST', '{}', 30, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'SAP_SUCCESSFACTORS', 'SAP SuccessFactors', '{}', 40, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'SCIM_BRIDGE', 'SCIM bridge', '{}', 50, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'CUSTOM_REST', 'Custom REST', '{}', 60, '{}'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'FILE_IMPORT', 'File import', '{}', 70, '{}'),
    ('PEOPLE.HRIS_AUTH_MODE', 'NONE', 'No authentication', '{}', 10, '{}'),
    ('PEOPLE.HRIS_AUTH_MODE', 'BASIC', 'Basic authentication', '{}', 20, '{}'),
    ('PEOPLE.HRIS_AUTH_MODE', 'OAUTH2_CLIENT_CREDENTIALS', 'OAuth 2.0 client credentials', '{}', 30, '{}'),
    ('PEOPLE.HRIS_AUTH_MODE', 'MTLS', 'Mutual TLS', '{}', 40, '{}'),
    ('PEOPLE.HRIS_AUTH_MODE', 'SIGNED_REQUEST', 'Signed request', '{}', 50, '{}'),
    ('PEOPLE.SYNC_MODE', 'FULL', 'Full', '{}', 10, '{}'),
    ('PEOPLE.SYNC_MODE', 'DELTA', 'Delta', '{}', 20, '{}'),
    ('PEOPLE.SYNC_MODE', 'EVENT', 'Event', '{}', 30, '{}'),
    ('PEOPLE.SYNC_MODE', 'REPLAY', 'Replay', '{}', 40, '{}'),
    ('PEOPLE.POSITION_TYPE', 'REGULAR', 'Regular', '{"ko":"일반","en":"Regular"}', 10, '{}'),
    ('PEOPLE.POSITION_TYPE', 'SHARED', 'Shared', '{"ko":"공유","en":"Shared"}', 20, '{}'),
    ('PEOPLE.POSITION_TYPE', 'ASSISTANT', 'Assistant', '{"ko":"보좌","en":"Assistant"}', 30, '{}'),
    ('PEOPLE.POSITION_TYPE', 'TEMPORARY', 'Temporary', '{"ko":"임시","en":"Temporary"}', 40, '{}'),
    ('PEOPLE.POSITION_CRITICALITY', 'LOW', 'Low', '{"ko":"낮음","en":"Low"}', 10, '{"weight":1}'),
    ('PEOPLE.POSITION_CRITICALITY', 'MEDIUM', 'Medium', '{"ko":"보통","en":"Medium"}', 20, '{"weight":2}'),
    ('PEOPLE.POSITION_CRITICALITY', 'HIGH', 'High', '{"ko":"높음","en":"High"}', 30, '{"weight":3}'),
    ('PEOPLE.POSITION_CRITICALITY', 'CRITICAL', 'Critical', '{"ko":"핵심","en":"Critical"}', 40, '{"weight":4}'),
    ('PEOPLE.POSITION_STATUS', 'OPEN', 'Open', '{}', 10, '{"occupied":false,"terminal":false}'),
    ('PEOPLE.POSITION_STATUS', 'FILLED', 'Filled', '{}', 20, '{"occupied":true,"terminal":false}'),
    ('PEOPLE.POSITION_STATUS', 'FROZEN', 'Frozen', '{}', 30, '{"occupied":false,"terminal":false}'),
    ('PEOPLE.POSITION_STATUS', 'CLOSED', 'Closed', '{}', 40, '{"occupied":false,"terminal":true}'),
    ('PEOPLE.WORKER_TYPE', 'EMPLOYEE', 'Employee', '{}', 10, '{}'),
    ('PEOPLE.WORKER_TYPE', 'CONTINGENT', 'Contingent worker', '{}', 20, '{}'),
    ('PEOPLE.WORKER_TYPE', 'NONWORKER', 'Nonworker', '{}', 30, '{}'),
    ('PEOPLE.WORKER_TYPE', 'PENDING', 'Pending worker', '{}', 40, '{}'),
    ('PEOPLE.WORKER_STATUS', 'ACTIVE', 'Active', '{}', 10, '{}'),
    ('PEOPLE.WORKER_STATUS', 'LEAVE', 'Leave', '{}', 20, '{}'),
    ('PEOPLE.WORKER_STATUS', 'TERMINATED', 'Terminated', '{}', 30, '{}'),
    ('PEOPLE.WORKER_STATUS', 'PENDING', 'Pending', '{}', 40, '{}'),
    ('PEOPLE.ASSIGNMENT_STATUS', 'ACTIVE', 'Active', '{}', 10, '{}'),
    ('PEOPLE.ASSIGNMENT_STATUS', 'SUSPENDED', 'Suspended', '{}', 20, '{}'),
    ('PEOPLE.ASSIGNMENT_STATUS', 'ENDED', 'Ended', '{}', 30, '{}'),
    ('PEOPLE.ASSIGNMENT_STATUS', 'PENDING', 'Pending', '{}', 40, '{}'),
    ('PEOPLE.RELATIONSHIP_TYPE', 'SUPERVISORY', 'Supervisory', '{}', 10, '{}'),
    ('PEOPLE.RELATIONSHIP_TYPE', 'MATRIX', 'Matrix', '{}', 20, '{}'),
    ('PEOPLE.RELATIONSHIP_TYPE', 'FUNCTIONAL', 'Functional', '{}', 30, '{}'),
    ('PEOPLE.CHANGE_REASON', 'SEED_IMPORT', 'Initial import', '{}', 10, '{}'),
    ('PEOPLE.CHANGE_REASON', 'INTERNAL_TRANSFER', 'Internal transfer', '{}', 20, '{}'),
    ('PEOPLE.CHANGE_REASON', 'PROMOTION', 'Promotion', '{}', 30, '{}'),
    ('PEOPLE.ORGANIZATION_ROLE', 'LEADER', 'Leader', '{}', 10, '{}'),
    ('PEOPLE.ORGANIZATION_ROLE', 'HR_BUSINESS_PARTNER', 'HR business partner', '{}', 20, '{}'),
    ('PEOPLE.ORGANIZATION_ROLE', 'FINANCE_PARTNER', 'Finance partner', '{}', 30, '{}'),
    ('PEOPLE.ORGANIZATION_ROLE', 'MATRIX_MANAGER', 'Matrix manager', '{}', 40, '{}'),
    ('PEOPLE.ORGANIZATION_ROLE', 'SECURITY_ADMIN', 'Security administrator', '{}', 50, '{}'),
    ('PLATFORM.REFERENCE_LIFECYCLE', 'DRAFT', 'Draft', '{}', 10, '{}'),
    ('PLATFORM.REFERENCE_LIFECYCLE', 'ACTIVE', 'Active', '{}', 20, '{}'),
    ('PLATFORM.REFERENCE_LIFECYCLE', 'RETIRED', 'Retired', '{}', 30, '{}'),
    ('PLATFORM.REGISTRY_TYPE', 'APP', 'Application', '{}', 10, '{}'),
    ('PLATFORM.REGISTRY_TYPE', 'CONNECTOR', 'Connector', '{}', 20, '{}'),
    ('PLATFORM.REGISTRY_TYPE', 'AGENT', 'Agent', '{}', 30, '{}'),
    ('PLATFORM.REGISTRY_TYPE', 'TOOL', 'Tool', '{}', 40, '{}'),
    ('PLATFORM.REGISTRY_TYPE', 'POLICY', 'Policy', '{}', 50, '{}'),
    ('PLATFORM.RISK_TIER', 'LOW', 'Low', '{}', 10, '{}'),
    ('PLATFORM.RISK_TIER', 'MEDIUM', 'Medium', '{}', 20, '{}'),
    ('PLATFORM.RISK_TIER', 'HIGH', 'High', '{}', 30, '{}'),
    ('PLATFORM.RISK_TIER', 'CRITICAL', 'Critical', '{}', 40, '{}'),
    ('PLATFORM.ANNOUNCEMENT_SEVERITY', 'INFO', 'Information', '{}', 10, '{}'),
    ('PLATFORM.ANNOUNCEMENT_SEVERITY', 'SUCCESS', 'Success', '{}', 20, '{}'),
    ('PLATFORM.ANNOUNCEMENT_SEVERITY', 'WARNING', 'Warning', '{}', 30, '{}'),
    ('PLATFORM.ANNOUNCEMENT_SEVERITY', 'CRITICAL', 'Critical', '{}', 40, '{}'),
    ('PLATFORM.ANNOUNCEMENT_AUDIENCE', 'ALL', 'All users', '{}', 10, '{}'),
    ('PLATFORM.ANNOUNCEMENT_AUDIENCE', 'ROLE', 'Role', '{}', 20, '{}'),
    ('PLATFORM.BACKGROUND_POSITION', 'LEFT', 'Left', '{}', 10, '{}'),
    ('PLATFORM.BACKGROUND_POSITION', 'CENTER', 'Center', '{}', 20, '{}'),
    ('PLATFORM.BACKGROUND_POSITION', 'RIGHT', 'Right', '{}', 30, '{}'),
    ('PLATFORM.PREFERENCE.COLOR_MODE', 'SYSTEM', 'System', '{"ko":"시스템","en":"System"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.COLOR_MODE', 'LIGHT', 'Light', '{"ko":"라이트","en":"Light"}', 20, '{}'),
    ('PLATFORM.PREFERENCE.COLOR_MODE', 'DARK', 'Dark', '{"ko":"다크","en":"Dark"}', 30, '{}'),
    ('PLATFORM.PREFERENCE.DENSITY', 'COMPACT', 'Compact', '{"ko":"컴팩트","en":"Compact"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.DENSITY', 'STANDARD', 'Standard', '{"ko":"표준","en":"Standard"}', 20, '{}'),
    ('PLATFORM.PREFERENCE.DENSITY', 'COMFORTABLE', 'Comfortable', '{"ko":"여유롭게","en":"Comfortable"}', 30, '{}'),
    ('PROVIDER.SERVICE_TIER', 'STANDARD', 'Standard', '{}', 10, '{}'),
    ('PROVIDER.SERVICE_TIER', 'ENTERPRISE', 'Enterprise', '{}', 20, '{}'),
    ('PROVIDER.SERVICE_TIER', 'REGULATED', 'Regulated', '{}', 30, '{}'),
    ('PROVIDER.ISOLATION_MODEL', 'POOL', 'Pool', '{}', 10, '{}'),
    ('PROVIDER.ISOLATION_MODEL', 'BRIDGE', 'Bridge', '{}', 20, '{}'),
    ('PROVIDER.ISOLATION_MODEL', 'SILO', 'Silo', '{}', 30, '{}'),
    ('PROVIDER.RISK_TIER', 'L1', 'L1', '{}', 10, '{}'),
    ('PROVIDER.RISK_TIER', 'L2', 'L2', '{}', 20, '{}'),
    ('PROVIDER.RISK_TIER', 'L3', 'L3', '{}', 30, '{}'),
    ('PROVIDER.OPERATION_STATE', 'PREVIEWED', 'Previewed', '{}', 10, '{}'),
    ('PROVIDER.OPERATION_STATE', 'EXECUTING', 'Executing', '{}', 20, '{}'),
    ('PROVIDER.OPERATION_STATE', 'PARTIAL', 'Partially completed', '{}', 30, '{}'),
    ('PROVIDER.OPERATION_STATE', 'SUCCEEDED', 'Succeeded', '{}', 40, '{}'),
    ('PROVIDER.OPERATION_STATE', 'FAILED', 'Failed', '{}', 50, '{}'),
    ('PROVIDER.OPERATION_STATE', 'CANCELLED', 'Cancelled', '{}', 60, '{}'),
    ('PROVIDER.INCIDENT_SEVERITY', 'SEV1', 'SEV1', '{}', 10, '{}'),
    ('PROVIDER.INCIDENT_SEVERITY', 'SEV2', 'SEV2', '{}', 20, '{}'),
    ('PROVIDER.INCIDENT_SEVERITY', 'SEV3', 'SEV3', '{}', 30, '{}'),
    ('PROVIDER.INCIDENT_SEVERITY', 'SEV4', 'SEV4', '{}', 40, '{}'),
    ('PROVIDER.INCIDENT_STATE', 'INVESTIGATING', 'Investigating', '{}', 10, '{}'),
    ('PROVIDER.INCIDENT_STATE', 'IDENTIFIED', 'Identified', '{}', 20, '{}'),
    ('PROVIDER.INCIDENT_STATE', 'MONITORING', 'Monitoring', '{}', 30, '{}'),
    ('PROVIDER.INCIDENT_STATE', 'RESOLVED', 'Resolved', '{}', 40, '{}'),
    ('PROVIDER.INCIDENT_STATE', 'CLOSED', 'Closed', '{}', 50, '{}'),
    ('PROVIDER.HEALTH_STATE', 'HEALTHY', 'Healthy', '{}', 10, '{}'),
    ('PROVIDER.HEALTH_STATE', 'DEGRADED', 'Degraded', '{}', 20, '{}'),
    ('PROVIDER.HEALTH_STATE', 'UNAVAILABLE', 'Unavailable', '{}', 30, '{}'),
    ('PROVIDER.HEALTH_STATE', 'UNKNOWN', 'Unknown', '{}', 40, '{}'),
    ('PROVIDER.SLO_COMPLIANCE_STATE', 'HEALTHY', 'Healthy', '{}', 10, '{}'),
    ('PROVIDER.SLO_COMPLIANCE_STATE', 'AT_RISK', 'At risk', '{}', 20, '{}'),
    ('PROVIDER.SLO_COMPLIANCE_STATE', 'EXHAUSTED', 'Exhausted', '{}', 30, '{}'),
    ('PROVIDER.SLO_COMPLIANCE_STATE', 'NO_DATA', 'No data', '{}', 40, '{}'),
    ('PROVIDER.MAINTENANCE_SCOPE', 'GLOBAL', 'Global', '{}', 10, '{}'),
    ('PROVIDER.MAINTENANCE_SCOPE', 'REGION', 'Region', '{}', 20, '{}'),
    ('PROVIDER.MAINTENANCE_SCOPE', 'CELL', 'Deployment cell', '{}', 30, '{}'),
    ('PROVIDER.MAINTENANCE_SCOPE', 'SERVICE', 'Service', '{}', 40, '{}'),
    ('PROVIDER.MAINTENANCE_SCOPE', 'TENANT', 'Tenant', '{}', 50, '{}'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'NO_IMPACT', 'No impact', '{}', 10, '{}'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'BRIEF_INTERRUPTION', 'Brief interruption', '{}', 20, '{}'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'DEGRADED_PERFORMANCE', 'Degraded performance', '{}', 30, '{}'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'SERVICE_UNAVAILABLE', 'Service unavailable', '{}', 40, '{}'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'FAILOVER', 'Failover', '{}', 50, '{}'),
    ('PROVIDER.MAINTENANCE_IMPACT', 'OTHER', 'Other', '{}', 60, '{}'),
    ('PROVIDER.SUPPORT_ACCESS_MODE', 'STANDARD', 'Standard', '{}', 10, '{}'),
    ('PROVIDER.SUPPORT_ACCESS_MODE', 'BREAK_GLASS', 'Emergency break glass', '{}', 20, '{}'),
    ('PROVIDER.TENANT_STATE', 'PROVISIONING', 'Provisioning', '{}', 10, '{}'),
    ('PROVIDER.TENANT_STATE', 'ACTIVE', 'Active', '{}', 20, '{}'),
    ('PROVIDER.TENANT_STATE', 'SUSPENDED', 'Suspended', '{}', 30, '{}'),
    ('PROVIDER.TENANT_STATE', 'RETIRED', 'Retired', '{}', 40, '{}'),
    ('PROVIDER.ONBOARDING_STATE', 'PREVIEWED', 'Previewed', '{}', 10, '{}'),
    ('PROVIDER.ONBOARDING_STATE', 'CONTROL_PLANE_READY', 'Control plane ready', '{}', 20, '{}'),
    ('PROVIDER.ONBOARDING_STATE', 'PENDING_EXTERNAL', 'Pending external dependency', '{}', 30, '{}'),
    ('PROVIDER.ONBOARDING_STATE', 'READY', 'Ready', '{}', 40, '{}'),
    ('PROVIDER.ONBOARDING_STATE', 'FAILED', 'Failed', '{}', 50, '{}'),
    ('PROVIDER.ONBOARDING_STATE', 'CANCELLED', 'Cancelled', '{}', 60, '{}'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'PROVISIONING', 'Provisioning', '{}', 10, '{}'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'READY', 'Ready', '{}', 20, '{}'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'DEGRADED', 'Degraded', '{}', 30, '{}'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'SUSPENDED', 'Suspended', '{}', 40, '{}'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'FAILED', 'Failed', '{}', 50, '{}'),
    ('PROVIDER.SERVICE_INSTANCE_STATE', 'RETIRED', 'Retired', '{}', 60, '{}'),
    ('PROVIDER.DOMAIN_VERIFICATION_STATE', 'PENDING', 'Pending', '{}', 10, '{}'),
    ('PROVIDER.DOMAIN_VERIFICATION_STATE', 'VERIFIED', 'Verified', '{}', 20, '{}'),
    ('PROVIDER.DOMAIN_VERIFICATION_STATE', 'FAILED', 'Failed', '{}', 30, '{}'),
    ('PROVIDER.DOMAIN_VERIFICATION_STATE', 'REVOKED', 'Revoked', '{}', 40, '{}'),
    ('PROVIDER.OPERATION_TYPE', 'TENANT_ONBOARD', 'Tenant onboarding', '{}', 10, '{"executable":true}'),
    ('PROVIDER.OPERATION_TYPE', 'TENANT_SUSPEND', 'Tenant suspension', '{}', 20, '{"executable":true}'),
    ('PROVIDER.OPERATION_TYPE', 'TENANT_ACTIVATE', 'Tenant activation', '{}', 30, '{"executable":true}'),
    ('PROVIDER.OPERATION_TYPE', 'ENTITLEMENT_CHANGE', 'Entitlement change', '{}', 40, '{"executable":true}'),
    ('PROVIDER.OPERATION_TYPE', 'MAINTENANCE_SCHEDULE', 'Maintenance scheduling', '{}', 50, '{"executable":true}'),
    ('PROVIDER.OPERATION_TYPE', 'DOMAIN_VERIFY', 'Domain verification', '{}', 60, '{"executable":false}'),
    ('PROVIDER.OPERATION_TYPE', 'SERVICE_RECONCILE', 'Service reconciliation', '{}', 70, '{"executable":false}'),
    ('PROVIDER.PERMISSION', 'AUDIT_READ', 'Read provider audit', '{}', 10, '{}'),
    ('PROVIDER.PERMISSION', 'BREAK_GLASS_SUPPORT', 'Use emergency support access', '{}', 20, '{}'),
    ('PROVIDER.PERMISSION', 'CHANGE_APPROVE', 'Approve provider changes', '{}', 30, '{}'),
    ('PROVIDER.PERMISSION', 'COMMERCIAL_READ', 'Read commercial portfolio', '{}', 40, '{}'),
    ('PROVIDER.PERMISSION', 'ENTITLEMENT_WRITE', 'Manage entitlements', '{}', 50, '{}'),
    ('PROVIDER.PERMISSION', 'ESTATE_READ', 'Read tenant estate', '{}', 60, '{}'),
    ('PROVIDER.PERMISSION', 'HEALTH_READ', 'Read service health', '{}', 70, '{}'),
    ('PROVIDER.PERMISSION', 'INCIDENT_WRITE', 'Manage service incidents', '{}', 80, '{}'),
    ('PROVIDER.PERMISSION', 'MAINTENANCE_WRITE', 'Manage planned maintenance', '{}', 90, '{}'),
    ('PROVIDER.PERMISSION', 'OPERATION_EXECUTE', 'Execute operations', '{}', 100, '{}'),
    ('PROVIDER.PERMISSION', 'RELIABILITY_READ', 'Read reliability controls', '{}', 110, '{}'),
    ('PROVIDER.PERMISSION', 'SUPPORT_SESSION_WRITE', 'Manage support sessions', '{}', 120, '{}'),
    ('PROVIDER.PERMISSION', 'TENANT_WRITE', 'Manage tenants', '{}', 130, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
SELECT code_set_key,
       owner_service,
       'DATABASE_COLUMN',
       source_reference,
       CASE validation_source
           WHEN 'FOREIGN_KEY' THEN 'FOREIGN_KEY'
           WHEN 'DOMAIN_CATALOG' THEN 'CATALOG_LOOKUP'
           WHEN 'TYPED_CONTRACT' THEN 'TYPED_CONTRACT'
           ELSE 'CHECK'
       END
  FROM sys_code_sets;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PEOPLE.HRIS_SOURCE_TYPE', 'dwp-frontend', 'UI_SELECTION', 'provisioning-manager/sourceType', 'CATALOG_LOOKUP'),
    ('PEOPLE.HRIS_CONNECTOR_TYPE', 'dwp-frontend', 'UI_SELECTION', 'provisioning-manager/connectorType', 'CATALOG_LOOKUP'),
    ('PEOPLE.HRIS_AUTH_MODE', 'dwp-frontend', 'UI_SELECTION', 'provisioning-manager/authMode', 'CATALOG_LOOKUP'),
    ('PEOPLE.POSITION_TYPE', 'dwp-frontend', 'UI_SELECTION', 'organization-scenario-position-editor/positionType', 'CATALOG_LOOKUP'),
    ('PEOPLE.POSITION_CRITICALITY', 'dwp-frontend', 'UI_SELECTION', 'organization-scenario-position-editor/criticality', 'CATALOG_LOOKUP'),
    ('AUTH.RESOURCE_TYPE', 'dwp-frontend', 'UI_SELECTION', 'role-governance-manager/resourceType', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.COLOR_MODE', 'dwp-frontend', 'UI_SELECTION', 'account-preferences/colorMode', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.DENSITY', 'dwp-frontend', 'UI_SELECTION', 'account-preferences/density', 'CATALOG_LOOKUP');

CREATE VIEW sys_code_catalog_health AS
SELECT code_set.code_set_key,
       code_set.owner_service,
       code_set.configuration_level,
       code_set.validation_source,
       COUNT(DISTINCT code_value.code) AS value_count,
       COUNT(DISTINCT binding.code_binding_id) AS binding_count,
       CASE
           WHEN COUNT(DISTINCT code_value.code) > 0
            AND COUNT(DISTINCT binding.code_binding_id) > 0
           THEN 'REGISTERED'
           ELSE 'INCOMPLETE'
       END AS registration_state
  FROM sys_code_sets code_set
  LEFT JOIN sys_code_values code_value
    ON code_value.code_set_key = code_set.code_set_key
   AND code_value.lifecycle_state = 'ACTIVE'
  LEFT JOIN sys_code_bindings binding
    ON binding.code_set_key = code_set.code_set_key
   AND binding.lifecycle_state = 'ACTIVE'
 GROUP BY code_set.code_set_key, code_set.owner_service,
          code_set.configuration_level, code_set.validation_source;

CREATE INDEX idx_sys_code_values_runtime
    ON sys_code_values(code_set_key, lifecycle_state, sort_order, code);
CREATE INDEX idx_sys_code_bindings_consumer
    ON sys_code_bindings(consumer_service, lifecycle_state, code_set_key);

COMMENT ON TABLE sys_code_sets IS
    'Read-only cross-service code contract registry. Domain services remain authoritative for validation and behavior.';
COMMENT ON COLUMN sys_code_sets.configuration_level IS
    'SYSTEM is immutable, EXTENSIBLE allows additions without changing predefined semantics, and USER is tenant-owned.';
COMMENT ON VIEW sys_code_catalog_health IS
    'Registration completeness only; service-local constraints and catalogs remain the runtime enforcement evidence.';
