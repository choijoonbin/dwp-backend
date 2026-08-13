CREATE TABLE ppl_workforce_access_policies (
    workforce_access_policy_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_ref VARCHAR(80) NOT NULL,
    population_type VARCHAR(20) NOT NULL,
    organization_public_id UUID,
    field_groups VARCHAR(40)[] NOT NULL,
    action_codes VARCHAR(40)[] NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    justification VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_workforce_access_policy_organization
        FOREIGN KEY (organization_public_id)
        REFERENCES ppl_organizations(public_id),
    CONSTRAINT ck_workforce_access_policy_subject
        CHECK (subject_type IN ('ROLE', 'USER')),
    CONSTRAINT ck_workforce_access_policy_subject_ref
        CHECK ((subject_type = 'ROLE' AND subject_ref ~ '^[A-Z][A-Z0-9_]{1,79}$')
            OR (subject_type = 'USER' AND subject_ref ~ '^[1-9][0-9]{0,18}$')),
    CONSTRAINT ck_workforce_access_policy_population
        CHECK (population_type IN ('TENANT', 'ORG_UNIT', 'ORG_TREE')),
    CONSTRAINT ck_workforce_access_policy_population_ref
        CHECK ((population_type = 'TENANT' AND organization_public_id IS NULL)
            OR (population_type <> 'TENANT' AND organization_public_id IS NOT NULL)),
    CONSTRAINT ck_workforce_access_policy_fields
        CHECK (field_groups <@ ARRAY[
            'DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE'
        ]::VARCHAR[] AND cardinality(field_groups) > 0),
    CONSTRAINT ck_workforce_access_policy_actions
        CHECK (action_codes <@ ARRAY['READ', 'EXPORT']::VARCHAR[]
            AND cardinality(action_codes) > 0),
    CONSTRAINT ck_workforce_access_policy_window
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_workforce_access_policy_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE UNIQUE INDEX uk_workforce_access_policy_active
    ON ppl_workforce_access_policies (
        tenant_id, subject_type, subject_ref, population_type,
        COALESCE(organization_public_id, '00000000-0000-0000-0000-000000000000'::UUID))
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_workforce_access_policy_resolution
    ON ppl_workforce_access_policies (
        tenant_id, subject_type, subject_ref, lifecycle_state, valid_to);

INSERT INTO ppl_workforce_access_policies (
    tenant_id, subject_type, subject_ref, population_type, organization_public_id,
    field_groups, action_codes, justification, created_by, updated_by)
SELECT tenant_id, 'ROLE', role_code, 'TENANT', NULL,
       field_groups, ARRAY['READ']::VARCHAR[],
       'Baseline migration of the existing workforce administrator capability.', 1, 1
 FROM (SELECT DISTINCT tenant_id FROM ppl_persons) tenant
 CROSS JOIN (VALUES
    ('ADMIN', ARRAY['DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE']::VARCHAR[])
 ) role_policy(role_code, field_groups)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE ppl_workforce_access_policies IS
    'Server-enforced workforce population, field-group, and action boundary for tenant administrators.';
