INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES (
    'DATA_GOVERNANCE_READ',
    'Read data governance catalog',
    'L2',
    'Inspect live database metadata, relationships, lineage, and schema quality findings')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'DATA_GOVERNANCE_READ'),
    ('PROVIDER_OPERATOR', 'DATA_GOVERNANCE_READ'),
    ('PROVIDER_AUDITOR', 'DATA_GOVERNANCE_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

-- V19 added the catalog-backed role constraint without replacing the original V8 constraint.
-- Both constraints enforce the same relationship, so retain the explicitly catalog-named one.
ALTER TABLE prv_tenant_administrators
    DROP CONSTRAINT IF EXISTS fk_prv_tenant_administrators_role;

CREATE TABLE prv_data_asset_annotations (
    asset_key VARCHAR(320) PRIMARY KEY,
    database_key VARCHAR(40) NOT NULL,
    schema_name VARCHAR(63) NOT NULL,
    object_name VARCHAR(128) NOT NULL,
    business_domain VARCHAR(120),
    owner_service VARCHAR(120),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    criticality VARCHAR(20),
    data_classification VARCHAR(20),
    review_state VARCHAR(24) NOT NULL DEFAULT 'VERIFIED',
    description VARCHAR(1200),
    review_note VARCHAR(1200),
    last_reviewed_at TIMESTAMPTZ,
    last_reviewed_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_data_asset_annotations_object
        UNIQUE (database_key, schema_name, object_name),
    CONSTRAINT ck_prv_data_asset_annotations_key
        CHECK (asset_key = database_key || '.' || schema_name || '.' || object_name),
    CONSTRAINT ck_prv_data_asset_annotations_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'PLANNED', 'DEPRECATED', 'RETIRED')),
    CONSTRAINT ck_prv_data_asset_annotations_criticality
        CHECK (criticality IS NULL OR criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_prv_data_asset_annotations_classification
        CHECK (data_classification IS NULL OR data_classification IN (
            'PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_prv_data_asset_annotations_review
        CHECK (review_state IN ('DISCOVERED', 'REVIEW_REQUIRED', 'VERIFIED'))
);

CREATE TABLE prv_data_lineage_edges (
    data_lineage_edge_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    edge_key VARCHAR(160) NOT NULL UNIQUE,
    source_asset_key VARCHAR(320) NOT NULL,
    target_asset_key VARCHAR(320) NOT NULL,
    process_key VARCHAR(160) NOT NULL,
    edge_type VARCHAR(30) NOT NULL,
    owner_service VARCHAR(120) NOT NULL,
    description VARCHAR(1200) NOT NULL,
    evidence VARCHAR(500),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_data_lineage_edges_distinct
        CHECK (source_asset_key <> target_asset_key),
    CONSTRAINT ck_prv_data_lineage_edges_type
        CHECK (edge_type IN (
            'PROVISIONING', 'EVENT', 'REPLICATION', 'REFERENCE', 'AGGREGATION')),
    CONSTRAINT ck_prv_data_lineage_edges_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_data_lineage_edges_metadata
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX idx_prv_data_asset_annotations_domain
    ON prv_data_asset_annotations(database_key, business_domain, review_state);
CREATE INDEX idx_prv_data_lineage_edges_source
    ON prv_data_lineage_edges(source_asset_key) WHERE lifecycle_state = 'ACTIVE';
CREATE INDEX idx_prv_data_lineage_edges_target
    ON prv_data_lineage_edges(target_asset_key) WHERE lifecycle_state = 'ACTIVE';

COMMENT ON TABLE prv_data_asset_annotations IS
    'Curated business metadata layered over live database catalog scans; technical columns and constraints remain source-of-truth in each service database.';
COMMENT ON TABLE prv_data_lineage_edges IS
    'Curated cross-database process lineage. Physical foreign-key relationships are discovered live and are not duplicated here.';

INSERT INTO prv_data_asset_annotations (
    asset_key, database_key, schema_name, object_name, business_domain,
    owner_service, lifecycle_state, criticality, data_classification,
    review_state, description, review_note, last_reviewed_at)
VALUES
    ('provider.public.prv_tenants', 'provider', 'public', 'prv_tenants',
     'Tenant lifecycle', 'dwp-provider-server', 'ACTIVE', 'CRITICAL', 'CONFIDENTIAL',
     'VERIFIED', 'Authoritative provider record for every customer runtime environment.',
     'Provider identity is linked to service-local tenant projections through provisioning.', CURRENT_TIMESTAMP),
    ('auth.public.com_tenants', 'auth', 'public', 'com_tenants',
     'Identity tenancy', 'dwp-auth-server', 'ACTIVE', 'CRITICAL', 'CONFIDENTIAL',
     'VERIFIED', 'Identity and access boundary for an onboarded customer environment.',
     'The numeric tenant identifier is service-local; public_id carries the provider tenant UUID.', CURRENT_TIMESTAMP),
    ('people.public.sys_service_tenants', 'people', 'public', 'sys_service_tenants',
     'Service tenancy', 'dwp-people-server', 'ACTIVE', 'CRITICAL', 'CONFIDENTIAL',
     'VERIFIED', 'People-service projection of the provider tenant and local tenant identifier.', NULL, CURRENT_TIMESTAMP),
    ('platform.public.sys_service_tenants', 'platform', 'public', 'sys_service_tenants',
     'Service tenancy', 'dwp-platform-server', 'ACTIVE', 'CRITICAL', 'CONFIDENTIAL',
     'VERIFIED', 'Platform-service projection of the provider tenant and local tenant identifier.', NULL, CURRENT_TIMESTAMP),
    ('people.public.sys_people_outbox_events', 'people', 'public', 'sys_people_outbox_events',
     'Workforce identity delivery', 'dwp-people-server', 'ACTIVE', 'HIGH', 'CONFIDENTIAL',
     'VERIFIED', 'Transactional outbox for reliable workforce identity projection events.',
     'This is a delivery control table and must not be merged with the enterprise audit outbox.', CURRENT_TIMESTAMP),
    ('platform.public.sys_audit_events', 'platform', 'public', 'sys_audit_events',
     'Enterprise audit', 'dwp-platform-server', 'ACTIVE', 'CRITICAL', 'RESTRICTED',
     'VERIFIED', 'Central immutable audit-event store populated by service-local transactional outboxes.', NULL, CURRENT_TIMESTAMP),
    ('auth.public.com_role_hierarchy', 'auth', 'public', 'com_role_hierarchy',
     'Access governance', 'dwp-auth-server', 'PLANNED', 'HIGH', 'CONFIDENTIAL',
     'REVIEW_REQUIRED', 'Prepared role inheritance model for delegated enterprise authorization.',
     'No runtime owner currently reads or writes this empty table; implement the policy engine or retire it through a dedicated migration.', NULL),
    ('auth.public.com_separation_of_duty_rules', 'auth', 'public', 'com_separation_of_duty_rules',
     'Access governance', 'dwp-auth-server', 'PLANNED', 'HIGH', 'CONFIDENTIAL',
     'REVIEW_REQUIRED', 'Prepared segregation-of-duties policy model.',
     'No runtime owner currently reads or writes this empty table; connect it to access decisions before activation.', NULL),
    ('people.public.int_sync_errors', 'people', 'public', 'int_sync_errors',
     'HR integration', 'dwp-people-server', 'PLANNED', 'MEDIUM', 'CONFIDENTIAL',
     'REVIEW_REQUIRED', 'Prepared normalized error ledger for HR connector synchronization.',
     'Current connector error handling does not persist records here.', NULL),
    ('people.public.ppl_attribute_definitions', 'people', 'public', 'ppl_attribute_definitions',
     'Workforce extensions', 'dwp-people-server', 'PLANNED', 'MEDIUM', 'CONFIDENTIAL',
     'REVIEW_REQUIRED', 'Metadata definitions for tenant-extensible workforce attributes.',
     'Keep paired with ppl_attribute_values; activate only with schema validation and field-level authorization.', NULL),
    ('people.public.ppl_attribute_values', 'people', 'public', 'ppl_attribute_values',
     'Workforce extensions', 'dwp-people-server', 'PLANNED', 'MEDIUM', 'CONFIDENTIAL',
     'REVIEW_REQUIRED', 'Values for tenant-extensible workforce attributes.',
     'Keep paired with ppl_attribute_definitions; no runtime owner is implemented yet.', NULL),
    ('people.public.ppl_person_private', 'people', 'public', 'ppl_person_private',
     'Private workforce data', 'dwp-people-server', 'PLANNED', 'CRITICAL', 'RESTRICTED',
     'REVIEW_REQUIRED', 'Security boundary for highly restricted person attributes.',
     'Do not merge into ppl_persons; activation requires field encryption, purpose checks, and separate access policy.', NULL),
    ('platform.public.adm_message_overrides', 'platform', 'public', 'adm_message_overrides',
     'Tenant experience', 'dwp-platform-server', 'PLANNED', 'LOW', 'INTERNAL',
     'REVIEW_REQUIRED', 'Tenant-level copy override extension point.',
     'No runtime resolver currently consumes this empty table.', NULL),
    ('platform.public.sys_admin_command_requests', 'platform', 'public', 'sys_admin_command_requests',
     'Administrative change control', 'dwp-platform-server', 'PLANNED', 'HIGH', 'RESTRICTED',
     'REVIEW_REQUIRED', 'Prepared command ledger for approved tenant administration changes.',
     'Activate together with sys_admin_command_approvals and an executable command handler.', NULL),
    ('platform.public.sys_admin_command_approvals', 'platform', 'public', 'sys_admin_command_approvals',
     'Administrative change control', 'dwp-platform-server', 'PLANNED', 'HIGH', 'RESTRICTED',
     'REVIEW_REQUIRED', 'Prepared approval evidence for high-risk administrative commands.',
     'Keep paired with sys_admin_command_requests; no runtime approval workflow currently writes it.', NULL),
    ('provider.public.prv_service_health_observations', 'provider', 'public', 'prv_service_health_observations',
     'Service reliability', 'dwp-provider-server', 'PLANNED', 'HIGH', 'INTERNAL',
     'REVIEW_REQUIRED', 'Time-series-ready service health observation model.',
     'Current health views use tenant service snapshots; either connect ingestion and retention or retire this table.', NULL)
ON CONFLICT (asset_key) DO UPDATE SET
    business_domain = EXCLUDED.business_domain,
    owner_service = EXCLUDED.owner_service,
    lifecycle_state = EXCLUDED.lifecycle_state,
    criticality = EXCLUDED.criticality,
    data_classification = EXCLUDED.data_classification,
    review_state = EXCLUDED.review_state,
    description = EXCLUDED.description,
    review_note = EXCLUDED.review_note,
    last_reviewed_at = EXCLUDED.last_reviewed_at,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_data_lineage_edges (
    edge_key, source_asset_key, target_asset_key, process_key, edge_type,
    owner_service, description, evidence, metadata)
VALUES
    ('tenant-provisioning-auth', 'provider.public.prv_tenants', 'auth.public.com_tenants',
     'provider.tenant-onboarding.auth', 'PROVISIONING', 'dwp-provider-server',
     'Creates the identity tenant and records its service-local tenant identifier.',
     'ProviderProvisioningOrchestrator / AuthTenantProvisioningService',
     '{"keys":["provider_tenant_id -> public_id","auth_tenant_id -> tenant_id"]}'::jsonb),
    ('tenant-provisioning-platform', 'provider.public.prv_tenants', 'platform.public.sys_service_tenants',
     'provider.tenant-onboarding.platform', 'PROVISIONING', 'dwp-provider-server',
     'Creates the platform tenant projection used to enforce tenant context.',
     'ProviderProvisioningOrchestrator / PlatformTenantProvisioningService',
     '{"keys":["provider_tenant_id","auth_tenant_id -> tenant_id"]}'::jsonb),
    ('tenant-provisioning-people', 'provider.public.prv_tenants', 'people.public.sys_service_tenants',
     'provider.tenant-onboarding.people', 'PROVISIONING', 'dwp-provider-server',
     'Creates the people tenant projection used to isolate workforce records.',
     'ProviderProvisioningOrchestrator / PeopleTenantProvisioningService',
     '{"keys":["provider_tenant_id","auth_tenant_id -> tenant_id"]}'::jsonb),
    ('workforce-identity-projection', 'people.public.sys_people_outbox_events', 'auth.public.com_users',
     'people.worker-projection.changed', 'EVENT', 'dwp-people-server',
     'Projects governed workforce identity changes into authentication users.',
     'IdentitySyncOutboxRepository / WorkforceIdentitySyncService',
     '{"keys":["provider_tenant_id","person_public_id","external_id"]}'::jsonb),
    ('auth-audit-delivery', 'auth.public.sys_audit_outbox', 'platform.public.sys_audit_events',
     'audit.delivery.auth', 'EVENT', 'dwp-audit',
     'Publishes authentication and access-control audit records into the central audit store.',
     'AuditOutboxRelay / audit collector', '{}'::jsonb),
    ('people-audit-delivery', 'people.public.sys_audit_outbox', 'platform.public.sys_audit_events',
     'audit.delivery.people', 'EVENT', 'dwp-audit',
     'Publishes workforce and organization audit records into the central audit store.',
     'AuditOutboxRelay / audit collector', '{}'::jsonb),
    ('provider-audit-delivery', 'provider.public.sys_audit_outbox', 'platform.public.sys_audit_events',
     'audit.delivery.provider', 'EVENT', 'dwp-audit',
     'Publishes provider control-plane audit records into the central audit store.',
     'AuditOutboxRelay / audit collector', '{}'::jsonb),
    ('platform-audit-delivery', 'platform.public.sys_audit_outbox', 'platform.public.sys_audit_events',
     'audit.delivery.platform', 'EVENT', 'dwp-audit',
     'Publishes tenant administration audit records into the partitioned central audit store.',
     'AuditOutboxRelay / audit collector', '{}'::jsonb)
ON CONFLICT (edge_key) DO UPDATE SET
    source_asset_key = EXCLUDED.source_asset_key,
    target_asset_key = EXCLUDED.target_asset_key,
    process_key = EXCLUDED.process_key,
    edge_type = EXCLUDED.edge_type,
    owner_service = EXCLUDED.owner_service,
    description = EXCLUDED.description,
    evidence = EXCLUDED.evidence,
    metadata = EXCLUDED.metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
