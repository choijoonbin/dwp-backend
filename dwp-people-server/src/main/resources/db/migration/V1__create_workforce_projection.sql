CREATE TABLE int_source_systems (
    source_system_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_key VARCHAR(100) NOT NULL,
    system_type VARCHAR(30) NOT NULL,
    name VARCHAR(200) NOT NULL,
    credential_reference VARCHAR(255),
    authoritative_domains JSONB NOT NULL DEFAULT '[]'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_int_source_systems_tenant_key UNIQUE (tenant_id, source_key),
    CONSTRAINT uk_int_source_systems_tenant_id UNIQUE (tenant_id, source_system_id),
    CONSTRAINT ck_int_source_systems_type
        CHECK (system_type IN ('WORKDAY', 'ORACLE_HCM', 'SAP_HCM', 'SCIM', 'CUSTOM')),
    CONSTRAINT ck_int_source_systems_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE ppl_persons (
    person_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    person_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    preferred_locale VARCHAR(35),
    time_zone VARCHAR(80),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_persons_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_persons_tenant_key UNIQUE (tenant_id, person_key),
    CONSTRAINT uk_ppl_persons_tenant_id UNIQUE (tenant_id, person_id),
    CONSTRAINT fk_ppl_persons_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_persons_state
        CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE', 'MERGED'))
);

CREATE UNIQUE INDEX uk_ppl_persons_external
    ON ppl_persons(tenant_id, source_system_id, external_id)
    WHERE source_system_id IS NOT NULL AND external_id IS NOT NULL;

CREATE TABLE ppl_person_names (
    person_name_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    name_type VARCHAR(20) NOT NULL,
    locale VARCHAR(35),
    given_name VARCHAR(120),
    middle_name VARCHAR(120),
    family_name VARCHAR(120),
    formatted_name VARCHAR(300) NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    effective_sequence INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_ppl_person_names_person
        FOREIGN KEY (tenant_id, person_id) REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT uk_ppl_person_names_slice
        UNIQUE NULLS NOT DISTINCT (
            tenant_id,
            person_id,
            name_type,
            locale,
            effective_start_date,
            effective_sequence
        ),
    CONSTRAINT ck_ppl_person_names_type CHECK (name_type IN ('LEGAL', 'PREFERRED', 'LOCAL')),
    CONSTRAINT ck_ppl_person_names_validity
        CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_ppl_person_names_sequence CHECK (effective_sequence > 0)
);

CREATE TABLE ppl_person_private (
    person_private_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    payload_schema_version INTEGER NOT NULL,
    key_reference VARCHAR(255) NOT NULL,
    retention_until DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_person_private_person UNIQUE (tenant_id, person_id),
    CONSTRAINT fk_ppl_person_private_person
        FOREIGN KEY (tenant_id, person_id) REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT ck_ppl_person_private_schema CHECK (payload_schema_version > 0)
);

CREATE TABLE ppl_person_identifiers (
    person_identifier_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    identifier_type VARCHAR(40) NOT NULL,
    value_hash CHAR(64) NOT NULL,
    encrypted_value BYTEA NOT NULL,
    key_reference VARCHAR(255) NOT NULL,
    display_suffix VARCHAR(12),
    issuing_country CHAR(2),
    valid_from DATE,
    valid_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_person_identifiers_hash
        UNIQUE (tenant_id, identifier_type, value_hash),
    CONSTRAINT fk_ppl_person_identifiers_person
        FOREIGN KEY (tenant_id, person_id) REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT ck_ppl_person_identifiers_validity
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE TABLE ppl_legal_employers (
    legal_employer_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    employer_key VARCHAR(100) NOT NULL,
    legal_name VARCHAR(240) NOT NULL,
    country_code CHAR(2),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_legal_employers_key UNIQUE (tenant_id, employer_key),
    CONSTRAINT uk_ppl_legal_employers_id UNIQUE (tenant_id, legal_employer_id),
    CONSTRAINT fk_ppl_legal_employers_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_legal_employers_state
        CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE ppl_organizations (
    organization_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    organization_key VARCHAR(100) NOT NULL,
    organization_type VARCHAR(30) NOT NULL,
    name VARCHAR(240) NOT NULL,
    parent_organization_id BIGINT,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_organizations_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_organizations_key UNIQUE (tenant_id, organization_key),
    CONSTRAINT uk_ppl_organizations_id UNIQUE (tenant_id, organization_id),
    CONSTRAINT fk_ppl_organizations_parent
        FOREIGN KEY (tenant_id, parent_organization_id)
        REFERENCES ppl_organizations(tenant_id, organization_id),
    CONSTRAINT fk_ppl_organizations_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_organizations_type CHECK (
        organization_type IN (
            'COMPANY', 'BUSINESS_UNIT', 'DIVISION', 'DEPARTMENT',
            'SUPERVISORY', 'COST_CENTER', 'CUSTOM'
        )
    ),
    CONSTRAINT ck_ppl_organizations_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_ppl_organizations_not_self CHECK (parent_organization_id <> organization_id)
);

CREATE TABLE ppl_job_profiles (
    job_profile_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    job_key VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    job_family_key VARCHAR(100),
    management_level VARCHAR(80),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_job_profiles_key UNIQUE (tenant_id, job_key),
    CONSTRAINT uk_ppl_job_profiles_id UNIQUE (tenant_id, job_profile_id),
    CONSTRAINT fk_ppl_job_profiles_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_job_profiles_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE ppl_locations (
    location_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    location_key VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    country_code CHAR(2),
    time_zone VARCHAR(80),
    address_payload JSONB,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_locations_key UNIQUE (tenant_id, location_key),
    CONSTRAINT uk_ppl_locations_id UNIQUE (tenant_id, location_id),
    CONSTRAINT fk_ppl_locations_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_locations_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE ppl_positions (
    position_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    position_key VARCHAR(100) NOT NULL,
    title VARCHAR(240) NOT NULL,
    organization_id BIGINT,
    job_profile_id BIGINT,
    location_id BIGINT,
    availability_date DATE,
    position_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_positions_key UNIQUE (tenant_id, position_key),
    CONSTRAINT uk_ppl_positions_id UNIQUE (tenant_id, position_id),
    CONSTRAINT fk_ppl_positions_org
        FOREIGN KEY (tenant_id, organization_id)
        REFERENCES ppl_organizations(tenant_id, organization_id),
    CONSTRAINT fk_ppl_positions_job
        FOREIGN KEY (tenant_id, job_profile_id)
        REFERENCES ppl_job_profiles(tenant_id, job_profile_id),
    CONSTRAINT fk_ppl_positions_location
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES ppl_locations(tenant_id, location_id),
    CONSTRAINT fk_ppl_positions_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_positions_status
        CHECK (position_status IN ('OPEN', 'FILLED', 'FROZEN', 'CLOSED'))
);

CREATE TABLE ppl_workers (
    worker_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    worker_number VARCHAR(100) NOT NULL,
    worker_type VARCHAR(24) NOT NULL,
    worker_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    original_hire_date DATE,
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_workers_number UNIQUE (tenant_id, worker_number),
    CONSTRAINT uk_ppl_workers_id UNIQUE (tenant_id, worker_id),
    CONSTRAINT fk_ppl_workers_person
        FOREIGN KEY (tenant_id, person_id) REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT fk_ppl_workers_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_workers_type
        CHECK (worker_type IN ('EMPLOYEE', 'CONTINGENT', 'NONWORKER', 'PENDING')),
    CONSTRAINT ck_ppl_workers_status
        CHECK (worker_status IN ('ACTIVE', 'LEAVE', 'TERMINATED', 'PENDING'))
);

CREATE TABLE ppl_work_relationships (
    work_relationship_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    relationship_key VARCHAR(100) NOT NULL,
    worker_id BIGINT NOT NULL,
    legal_employer_id BIGINT NOT NULL,
    relationship_type VARCHAR(24) NOT NULL,
    primary_relationship BOOLEAN NOT NULL DEFAULT FALSE,
    start_date DATE NOT NULL,
    end_date DATE,
    projected_end_date DATE,
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_work_relationships_key UNIQUE (tenant_id, relationship_key),
    CONSTRAINT uk_ppl_work_relationships_id UNIQUE (tenant_id, work_relationship_id),
    CONSTRAINT fk_ppl_work_relationships_worker
        FOREIGN KEY (tenant_id, worker_id) REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_ppl_work_relationships_employer
        FOREIGN KEY (tenant_id, legal_employer_id)
        REFERENCES ppl_legal_employers(tenant_id, legal_employer_id),
    CONSTRAINT fk_ppl_work_relationships_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_work_relationships_type
        CHECK (relationship_type IN ('EMPLOYEE', 'CONTINGENT', 'NONWORKER', 'PENDING')),
    CONSTRAINT ck_ppl_work_relationships_validity CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE UNIQUE INDEX uk_ppl_work_relationships_primary
    ON ppl_work_relationships(tenant_id, worker_id)
    WHERE primary_relationship = TRUE AND end_date IS NULL;

CREATE TABLE ppl_assignments (
    assignment_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    assignment_key VARCHAR(100) NOT NULL,
    work_relationship_id BIGINT NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    effective_sequence INTEGER NOT NULL DEFAULT 1,
    assignment_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    primary_assignment BOOLEAN NOT NULL DEFAULT FALSE,
    position_id BIGINT,
    job_profile_id BIGINT,
    organization_id BIGINT,
    location_id BIGINT,
    manager_assignment_key VARCHAR(100),
    business_title VARCHAR(240),
    cost_center_key VARCHAR(100),
    change_reason_code VARCHAR(80),
    worker_hours NUMERIC(8, 2),
    full_time_equivalent NUMERIC(5, 4),
    source_system_id BIGINT,
    external_id VARCHAR(255),
    source_version VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_assignments_slice
        UNIQUE (tenant_id, assignment_key, effective_start_date, effective_sequence),
    CONSTRAINT uk_ppl_assignments_id UNIQUE (tenant_id, assignment_id),
    CONSTRAINT fk_ppl_assignments_relationship
        FOREIGN KEY (tenant_id, work_relationship_id)
        REFERENCES ppl_work_relationships(tenant_id, work_relationship_id),
    CONSTRAINT fk_ppl_assignments_position
        FOREIGN KEY (tenant_id, position_id) REFERENCES ppl_positions(tenant_id, position_id),
    CONSTRAINT fk_ppl_assignments_job
        FOREIGN KEY (tenant_id, job_profile_id)
        REFERENCES ppl_job_profiles(tenant_id, job_profile_id),
    CONSTRAINT fk_ppl_assignments_org
        FOREIGN KEY (tenant_id, organization_id)
        REFERENCES ppl_organizations(tenant_id, organization_id),
    CONSTRAINT fk_ppl_assignments_location
        FOREIGN KEY (tenant_id, location_id) REFERENCES ppl_locations(tenant_id, location_id),
    CONSTRAINT fk_ppl_assignments_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_assignments_status
        CHECK (assignment_status IN ('ACTIVE', 'SUSPENDED', 'ENDED', 'PENDING')),
    CONSTRAINT ck_ppl_assignments_validity
        CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_ppl_assignments_sequence CHECK (effective_sequence > 0),
    CONSTRAINT ck_ppl_assignments_fte
        CHECK (full_time_equivalent IS NULL OR full_time_equivalent BETWEEN 0 AND 1)
);

CREATE TABLE ppl_profile_media (
    profile_media_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    media_type VARCHAR(24) NOT NULL DEFAULT 'PROFILE_IMAGE',
    object_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_profile_media_object UNIQUE (tenant_id, object_key),
    CONSTRAINT fk_ppl_profile_media_person
        FOREIGN KEY (tenant_id, person_id) REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT ck_ppl_profile_media_size CHECK (byte_size > 0),
    CONSTRAINT ck_ppl_profile_media_visibility
        CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'PRIVATE')),
    CONSTRAINT ck_ppl_profile_media_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE ppl_contacts (
    contact_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    contact_type VARCHAR(24) NOT NULL,
    usage_type VARCHAR(24) NOT NULL,
    display_value VARCHAR(320),
    encrypted_payload BYTEA,
    key_reference VARCHAR(255),
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    visibility VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    valid_from DATE,
    valid_to DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_ppl_contacts_person
        FOREIGN KEY (tenant_id, person_id) REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT ck_ppl_contacts_type CHECK (contact_type IN ('EMAIL', 'PHONE', 'ADDRESS')),
    CONSTRAINT ck_ppl_contacts_usage CHECK (usage_type IN ('WORK', 'HOME', 'EMERGENCY')),
    CONSTRAINT ck_ppl_contacts_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'PRIVATE')),
    CONSTRAINT ck_ppl_contacts_validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_ppl_contacts_payload CHECK (display_value IS NOT NULL OR encrypted_payload IS NOT NULL)
);

CREATE TABLE ppl_attribute_definitions (
    attribute_definition_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    attribute_key VARCHAR(120) NOT NULL,
    name VARCHAR(200) NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    data_classification VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    validation_schema JSONB,
    searchable BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_attribute_definitions_key
        UNIQUE (tenant_id, entity_type, attribute_key),
    CONSTRAINT uk_ppl_attribute_definitions_id
        UNIQUE (tenant_id, attribute_definition_id),
    CONSTRAINT ck_ppl_attribute_definitions_entity
        CHECK (entity_type IN ('PERSON', 'WORKER', 'ASSIGNMENT', 'POSITION')),
    CONSTRAINT ck_ppl_attribute_definitions_value
        CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'DATE', 'CODE', 'JSON')),
    CONSTRAINT ck_ppl_attribute_definitions_classification
        CHECK (data_classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_ppl_attribute_definitions_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE ppl_attribute_values (
    attribute_value_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    attribute_definition_id BIGINT NOT NULL,
    entity_id BIGINT NOT NULL,
    value_payload JSONB NOT NULL,
    effective_start_date DATE,
    effective_end_date DATE,
    effective_sequence INTEGER NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_ppl_attribute_values_definition
        FOREIGN KEY (tenant_id, attribute_definition_id)
        REFERENCES ppl_attribute_definitions(tenant_id, attribute_definition_id),
    CONSTRAINT uk_ppl_attribute_values_slice
        UNIQUE NULLS NOT DISTINCT (
            tenant_id,
            attribute_definition_id,
            entity_id,
            effective_start_date,
            effective_sequence
        ),
    CONSTRAINT ck_ppl_attribute_values_validity
        CHECK (effective_end_date IS NULL OR effective_start_date IS NULL OR effective_end_date >= effective_start_date),
    CONSTRAINT ck_ppl_attribute_values_sequence CHECK (effective_sequence > 0)
);

CREATE TABLE int_external_mappings (
    external_mapping_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_system_id BIGINT NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    internal_key VARCHAR(160) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    external_version VARCHAR(160),
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_int_external_mappings_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT uk_int_external_mappings_external
        UNIQUE (tenant_id, source_system_id, entity_type, external_id),
    CONSTRAINT uk_int_external_mappings_internal
        UNIQUE (tenant_id, source_system_id, entity_type, internal_key)
);

CREATE TABLE int_sync_runs (
    sync_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    source_system_id BIGINT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    sync_mode VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    requested_watermark VARCHAR(500),
    committed_watermark VARCHAR(500),
    read_count BIGINT NOT NULL DEFAULT 0,
    created_count BIGINT NOT NULL DEFAULT 0,
    updated_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_int_sync_runs_tenant_id UNIQUE (tenant_id, sync_run_id),
    CONSTRAINT fk_int_sync_runs_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT uk_int_sync_runs_correlation UNIQUE (tenant_id, correlation_id),
    CONSTRAINT ck_int_sync_runs_mode CHECK (sync_mode IN ('FULL', 'DELTA', 'EVENT', 'REPLAY')),
    CONSTRAINT ck_int_sync_runs_state
        CHECK (lifecycle_state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'))
);

CREATE TABLE int_sync_errors (
    sync_error_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    sync_run_id UUID NOT NULL,
    entity_type VARCHAR(40),
    external_id VARCHAR(255),
    error_code VARCHAR(80) NOT NULL,
    redacted_message VARCHAR(1000) NOT NULL,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_int_sync_errors_run
        FOREIGN KEY (tenant_id, sync_run_id)
        REFERENCES int_sync_runs(tenant_id, sync_run_id)
);

CREATE TABLE sys_people_audit_events (
    audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(128),
    source_system_id BIGINT,
    before_snapshot JSONB,
    after_snapshot JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_people_audit_actor CHECK (actor_type IN ('USER', 'SERVICE', 'AGENT')),
    CONSTRAINT ck_sys_people_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE TABLE sys_people_outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_sys_people_outbox_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_ppl_persons_tenant_name
    ON ppl_persons(tenant_id, lifecycle_state, display_name, person_id);
CREATE INDEX idx_ppl_person_names_current
    ON ppl_person_names(tenant_id, person_id, name_type, effective_start_date DESC);
CREATE INDEX idx_ppl_workers_tenant_status
    ON ppl_workers(tenant_id, worker_status, worker_number);
CREATE INDEX idx_ppl_organizations_parent
    ON ppl_organizations(tenant_id, parent_organization_id, lifecycle_state);
CREATE INDEX idx_ppl_assignments_current
    ON ppl_assignments(tenant_id, assignment_status, effective_start_date, effective_end_date);
CREATE INDEX idx_ppl_assignments_org
    ON ppl_assignments(tenant_id, organization_id, effective_start_date DESC);
CREATE INDEX idx_ppl_assignments_manager
    ON ppl_assignments(tenant_id, manager_assignment_key, effective_start_date DESC);
CREATE INDEX idx_ppl_profile_media_person
    ON ppl_profile_media(tenant_id, person_id, lifecycle_state);
CREATE INDEX idx_int_sync_runs_source_time
    ON int_sync_runs(tenant_id, source_system_id, created_at DESC);
CREATE INDEX idx_int_sync_errors_run
    ON int_sync_errors(tenant_id, sync_run_id, occurred_at);
CREATE INDEX idx_sys_people_audit_tenant_time
    ON sys_people_audit_events(tenant_id, occurred_at DESC);
CREATE INDEX idx_sys_people_outbox_pending
    ON sys_people_outbox_events(occurred_at)
    WHERE published_at IS NULL;
