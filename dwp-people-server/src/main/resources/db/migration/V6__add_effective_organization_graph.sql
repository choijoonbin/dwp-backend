ALTER TABLE ppl_organizations
    ADD COLUMN short_name VARCHAR(80),
    ADD COLUMN description VARCHAR(1000),
    ADD COLUMN cost_center_key VARCHAR(100),
    ADD COLUMN color_token VARCHAR(30),
    ADD COLUMN valid_from DATE NOT NULL DEFAULT DATE '1900-01-01',
    ADD COLUMN valid_to DATE;

ALTER TABLE ppl_organizations
    ADD CONSTRAINT ck_ppl_organizations_validity
        CHECK (valid_to IS NULL OR valid_to >= valid_from);

CREATE TABLE ppl_organization_relationships (
    organization_relationship_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    child_organization_id BIGINT NOT NULL,
    parent_organization_id BIGINT NOT NULL,
    relationship_type VARCHAR(24) NOT NULL,
    primary_relationship BOOLEAN NOT NULL DEFAULT FALSE,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_org_relationships_slice UNIQUE (
        tenant_id,
        child_organization_id,
        parent_organization_id,
        relationship_type,
        effective_start_date
    ),
    CONSTRAINT fk_ppl_org_relationships_child
        FOREIGN KEY (tenant_id, child_organization_id)
        REFERENCES ppl_organizations(tenant_id, organization_id),
    CONSTRAINT fk_ppl_org_relationships_parent
        FOREIGN KEY (tenant_id, parent_organization_id)
        REFERENCES ppl_organizations(tenant_id, organization_id),
    CONSTRAINT fk_ppl_org_relationships_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_org_relationships_type
        CHECK (relationship_type IN ('SUPERVISORY', 'MATRIX', 'FUNCTIONAL')),
    CONSTRAINT ck_ppl_org_relationships_not_self
        CHECK (child_organization_id <> parent_organization_id),
    CONSTRAINT ck_ppl_org_relationships_validity
        CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date)
);

CREATE TABLE ppl_job_grades (
    job_grade_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    grade_key VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    level_order INTEGER NOT NULL,
    career_track VARCHAR(24) NOT NULL DEFAULT 'PROFESSIONAL',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_job_grades_key UNIQUE (tenant_id, grade_key),
    CONSTRAINT uk_ppl_job_grades_id UNIQUE (tenant_id, job_grade_id),
    CONSTRAINT fk_ppl_job_grades_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_job_grades_level CHECK (level_order > 0),
    CONSTRAINT ck_ppl_job_grades_track
        CHECK (career_track IN ('PROFESSIONAL', 'MANAGEMENT', 'EXECUTIVE', 'CONTRACTOR')),
    CONSTRAINT ck_ppl_job_grades_state CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE'))
);

ALTER TABLE ppl_assignments
    ADD COLUMN job_grade_id BIGINT,
    ADD CONSTRAINT fk_ppl_assignments_grade
        FOREIGN KEY (tenant_id, job_grade_id)
        REFERENCES ppl_job_grades(tenant_id, job_grade_id);

INSERT INTO ppl_organization_relationships (
    tenant_id,
    child_organization_id,
    parent_organization_id,
    relationship_type,
    primary_relationship,
    effective_start_date,
    source_system_id,
    external_id,
    created_by,
    updated_by)
SELECT organization.tenant_id,
       organization.organization_id,
       organization.parent_organization_id,
       'SUPERVISORY',
       TRUE,
       DATE '1900-01-01',
       organization.source_system_id,
       organization.organization_key || ':supervisory',
       organization.created_by,
       organization.updated_by
  FROM ppl_organizations organization
 WHERE organization.parent_organization_id IS NOT NULL
ON CONFLICT (
    tenant_id,
    child_organization_id,
    parent_organization_id,
    relationship_type,
    effective_start_date)
DO NOTHING;

CREATE INDEX idx_ppl_org_relationships_as_of
    ON ppl_organization_relationships (
        tenant_id,
        relationship_type,
        effective_start_date,
        effective_end_date,
        parent_organization_id,
        child_organization_id);

CREATE INDEX idx_ppl_job_grades_level
    ON ppl_job_grades(tenant_id, lifecycle_state, level_order);

CREATE INDEX idx_ppl_assignments_grade
    ON ppl_assignments(tenant_id, job_grade_id, effective_start_date DESC);
