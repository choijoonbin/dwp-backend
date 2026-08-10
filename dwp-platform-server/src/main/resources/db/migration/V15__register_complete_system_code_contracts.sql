ALTER TABLE sys_code_sets
    ADD COLUMN contract_kind VARCHAR(24) NOT NULL DEFAULT 'REFERENCE';

ALTER TABLE sys_code_sets
    ADD CONSTRAINT ck_sys_code_sets_contract_kind
        CHECK (contract_kind IN (
            'REFERENCE', 'STATE_MACHINE', 'SECURITY',
            'PROTOCOL', 'OBSERVABILITY', 'REGISTRY_META'));

UPDATE sys_code_sets
   SET contract_kind = CASE
       WHEN source_reference LIKE 'sys_code_%' THEN 'REGISTRY_META'
       WHEN source_reference ~ '(status|state|decision|outcome)$' THEN 'STATE_MACHINE'
       WHEN code_set_key LIKE 'AUTH.%'
         OR source_reference ~ '(role|permission|access_scope|effect)$' THEN 'SECURITY'
       WHEN source_reference LIKE 'sys_audit_%'
         OR source_reference LIKE '%health%'
         OR source_reference LIKE '%incident%' THEN 'OBSERVABILITY'
       WHEN source_reference ~ '(type|mode|kind|scope|tier|severity|operation|strategy|method)$'
         THEN 'PROTOCOL'
       ELSE 'REFERENCE'
       END;

CREATE TEMP TABLE tmp_sys_code_contract_manifest (
    generated_code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    UNIQUE (owner_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_sys_code_contract_manifest (
    generated_code_set_key, owner_service, source_reference, allowed_values)
VALUES
    ('AUTH.COM_GROUP_MEMBERS.SOURCE_TYPE', 'dwp-auth-server', 'com_group_members.source_type', ARRAY['LOCAL', 'SCIM']::VARCHAR[]),
    ('AUTH.COM_GROUP_ROLE_ASSIGNMENTS.ASSIGNMENT_TYPE', 'dwp-auth-server', 'com_group_role_assignments.assignment_type', ARRAY['ACTIVE', 'ELIGIBLE']::VARCHAR[]),
    ('AUTH.COM_GROUP_ROLE_ASSIGNMENTS.LIFECYCLE_STATE', 'dwp-auth-server', 'com_group_role_assignments.lifecycle_state', ARRAY['ACTIVE', 'REVOKED']::VARCHAR[]),
    ('AUTH.COM_GROUP_ROLE_ASSIGNMENTS.SCOPE_TYPE', 'dwp-auth-server', 'com_group_role_assignments.scope_type', ARRAY['ORG_UNIT', 'RESOURCE', 'TENANT']::VARCHAR[]),
    ('AUTH.COM_GROUPS.SOURCE_TYPE', 'dwp-auth-server', 'com_groups.source_type', ARRAY['LOCAL', 'SCIM']::VARCHAR[]),
    ('AUTH.COM_GROUPS.STATUS', 'dwp-auth-server', 'com_groups.status', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('AUTH.COM_ORGANIZATION_UNITS.SOURCE_TYPE', 'dwp-auth-server', 'com_organization_units.source_type', ARRAY['LOCAL', 'SCIM']::VARCHAR[]),
    ('AUTH.COM_ORGANIZATION_UNITS.STATUS', 'dwp-auth-server', 'com_organization_units.status', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('AUTH.COM_RESOURCE_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-auth-server', 'com_resource_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.COM_ROLE_HIERARCHY.LIFECYCLE_STATE', 'dwp-auth-server', 'com_role_hierarchy.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('AUTH.COM_ROLE_PERMISSIONS.EFFECT', 'dwp-auth-server', 'com_role_permissions.effect', ARRAY['ALLOW', 'DENY']::VARCHAR[]),
    ('AUTH.COM_ROLES.ROLE_TYPE', 'dwp-auth-server', 'com_roles.role_type', ARRAY['CUSTOM', 'SYSTEM']::VARCHAR[]),
    ('AUTH.COM_ROLES.STATUS', 'dwp-auth-server', 'com_roles.status', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('AUTH.COM_SEPARATION_OF_DUTY_RULES.ENFORCEMENT_MODE', 'dwp-auth-server', 'com_separation_of_duty_rules.enforcement_mode', ARRAY['BLOCK', 'REQUIRE_APPROVAL']::VARCHAR[]),
    ('AUTH.COM_SEPARATION_OF_DUTY_RULES.LIFECYCLE_STATE', 'dwp-auth-server', 'com_separation_of_duty_rules.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('AUTH.COM_SEPARATION_OF_DUTY_RULES.RULE_TYPE', 'dwp-auth-server', 'com_separation_of_duty_rules.rule_type', ARRAY['DYNAMIC', 'STATIC']::VARCHAR[]),
    ('AUTH.COM_TENANTS.ISOLATION_MODEL', 'dwp-auth-server', 'com_tenants.isolation_model', ARRAY['BRIDGE', 'POOL', 'SILO']::VARCHAR[]),
    ('AUTH.COM_TENANTS.STATUS', 'dwp-auth-server', 'com_tenants.status', ARRAY['ACTIVE', 'PROVISIONING', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('AUTH.COM_USER_ACCOUNTS.STATUS', 'dwp-auth-server', 'com_user_accounts.status', ARRAY['ACTIVE', 'INVITED', 'LOCKED', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('AUTH.COM_USERS.SOURCE_TYPE', 'dwp-auth-server', 'com_users.source_type', ARRAY['HRIS', 'LOCAL', 'SCIM']::VARCHAR[]),
    ('AUTH.COM_USERS.STATUS', 'dwp-auth-server', 'com_users.status', ARRAY['ACTIVE', 'INACTIVE', 'INVITED', 'SUSPENDED']::VARCHAR[]),
    ('AUTH.SYS_ACCOUNT_ACTIVATION_TOKENS.LIFECYCLE_STATE', 'dwp-auth-server', 'sys_account_activation_tokens.lifecycle_state', ARRAY['ACTIVE', 'EXPIRED', 'REVOKED', 'USED']::VARCHAR[]),
    ('AUTH.SYS_AUDIT_OUTBOX.STATUS', 'dwp-auth-server', 'sys_audit_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('AUTH.SYS_IDENTITY_PROVIDER_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-auth-server', 'sys_identity_provider_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.SYS_IDENTITY_SYNC_RECEIPTS.SOURCE_TYPE', 'dwp-auth-server', 'sys_identity_sync_receipts.source_type', ARRAY['HRIS', 'SCIM']::VARCHAR[]),
    ('AUTH.SYS_LOGIN_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-auth-server', 'sys_login_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('AUTH.SYS_SCIM_CONNECTORS.LIFECYCLE_STATE', 'dwp-auth-server', 'sys_scim_connectors.lifecycle_state', ARRAY['ACTIVE', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('AUTH.SYS_SCIM_PROVISIONING_EVENTS.OPERATION', 'dwp-auth-server', 'sys_scim_provisioning_events.operation', ARRAY['CREATE', 'DELETE', 'PATCH', 'READ', 'REPLACE', 'SEARCH']::VARCHAR[]),
    ('AUTH.SYS_SCIM_PROVISIONING_EVENTS.OUTCOME', 'dwp-auth-server', 'sys_scim_provisioning_events.outcome', ARRAY['DENIED', 'FAILED', 'SUCCESS']::VARCHAR[]),
    ('AUTH.SYS_SCIM_PROVISIONING_EVENTS.RESOURCE_TYPE', 'dwp-auth-server', 'sys_scim_provisioning_events.resource_type', ARRAY['CONFIG', 'GROUP', 'USER']::VARCHAR[]),
    ('PEOPLE.INT_CONNECTOR_INSTANCES.AUTH_MODE', 'dwp-people-server', 'int_connector_instances.auth_mode', ARRAY['BASIC', 'MTLS', 'NONE', 'OAUTH2_CLIENT_CREDENTIALS', 'SIGNED_REQUEST']::VARCHAR[]),
    ('PEOPLE.INT_CONNECTOR_INSTANCES.CONNECTOR_TYPE', 'dwp-people-server', 'int_connector_instances.connector_type', ARRAY['CUSTOM_REST', 'FILE_IMPORT', 'ORACLE_HCM_REST', 'SAP_SUCCESSFACTORS', 'SCIM_BRIDGE', 'WORKDAY_REST', 'WORKDAY_SOAP']::VARCHAR[]),
    ('PEOPLE.INT_CONNECTOR_INSTANCES.HEALTH_STATE', 'dwp-people-server', 'int_connector_instances.health_state', ARRAY['DEGRADED', 'FAILED', 'HEALTHY', 'UNKNOWN']::VARCHAR[]),
    ('PEOPLE.INT_CONNECTOR_INSTANCES.LIFECYCLE_STATE', 'dwp-people-server', 'int_connector_instances.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PEOPLE.INT_INGESTION_RECEIPTS.LIFECYCLE_STATE', 'dwp-people-server', 'int_ingestion_receipts.lifecycle_state', ARRAY['FAILED', 'PROCESSING', 'SUCCEEDED']::VARCHAR[]),
    ('PEOPLE.INT_MAPPING_PROFILES.ADAPTER_TYPE', 'dwp-people-server', 'int_mapping_profiles.adapter_type', ARRAY['CANONICAL_JSON', 'ORACLE_HCM_REST', 'SAP_SUCCESSFACTORS', 'WORKDAY_REFERENCE', 'WORKDAY_REST', 'WORKDAY_SOAP']::VARCHAR[]),
    ('PEOPLE.INT_MAPPING_PROFILES.LIFECYCLE_STATE', 'dwp-people-server', 'int_mapping_profiles.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PEOPLE.INT_SOURCE_SYSTEMS.LIFECYCLE_STATE', 'dwp-people-server', 'int_source_systems.lifecycle_state', ARRAY['ACTIVE', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PEOPLE.INT_SOURCE_SYSTEMS.SYSTEM_TYPE', 'dwp-people-server', 'int_source_systems.system_type', ARRAY['CUSTOM', 'ORACLE_HCM', 'SAP_HCM', 'SCIM', 'WORKDAY']::VARCHAR[]),
    ('PEOPLE.INT_SYNC_RUNS.LIFECYCLE_STATE', 'dwp-people-server', 'int_sync_runs.lifecycle_state', ARRAY['CANCELLED', 'FAILED', 'PARTIAL', 'QUEUED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('PEOPLE.INT_SYNC_RUNS.SYNC_MODE', 'dwp-people-server', 'int_sync_runs.sync_mode', ARRAY['DELTA', 'EVENT', 'FULL', 'REPLAY']::VARCHAR[]),
    ('PEOPLE.PPL_ASSIGNMENTS.ASSIGNMENT_STATUS', 'dwp-people-server', 'ppl_assignments.assignment_status', ARRAY['ACTIVE', 'ENDED', 'PENDING', 'SUSPENDED']::VARCHAR[]),
    ('PEOPLE.PPL_ASSIGNMENT_CHANGE_REASON_CATALOG.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_assignment_change_reason_catalog.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_ATTRIBUTE_DEFINITIONS.DATA_CLASSIFICATION', 'dwp-people-server', 'ppl_attribute_definitions.data_classification', ARRAY['CONFIDENTIAL', 'INTERNAL', 'PUBLIC', 'RESTRICTED']::VARCHAR[]),
    ('PEOPLE.PPL_ATTRIBUTE_DEFINITIONS.ENTITY_TYPE', 'dwp-people-server', 'ppl_attribute_definitions.entity_type', ARRAY['ASSIGNMENT', 'PERSON', 'POSITION', 'WORKER']::VARCHAR[]),
    ('PEOPLE.PPL_ATTRIBUTE_DEFINITIONS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_attribute_definitions.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PEOPLE.PPL_ATTRIBUTE_DEFINITIONS.VALUE_TYPE', 'dwp-people-server', 'ppl_attribute_definitions.value_type', ARRAY['BOOLEAN', 'CODE', 'DATE', 'JSON', 'NUMBER', 'STRING']::VARCHAR[]),
    ('PEOPLE.PPL_CONTACTS.CONTACT_TYPE', 'dwp-people-server', 'ppl_contacts.contact_type', ARRAY['ADDRESS', 'EMAIL', 'PHONE']::VARCHAR[]),
    ('PEOPLE.PPL_CONTACTS.USAGE_TYPE', 'dwp-people-server', 'ppl_contacts.usage_type', ARRAY['EMERGENCY', 'HOME', 'WORK']::VARCHAR[]),
    ('PEOPLE.PPL_CONTACTS.VISIBILITY', 'dwp-people-server', 'ppl_contacts.visibility', ARRAY['INTERNAL', 'PRIVATE', 'PUBLIC']::VARCHAR[]),
    ('PEOPLE.PPL_JOB_GRADES.CAREER_TRACK', 'dwp-people-server', 'ppl_job_grades.career_track', ARRAY['CONTRACTOR', 'EXECUTIVE', 'MANAGEMENT', 'PROFESSIONAL']::VARCHAR[]),
    ('PEOPLE.PPL_JOB_GRADES.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_job_grades.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_JOB_PROFILES.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_job_profiles.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_LEGAL_EMPLOYERS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_legal_employers.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_LOCATIONS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_locations.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_CHANGE_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_organization_change_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_CHANGE_TYPE_CATALOG.RISK_TIER', 'dwp-people-server', 'ppl_organization_change_type_catalog.risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_CHANGE_TYPE_CATALOG.TARGET_KIND', 'dwp-people-server', 'ppl_organization_change_type_catalog.target_kind', ARRAY['ASSIGNMENT', 'ORGANIZATION', 'POSITION']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_RELATIONSHIPS.RELATIONSHIP_TYPE', 'dwp-people-server', 'ppl_organization_relationships.relationship_type', ARRAY['FUNCTIONAL', 'MATRIX', 'SUPERVISORY']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_ROLE_ASSIGNMENTS.ROLE_CODE', 'dwp-people-server', 'ppl_organization_role_assignments.role_code', ARRAY['FINANCE_PARTNER', 'HR_BUSINESS_PARTNER', 'LEADER', 'MATRIX_MANAGER', 'SECURITY_ADMIN']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_ROLE_CATALOG.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_organization_role_catalog.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIO_APPROVALS.EVIDENCE_BINDING_STATE', 'dwp-people-server', 'ppl_organization_scenario_approvals.evidence_binding_state', ARRAY['BOUND', 'LEGACY_UNBOUND']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIO_APPROVALS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_organization_scenario_approvals.lifecycle_state', ARRAY['APPROVED', 'CANCELLED', 'EXPIRED', 'PENDING', 'REJECTED']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIO_CHANGES.TARGET_KIND', 'dwp-people-server', 'ppl_organization_scenario_changes.target_kind', ARRAY['ASSIGNMENT', 'ORGANIZATION', 'POSITION']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIO_CHANGES.VALIDATION_STATE', 'dwp-people-server', 'ppl_organization_scenario_changes.validation_state', ARRAY['BLOCKED', 'VALID', 'WARNING']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIO_VALIDATION_RUNS.DECISION_STATE', 'dwp-people-server', 'ppl_organization_scenario_validation_runs.decision_state', ARRAY['BLOCKED', 'READY', 'REVIEW_REQUIRED']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIO_VALIDATION_RUNS.TRIGGER_TYPE', 'dwp-people-server', 'ppl_organization_scenario_validation_runs.trigger_type', ARRAY['APPROVE', 'MANUAL', 'PUBLISH', 'REJECT', 'SUBMIT']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIOS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_organization_scenarios.lifecycle_state', ARRAY['APPROVED', 'CANCELLED', 'DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED', 'STALE']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_SCENARIOS.PUBLICATION_EVIDENCE_STATE', 'dwp-people-server', 'ppl_organization_scenarios.publication_evidence_state', ARRAY['BOUND', 'LEGACY_UNBOUND']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATION_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_organization_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_ORGANIZATIONS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_organizations.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PEOPLE.PPL_PERSON_NAMES.NAME_TYPE', 'dwp-people-server', 'ppl_person_names.name_type', ARRAY['LEGAL', 'LOCAL', 'PREFERRED']::VARCHAR[]),
    ('PEOPLE.PPL_PERSONS.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_persons.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE', 'MERGED']::VARCHAR[]),
    ('PEOPLE.PPL_POSITION_RELATIONSHIPS.RELATIONSHIP_SOURCE', 'dwp-people-server', 'ppl_position_relationships.relationship_source', ARRAY['HRIS', 'INFERRED', 'POSITION', 'SCENARIO']::VARCHAR[]),
    ('PEOPLE.PPL_POSITION_RELATIONSHIPS.RELATIONSHIP_TYPE', 'dwp-people-server', 'ppl_position_relationships.relationship_type', ARRAY['FUNCTIONAL', 'MATRIX', 'SUPERVISORY']::VARCHAR[]),
    ('PEOPLE.PPL_POSITIONS.CRITICALITY', 'dwp-people-server', 'ppl_positions.criticality', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('PEOPLE.PPL_POSITIONS.POSITION_STATUS', 'dwp-people-server', 'ppl_positions.position_status', ARRAY['CLOSED', 'FILLED', 'FROZEN', 'OPEN']::VARCHAR[]),
    ('PEOPLE.PPL_POSITIONS.POSITION_TYPE', 'dwp-people-server', 'ppl_positions.position_type', ARRAY['ASSISTANT', 'REGULAR', 'SHARED', 'TEMPORARY']::VARCHAR[]),
    ('PEOPLE.PPL_POSITION_CRITICALITY_CATALOG.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_position_criticality_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PEOPLE.PPL_POSITION_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_position_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PEOPLE.PPL_PROFILE_MEDIA.LIFECYCLE_STATE', 'dwp-people-server', 'ppl_profile_media.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PEOPLE.PPL_PROFILE_MEDIA.VISIBILITY', 'dwp-people-server', 'ppl_profile_media.visibility', ARRAY['INTERNAL', 'PRIVATE', 'PUBLIC']::VARCHAR[]),
    ('PEOPLE.PPL_WORK_RELATIONSHIPS.RELATIONSHIP_TYPE', 'dwp-people-server', 'ppl_work_relationships.relationship_type', ARRAY['CONTINGENT', 'EMPLOYEE', 'NONWORKER', 'PENDING']::VARCHAR[]),
    ('PEOPLE.PPL_WORKERS.WORKER_STATUS', 'dwp-people-server', 'ppl_workers.worker_status', ARRAY['ACTIVE', 'LEAVE', 'PENDING', 'TERMINATED']::VARCHAR[]),
    ('PEOPLE.PPL_WORKERS.WORKER_TYPE', 'dwp-people-server', 'ppl_workers.worker_type', ARRAY['CONTINGENT', 'EMPLOYEE', 'NONWORKER', 'PENDING']::VARCHAR[]),
    ('PEOPLE.SYS_AUDIT_OUTBOX.STATUS', 'dwp-people-server', 'sys_audit_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('PEOPLE.SYS_PEOPLE_AUDIT_EVENTS.ACTOR_TYPE', 'dwp-people-server', 'sys_people_audit_events.actor_type', ARRAY['AGENT', 'SERVICE', 'USER']::VARCHAR[]),
    ('PEOPLE.SYS_PEOPLE_AUDIT_EVENTS.OUTCOME', 'dwp-people-server', 'sys_people_audit_events.outcome', ARRAY['DENIED', 'FAILED', 'SUCCESS']::VARCHAR[]),
    ('PEOPLE.SYS_SERVICE_TENANTS.ISOLATION_MODEL', 'dwp-people-server', 'sys_service_tenants.isolation_model', ARRAY['BRIDGE', 'POOL', 'SILO']::VARCHAR[]),
    ('PEOPLE.SYS_SERVICE_TENANTS.LIFECYCLE_STATE', 'dwp-people-server', 'sys_service_tenants.lifecycle_state', ARRAY['ACTIVE', 'PROVISIONING', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PLATFORM.ADM_ANNOUNCEMENTS.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_announcements.lifecycle_state', ARRAY['ARCHIVED', 'DRAFT', 'PUBLISHED']::VARCHAR[]),
    ('PLATFORM.ADM_ANNOUNCEMENTS.SEVERITY', 'dwp-platform-server', 'adm_announcements.severity', ARRAY['CRITICAL', 'INFO', 'SUCCESS', 'WARNING']::VARCHAR[]),
    ('PLATFORM.ADM_HOME_EXPERIENCES.BACKGROUND_POSITION', 'dwp-platform-server', 'adm_home_experiences.background_position', ARRAY['CENTER', 'LEFT', 'RIGHT']::VARCHAR[]),
    ('PLATFORM.ADM_MESSAGE_OVERRIDES.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_message_overrides.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.ADM_MESSAGE_OVERRIDES.NAMESPACE', 'dwp-platform-server', 'adm_message_overrides.namespace', ARRAY['CONTENT', 'NAVIGATION', 'SERVICE', 'TENANT']::VARCHAR[]),
    ('PLATFORM.ADM_NAVIGATION_ITEMS.ITEM_TYPE', 'dwp-platform-server', 'adm_navigation_items.item_type', ARRAY['APP', 'GROUP']::VARCHAR[]),
    ('PLATFORM.ADM_NAVIGATION_ITEMS.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_navigation_items.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.ADM_REFERENCE_ITEMS.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_reference_items.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.ADM_REFERENCE_SETS.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_reference_sets.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.ADM_REGISTRY_ENTRIES.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_registry_entries.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.ADM_REGISTRY_ENTRIES.REGISTRY_TYPE', 'dwp-platform-server', 'adm_registry_entries.registry_type', ARRAY['AGENT', 'APP', 'CONNECTOR', 'POLICY', 'TOOL']::VARCHAR[]),
    ('PLATFORM.ADM_REGISTRY_ENTRIES.RISK_TIER', 'dwp-platform-server', 'adm_registry_entries.risk_tier', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('PLATFORM.ADM_TENANT_LOCALES.LIFECYCLE_STATE', 'dwp-platform-server', 'adm_tenant_locales.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('PLATFORM.SYS_ADMIN_COMMAND_APPROVALS.APPROVER_TYPE', 'dwp-platform-server', 'sys_admin_command_approvals.approver_type', ARRAY['GROUP', 'ROLE', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_ADMIN_COMMAND_APPROVALS.DECISION', 'dwp-platform-server', 'sys_admin_command_approvals.decision', ARRAY['APPROVED', 'CANCELLED', 'DENIED', 'PENDING']::VARCHAR[]),
    ('PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.ACTOR_TYPE', 'dwp-platform-server', 'sys_admin_command_requests.actor_type', ARRAY['AGENT', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.LIFECYCLE_STATE', 'dwp-platform-server', 'sys_admin_command_requests.lifecycle_state', ARRAY['APPROVED', 'CANCELLED', 'DENIED', 'EXECUTED', 'EXPIRED', 'FAILED', 'PENDING_APPROVAL', 'PREVIEWED']::VARCHAR[]),
    ('PLATFORM.SYS_ADMIN_COMMAND_REQUESTS.RISK_TIER', 'dwp-platform-server', 'sys_admin_command_requests.risk_tier', ARRAY['L0', 'L1', 'L2', 'L3']::VARCHAR[]),
    ('PLATFORM.SYS_API_HISTORY.ACTOR_TYPE', 'dwp-platform-server', 'sys_api_history.actor_type', ARRAY['AGENT', 'ANONYMOUS', 'SERVICE', 'SYSTEM', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_API_HISTORY.AUTH_TYPE', 'dwp-platform-server', 'sys_api_history.auth_type', ARRAY['BEARER', 'NONE', 'SCIM', 'SERVICE', 'SESSION', 'UNKNOWN']::VARCHAR[]),
    ('PLATFORM.SYS_API_HISTORY.OBSERVATION_POINT', 'dwp-platform-server', 'sys_api_history.observation_point', ARRAY['GATEWAY', 'SERVICE']::VARCHAR[]),
    ('PLATFORM.SYS_API_HISTORY.OUTCOME', 'dwp-platform-server', 'sys_api_history.outcome', ARRAY['CANCELLED', 'CLIENT_ERROR', 'REDIRECTION', 'SERVER_ERROR', 'SUCCESS']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_CASE_ACTIVITIES.ACTIVITY_TYPE', 'dwp-platform-server', 'sys_audit_case_activities.activity_type', ARRAY['ASSIGNMENT_CHANGED', 'CASE_CREATED', 'CASE_UPDATED', 'EVIDENCE_LINKED', 'FINDING_LINKED', 'NOTE_ADDED', 'RESOLUTION_RECORDED', 'STATUS_CHANGED', 'TASK_CREATED', 'TASK_UPDATED']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_CASE_ENTITIES.ENTITY_TYPE', 'dwp-platform-server', 'sys_audit_case_entities.entity_type', ARRAY['AI_AGENT', 'APPLICATION', 'DATA', 'OTHER', 'RESOURCE', 'SERVICE', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_CASE_TASKS.PRIORITY', 'dwp-platform-server', 'sys_audit_case_tasks.priority', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_CASE_TASKS.STATUS', 'dwp-platform-server', 'sys_audit_case_tasks.status', ARRAY['DONE', 'IN_PROGRESS', 'OPEN', 'SKIPPED']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_CASES.SEVERITY', 'dwp-platform-server', 'sys_audit_cases.severity', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_CASES.STATUS', 'dwp-platform-server', 'sys_audit_cases.status', ARRAY['CLOSED', 'CONTAINED', 'INVESTIGATING', 'OPEN', 'RESOLVED']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EVENTS.ACTOR_TYPE', 'dwp-platform-server', 'sys_audit_events.actor_type', ARRAY['AGENT', 'ANONYMOUS', 'SERVICE', 'SYSTEM', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EVENTS.CATEGORY', 'dwp-platform-server', 'sys_audit_events.category', ARRAY['ADMIN_CHANGE', 'AI_ACTION', 'AUTHENTICATION', 'AUTHORIZATION', 'DATA_ACCESS', 'DATA_EXPORT', 'POLICY_DENIED', 'PROVISIONING', 'SYSTEM_EVENT']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EVENTS.OUTCOME', 'dwp-platform-server', 'sys_audit_events.outcome', ARRAY['DENIED', 'FAILED', 'SUCCESS']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EVENTS.POLICY_DECISION', 'dwp-platform-server', 'sys_audit_events.policy_decision', ARRAY['ALLOW', 'DENY', 'NOT_APPLICABLE']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EVENTS.RETENTION_CLASS', 'dwp-platform-server', 'sys_audit_events.retention_class', ARRAY['EXTENDED', 'LEGAL_HOLD', 'STANDARD']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EVENTS.SEVERITY', 'dwp-platform-server', 'sys_audit_events.severity', ARRAY['CRITICAL', 'HIGH', 'INFO', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT', 'dwp-platform-server', 'sys_audit_export_jobs.format', ARRAY['CSV', 'JSONL']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_EXPORT_JOBS.STATUS', 'dwp-platform-server', 'sys_audit_export_jobs.status', ARRAY['COMPLETED', 'EXPIRED', 'FAILED', 'PENDING', 'RUNNING']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_FINDINGS.SEVERITY', 'dwp-platform-server', 'sys_audit_findings.severity', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_FINDINGS.STATUS', 'dwp-platform-server', 'sys_audit_findings.status', ARRAY['ACKNOWLEDGED', 'DISMISSED', 'INVESTIGATING', 'OPEN', 'RESOLVED']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_INTEGRITY_CHECKPOINTS.VERIFICATION_STATUS', 'dwp-platform-server', 'sys_audit_integrity_checkpoints.verification_status', ARRAY['FAILED', 'UNAVAILABLE', 'VERIFIED']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_OUTBOX.STATUS', 'dwp-platform-server', 'sys_audit_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('PLATFORM.SYS_AUDIT_SOURCE_HEALTH.DELIVERY_STATUS', 'dwp-platform-server', 'sys_audit_source_health.delivery_status', ARRAY['DEGRADED', 'ERROR', 'HEALTHY', 'STALE']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_BINDINGS.ENFORCEMENT_TYPE', 'dwp-platform-server', 'sys_code_bindings.enforcement_type', ARRAY['CATALOG_LOOKUP', 'CHECK', 'FOREIGN_KEY', 'TYPED_CONTRACT']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_BINDINGS.LIFECYCLE_STATE', 'dwp-platform-server', 'sys_code_bindings.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_BINDINGS.USAGE_TYPE', 'dwp-platform-server', 'sys_code_bindings.usage_type', ARRAY['API_CONTRACT', 'BEHAVIOR', 'DATABASE_COLUMN', 'UI_SELECTION']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_SETS.CONFIGURATION_LEVEL', 'dwp-platform-server', 'sys_code_sets.configuration_level', ARRAY['EXTENSIBLE', 'SYSTEM', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_SETS.CONTRACT_KIND', 'dwp-platform-server', 'sys_code_sets.contract_kind', ARRAY['OBSERVABILITY', 'PROTOCOL', 'REFERENCE', 'REGISTRY_META', 'SECURITY', 'STATE_MACHINE']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_SETS.LIFECYCLE_STATE', 'dwp-platform-server', 'sys_code_sets.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_SETS.VALIDATION_SOURCE', 'dwp-platform-server', 'sys_code_sets.validation_source', ARRAY['CHECK', 'DOMAIN_CATALOG', 'EXTERNAL_STANDARD', 'FOREIGN_KEY', 'TYPED_CONTRACT']::VARCHAR[]),
    ('PLATFORM.SYS_CODE_VALUES.LIFECYCLE_STATE', 'dwp-platform-server', 'sys_code_values.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PLATFORM.SYS_PLATFORM_AUDIT_EVENTS.ACTOR_TYPE', 'dwp-platform-server', 'sys_platform_audit_events.actor_type', ARRAY['SERVICE', 'USER']::VARCHAR[]),
    ('PLATFORM.SYS_PLATFORM_AUDIT_EVENTS.OUTCOME', 'dwp-platform-server', 'sys_platform_audit_events.outcome', ARRAY['DENIED', 'FAILED', 'SUCCESS']::VARCHAR[]),
    ('PLATFORM.SYS_SERVICE_TENANTS.ISOLATION_MODEL', 'dwp-platform-server', 'sys_service_tenants.isolation_model', ARRAY['BRIDGE', 'POOL', 'SILO']::VARCHAR[]),
    ('PLATFORM.SYS_SERVICE_TENANTS.LIFECYCLE_STATE', 'dwp-platform-server', 'sys_service_tenants.lifecycle_state', ARRAY['ACTIVE', 'PROVISIONING', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_AUDIT_EVENTS.OUTCOME', 'dwp-provider-server', 'prv_audit_events.outcome', ARRAY['DENIED', 'FAILED', 'SUCCESS']::VARCHAR[]),
    ('PROVIDER.PRV_CONFIGURATION_SCHEMAS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_configuration_schemas.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_CONFIGURATION_SCHEMAS.SCOPE_KIND', 'dwp-provider-server', 'prv_configuration_schemas.scope_kind', ARRAY['ORGANIZATION', 'SERVICE', 'TENANT']::VARCHAR[]),
    ('PROVIDER.PRV_CONFIGURATION_VALUES.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_configuration_values.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_DEPLOYMENT_CELLS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_deployment_cells.lifecycle_state', ARRAY['ACTIVE', 'DRAINING', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_ENTITLEMENT_CATALOG.ENTITLEMENT_TYPE', 'dwp-provider-server', 'prv_entitlement_catalog.entitlement_type', ARRAY['APP', 'CAPABILITY', 'LIMIT']::VARCHAR[]),
    ('PROVIDER.PRV_ENTITLEMENT_CATALOG.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_entitlement_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_CONTROLS.CONTROL_BEHAVIOR', 'dwp-provider-server', 'prv_governance_controls.control_behavior', ARRAY['DETECTIVE', 'PREVENTIVE', 'PROACTIVE']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_CONTROLS.CONTROL_CATEGORY', 'dwp-provider-server', 'prv_governance_controls.control_category', ARRAY['BASELINE', 'DATA_GOVERNANCE', 'IDENTITY', 'RESILIENCE']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_CONTROLS.GUIDANCE_LEVEL', 'dwp-provider-server', 'prv_governance_controls.guidance_level', ARRAY['ELECTIVE', 'MANDATORY', 'STRONGLY_RECOMMENDED']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_CONTROLS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_governance_controls.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_CONTROLS.RISK_TIER', 'dwp-provider-server', 'prv_governance_controls.risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_EVALUATIONS.EVALUATION_RESULT', 'dwp-provider-server', 'prv_governance_evaluations.evaluation_result', ARRAY['COMPLIANT', 'ERROR', 'NON_COMPLIANT', 'NOT_APPLICABLE']::VARCHAR[]),
    ('PROVIDER.PRV_GOVERNANCE_EVALUATIONS.TARGET_TYPE', 'dwp-provider-server', 'prv_governance_evaluations.target_type', ARRAY['CELL', 'DOMAIN', 'ORGANIZATION', 'SERVICE_INSTANCE', 'TENANT']::VARCHAR[]),
    ('PROVIDER.PRV_MAINTENANCE_WINDOWS.IMPACT_TYPE', 'dwp-provider-server', 'prv_maintenance_windows.impact_type', ARRAY['BRIEF_INTERRUPTION', 'DEGRADED_PERFORMANCE', 'FAILOVER', 'NO_IMPACT', 'OTHER', 'SERVICE_UNAVAILABLE']::VARCHAR[]),
    ('PROVIDER.PRV_MAINTENANCE_WINDOWS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_maintenance_windows.lifecycle_state', ARRAY['CANCELLED', 'COMPLETED', 'DRAFT', 'IN_PROGRESS', 'SCHEDULED']::VARCHAR[]),
    ('PROVIDER.PRV_MAINTENANCE_WINDOWS.SCOPE_TYPE', 'dwp-provider-server', 'prv_maintenance_windows.scope_type', ARRAY['CELL', 'GLOBAL', 'REGION', 'SERVICE', 'TENANT']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_APPROVALS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operation_approvals.lifecycle_state', ARRAY['APPROVED', 'CANCELLED', 'EXPIRED', 'PENDING', 'REJECTED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_STEP_ATTEMPTS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operation_step_attempts.lifecycle_state', ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_STEPS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operation_steps.lifecycle_state', ARRAY['FAILED', 'PENDING', 'PENDING_EXTERNAL', 'RUNNING', 'SKIPPED', 'SUCCEEDED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_TYPE_CATALOG.DEFAULT_RISK_TIER', 'dwp-provider-server', 'prv_operation_type_catalog.default_risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_TYPE_CATALOG.EXECUTION_STRATEGY', 'dwp-provider-server', 'prv_operation_type_catalog.execution_strategy', ARRAY['EXTERNAL_WORKFLOW', 'SAGA', 'SINGLE_STEP']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_TYPE_CATALOG.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operation_type_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATIONS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operations.lifecycle_state', ARRAY['CANCELLED', 'EXECUTING', 'FAILED', 'PARTIAL', 'PREVIEWED', 'SUCCEEDED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATIONS.RISK_TIER', 'dwp-provider-server', 'prv_operations.risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATOR_PERMISSION_CATALOG.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operator_permission_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATOR_PERMISSION_CATALOG.RISK_TIER', 'dwp-provider-server', 'prv_operator_permission_catalog.risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATOR_ROLE_ASSIGNMENTS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operator_role_assignments.lifecycle_state', ARRAY['ACTIVE', 'REVOKED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATOR_ROLES.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operator_roles.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATORS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_operators.lifecycle_state', ARRAY['ACTIVE', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_ORGANIZATION_SUBSCRIPTIONS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_organization_subscriptions.lifecycle_state', ARRAY['ACTIVE', 'ENDED', 'SUSPENDED', 'TRIAL']::VARCHAR[]),
    ('PROVIDER.PRV_ORGANIZATIONS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_organizations.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_REGIONS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_regions.lifecycle_state', ARRAY['ACTIVE', 'DRAINING', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_REGIONS.RESIDENCY_CLASS', 'dwp-provider-server', 'prv_regions.residency_class', ARRAY['LOCAL_ONLY', 'RESTRICTED', 'STANDARD']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_CATALOG.CRITICALITY', 'dwp-provider-server', 'prv_service_catalog.criticality', ARRAY['CRITICAL', 'HIGH', 'STANDARD']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_CATALOG.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_service_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_CATALOG.SERVICE_KIND', 'dwp-provider-server', 'prv_service_catalog.service_kind', ARRAY['CONTROL_PLANE', 'DATA_PLANE', 'STORAGE']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_HEALTH_OBSERVATIONS.HEALTH_STATE', 'dwp-provider-server', 'prv_service_health_observations.health_state', ARRAY['DEGRADED', 'HEALTHY', 'UNAVAILABLE', 'UNKNOWN']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENT_IMPACTS.IMPACT_STATE', 'dwp-provider-server', 'prv_service_incident_impacts.impact_state', ARRAY['CONFIRMED', 'POTENTIAL', 'RECOVERED']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENT_IMPACTS.TARGET_TYPE', 'dwp-provider-server', 'prv_service_incident_impacts.target_type', ARRAY['CELL', 'REGION', 'SERVICE', 'TENANT']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENT_UPDATES.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_service_incident_updates.lifecycle_state', ARRAY['CLOSED', 'IDENTIFIED', 'INVESTIGATING', 'MONITORING', 'RESOLVED']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENT_UPDATES.VISIBILITY', 'dwp-provider-server', 'prv_service_incident_updates.visibility', ARRAY['CUSTOMER', 'INTERNAL']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENTS.IMPACT_SCOPE', 'dwp-provider-server', 'prv_service_incidents.impact_scope', ARRAY['CELL', 'GLOBAL', 'REGION', 'SERVICE', 'TENANT']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENTS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_service_incidents.lifecycle_state', ARRAY['CLOSED', 'IDENTIFIED', 'INVESTIGATING', 'MONITORING', 'RESOLVED']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_INCIDENTS.SEVERITY', 'dwp-provider-server', 'prv_service_incidents.severity', ARRAY['SEV1', 'SEV2', 'SEV3', 'SEV4']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_LEVEL_OBJECTIVES.INDICATOR_TYPE', 'dwp-provider-server', 'prv_service_level_objectives.indicator_type', ARRAY['AVAILABILITY', 'LATENCY', 'SUCCESS_RATE']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_LEVEL_OBJECTIVES.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_service_level_objectives.lifecycle_state', ARRAY['ACTIVE', 'PAUSED', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_LEVEL_OBJECTIVES.SCOPE_TYPE', 'dwp-provider-server', 'prv_service_level_objectives.scope_type', ARRAY['CELL', 'GLOBAL', 'REGION', 'TENANT']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_LEVEL_SNAPSHOTS.COMPLIANCE_STATE', 'dwp-provider-server', 'prv_service_level_snapshots.compliance_state', ARRAY['AT_RISK', 'EXHAUSTED', 'HEALTHY', 'NO_DATA']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_PLANS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_service_plans.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_SERVICE_PLANS.SERVICE_TIER', 'dwp-provider-server', 'prv_service_plans.service_tier', ARRAY['ENTERPRISE', 'REGULATED', 'STANDARD']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SCOPE_CATALOG.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_support_scope_catalog.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SCOPE_CATALOG.RISK_TIER', 'dwp-provider-server', 'prv_support_scope_catalog.risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SESSIONS.ACCESS_MODE', 'dwp-provider-server', 'prv_support_sessions.access_mode', ARRAY['BREAK_GLASS', 'STANDARD']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SESSIONS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_support_sessions.lifecycle_state', ARRAY['ACTIVE', 'EXPIRED', 'REVOKED']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SESSIONS.RISK_TIER', 'dwp-provider-server', 'prv_support_sessions.risk_tier', ARRAY['L1', 'L2', 'L3']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_ADMINISTRATOR_ROLES.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_tenant_administrator_roles.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_ADMINISTRATORS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_tenant_administrators.lifecycle_state', ARRAY['ACTIVE', 'INVITED', 'PENDING', 'REVOKED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_DOMAINS.DOMAIN_TYPE', 'dwp-provider-server', 'prv_tenant_domains.domain_type', ARRAY['CUSTOM', 'EMAIL', 'LOGIN']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_DOMAINS.VERIFICATION_METHOD', 'dwp-provider-server', 'prv_tenant_domains.verification_method', ARRAY['DNS_TXT', 'HTTP', 'INTERNAL']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_DOMAINS.VERIFICATION_STATE', 'dwp-provider-server', 'prv_tenant_domains.verification_state', ARRAY['FAILED', 'PENDING', 'REVOKED', 'VERIFIED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_ENTITLEMENTS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_tenant_entitlements.lifecycle_state', ARRAY['ACTIVE', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_SERVICE_INSTANCES.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_tenant_service_instances.lifecycle_state', ARRAY['DEGRADED', 'FAILED', 'PROVISIONING', 'READY', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANTS.ISOLATION_MODEL', 'dwp-provider-server', 'prv_tenants.isolation_model', ARRAY['BRIDGE', 'POOL', 'SILO']::VARCHAR[]),
    ('PROVIDER.PRV_TENANTS.LIFECYCLE_STATE', 'dwp-provider-server', 'prv_tenants.lifecycle_state', ARRAY['ACTIVE', 'PROVISIONING', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANTS.ONBOARDING_STATE', 'dwp-provider-server', 'prv_tenants.onboarding_state', ARRAY['CANCELLED', 'CONTROL_PLANE_READY', 'FAILED', 'PENDING_EXTERNAL', 'PREVIEWED', 'READY']::VARCHAR[]),
    ('PROVIDER.PRV_TENANTS.SERVICE_TIER', 'dwp-provider-server', 'prv_tenants.service_tier', ARRAY['ENTERPRISE', 'REGULATED', 'STANDARD']::VARCHAR[]),
    ('PROVIDER.SYS_AUDIT_OUTBOX.STATUS', 'dwp-provider-server', 'sys_audit_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
SELECT manifest.generated_code_set_key,
       manifest.owner_service,
       INITCAP(REPLACE(REPLACE(manifest.source_reference, '.', ' '), '_', ' ')),
       'Database-enforced code contract for ' || manifest.source_reference || '.',
       'SYSTEM',
       'CHECK',
       manifest.source_reference,
       CASE
           WHEN manifest.source_reference LIKE 'sys_code_%' THEN 'REGISTRY_META'
           WHEN manifest.source_reference ~ '(status|state|decision|outcome)$'
               THEN 'STATE_MACHINE'
           WHEN manifest.generated_code_set_key LIKE 'AUTH.%'
             OR manifest.source_reference ~ '(role|permission|access_scope|effect)$'
               THEN 'SECURITY'
           WHEN manifest.source_reference LIKE 'sys_audit_%'
             OR manifest.source_reference LIKE '%health%'
             OR manifest.source_reference LIKE '%incident%'
               THEN 'OBSERVABILITY'
           WHEN manifest.source_reference ~
                '(type|mode|kind|scope|tier|severity|operation|strategy|method)$'
               THEN 'PROTOCOL'
           ELSE 'REFERENCE'
       END
  FROM tmp_sys_code_contract_manifest manifest
 WHERE NOT EXISTS (
       SELECT 1
         FROM sys_code_sets registered
        WHERE registered.owner_service = manifest.owner_service
          AND registered.source_reference = manifest.source_reference)
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, sort_order,
    predefined, behavior_metadata)
SELECT registered.code_set_key,
       value.code,
       INITCAP(REPLACE(REPLACE(LOWER(value.code), '_', ' '), '-', ' ')),
       value.ordinality * 10,
       TRUE,
       '{"declaredBy":"database-check"}'::jsonb
  FROM tmp_sys_code_contract_manifest manifest
  JOIN sys_code_sets registered
    ON registered.owner_service = manifest.owner_service
   AND registered.source_reference = manifest.source_reference
 CROSS JOIN LATERAL UNNEST(manifest.allowed_values)
       WITH ORDINALITY value(code, ordinality)
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type)
SELECT registered.code_set_key,
       manifest.owner_service,
       'DATABASE_COLUMN',
       manifest.source_reference,
       'CHECK'
  FROM tmp_sys_code_contract_manifest manifest
  JOIN sys_code_sets registered
    ON registered.owner_service = manifest.owner_service
   AND registered.source_reference = manifest.source_reference
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

-- Domain catalogs are service-owned. The platform registry publishes their
-- contract and baseline values but does not become their write authority.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PEOPLE.ORGANIZATION_TYPE', 'dwp-people-server', 'Organization type',
     'Tenant-extensible organization vocabulary.', 'EXTENSIBLE',
     'DOMAIN_CATALOG', 'ppl_organization_type_catalog.type_key', 'REFERENCE'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'dwp-people-server', 'Organization change type',
     'Implemented organization scenario mutation handlers.', 'SYSTEM',
     'DOMAIN_CATALOG', 'ppl_organization_change_type_catalog.change_type', 'PROTOCOL'),
    ('PROVIDER.OPERATOR_ROLE', 'dwp-provider-server', 'Provider operator role',
     'Provider workforce security roles.', 'EXTENSIBLE',
     'DOMAIN_CATALOG', 'prv_operator_roles.role_code', 'SECURITY'),
    ('PROVIDER.TENANT_ADMINISTRATOR_ROLE', 'dwp-provider-server', 'Tenant administrator role',
     'Customer tenant administration roles.', 'EXTENSIBLE',
     'DOMAIN_CATALOG', 'prv_tenant_administrator_roles.role_code', 'SECURITY')
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = EXCLUDED.configuration_level,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, predefined, lifecycle_state)
VALUES
    ('PEOPLE.CHANGE_REASON', 'REFERENCE_PROFILE', 'Reference profile',
     '{"ko":"참조 프로필","en":"Reference profile"}', 20, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'COMPANY', 'Company', '{}', 10, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'BUSINESS_UNIT', 'Business unit', '{}', 20, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'DIVISION', 'Division', '{}', 30, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'REGION', 'Region', '{}', 35, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'CENTER', 'Center', '{}', 36, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'DEPARTMENT', 'Department', '{}', 40, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'PRODUCT_GROUP', 'Product group', '{}', 45, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'DELIVERY_POD', 'Delivery pod', '{}', 50, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'SUPERVISORY', 'Team', '{}', 55, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'CHAPTER', 'Chapter', '{}', 56, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'SQUAD', 'Squad', '{}', 57, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'COST_CENTER', 'Cost center', '{}', 60, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_TYPE', 'CUSTOM', 'Custom unit', '{}', 500, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'CREATE_POSITION', 'Create position', '{}', 10, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'MOVE_POSITION', 'Move position', '{}', 20, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'CLOSE_POSITION', 'Close position', '{}', 30, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'MOVE_ORGANIZATION', 'Move organization', '{}', 40, TRUE, 'ACTIVE'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'CHANGE_MANAGER', 'Change manager', '{}', 100, TRUE, 'RETIRED'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'CHANGE_ORGANIZATION_LEADER', 'Change organization leader', '{}', 110, TRUE, 'RETIRED'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'MOVE_ASSIGNMENT', 'Move worker assignment', '{}', 120, TRUE, 'RETIRED'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'RENAME_ORGANIZATION', 'Rename organization', '{}', 130, TRUE, 'RETIRED'),
    ('PROVIDER.OPERATOR_ROLE', 'PROVIDER_ADMIN', 'Provider administrator', '{}', 10, TRUE, 'ACTIVE'),
    ('PROVIDER.OPERATOR_ROLE', 'PROVIDER_OPERATOR', 'Provider operator', '{}', 20, TRUE, 'ACTIVE'),
    ('PROVIDER.OPERATOR_ROLE', 'PROVIDER_SUPPORT', 'Provider support', '{}', 30, TRUE, 'ACTIVE'),
    ('PROVIDER.OPERATOR_ROLE', 'PROVIDER_AUDITOR', 'Provider auditor', '{}', 40, TRUE, 'ACTIVE'),
    ('PROVIDER.TENANT_ADMINISTRATOR_ROLE', 'TENANT_ADMIN', 'Tenant administrator', '{}', 10, TRUE, 'ACTIVE'),
    ('PROVIDER.TENANT_ADMINISTRATOR_ROLE', 'EXPERIENCE_ADMIN', 'Experience administrator', '{}', 20, TRUE, 'ACTIVE'),
    ('PROVIDER.TENANT_ADMINISTRATOR_ROLE', 'SECURITY_ADMIN', 'Security administrator', '{}', 30, TRUE, 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    predefined = EXCLUDED.predefined,
    lifecycle_state = EXCLUDED.lifecycle_state,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type)
VALUES
    ('PEOPLE.ORGANIZATION_TYPE', 'dwp-people-server', 'DATABASE_COLUMN',
     'ppl_organizations.organization_type', 'FOREIGN_KEY'),
    ('PEOPLE.ORGANIZATION_CHANGE_TYPE', 'dwp-people-server', 'DATABASE_COLUMN',
     'ppl_organization_scenario_changes.change_type', 'FOREIGN_KEY'),
    ('PROVIDER.OPERATOR_ROLE', 'dwp-provider-server', 'DATABASE_COLUMN',
     'prv_operators.role_code', 'FOREIGN_KEY'),
    ('PROVIDER.OPERATOR_ROLE', 'dwp-provider-server', 'DATABASE_COLUMN',
     'prv_operator_role_assignments.role_code', 'FOREIGN_KEY'),
    ('PROVIDER.TENANT_ADMINISTRATOR_ROLE', 'dwp-provider-server', 'DATABASE_COLUMN',
     'prv_tenant_administrators.role_code', 'FOREIGN_KEY'),
    ('PEOPLE.CHANGE_REASON', 'dwp-people-server', 'DATABASE_COLUMN',
     'ppl_assignments.change_reason_code', 'FOREIGN_KEY'),
    ('PEOPLE.ORGANIZATION_ROLE', 'dwp-people-server', 'DATABASE_COLUMN',
     'ppl_organization_role_assignments.role_code', 'FOREIGN_KEY'),
    ('PEOPLE.ORGANIZATION_ROLE', 'dwp-people-server', 'DATABASE_COLUMN',
     'ppl_organization_scenario_approvals.required_role_code', 'FOREIGN_KEY')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_sets
   SET validation_source = 'FOREIGN_KEY',
       source_reference = 'ppl_position_type_catalog.position_type',
       contract_kind = 'REFERENCE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PEOPLE.POSITION_TYPE';

UPDATE sys_code_sets
   SET validation_source = 'FOREIGN_KEY',
       source_reference = 'ppl_position_criticality_catalog.criticality',
       contract_kind = 'REFERENCE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PEOPLE.POSITION_CRITICALITY';

UPDATE sys_code_sets
   SET validation_source = 'DOMAIN_CATALOG',
       source_reference = 'ppl_organization_role_catalog.role_code',
       contract_kind = 'REFERENCE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PEOPLE.ORGANIZATION_ROLE';

UPDATE sys_code_bindings
   SET enforcement_type = 'FOREIGN_KEY',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key IN (
       'PEOPLE.POSITION_TYPE',
       'PEOPLE.POSITION_CRITICALITY',
       'PEOPLE.CHANGE_REASON',
       'PEOPLE.ORGANIZATION_ROLE')
   AND usage_type = 'DATABASE_COLUMN';

CREATE UNIQUE INDEX uk_sys_code_sets_owner_source_reference
    ON sys_code_sets(owner_service, source_reference);

DROP VIEW sys_code_catalog_health;

CREATE VIEW sys_code_catalog_health AS
SELECT code_set.code_set_key,
       code_set.owner_service,
       code_set.contract_kind,
       code_set.configuration_level,
       code_set.validation_source,
       COUNT(DISTINCT code_value.code) AS value_count,
       COUNT(DISTINCT binding.code_binding_id) AS binding_count,
       COUNT(DISTINCT binding.code_binding_id) FILTER (
           WHERE binding.enforcement_type IN (
               'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT')) AS enforced_binding_count,
       CASE
           WHEN COUNT(DISTINCT code_value.code) > 0
            AND COUNT(DISTINCT binding.code_binding_id) > 0
            AND COUNT(DISTINCT binding.code_binding_id) =
                COUNT(DISTINCT binding.code_binding_id) FILTER (
                    WHERE binding.enforcement_type IN (
                        'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT'))
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
          code_set.contract_kind, code_set.configuration_level,
          code_set.validation_source;

COMMENT ON COLUMN sys_code_sets.contract_kind IS
    'REFERENCE is selectable vocabulary; STATE_MACHINE and PROTOCOL are executable product contracts and are never tenant-editable.';
COMMENT ON VIEW sys_code_catalog_health IS
    'Declared registration and enforcement evidence. scripts/audit-code-contracts.sh verifies declarations against service databases.';
