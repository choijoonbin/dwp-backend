-- The Auth-owned local integration identity has a stable personPublicId, but
-- the People reference tenant did not previously own the corresponding
-- workforce proof. Keep SELF_PERSON_BINDING fail-closed and complete that
-- local-only fixture instead of weakening the HCM eligibility evaluator.

CREATE TEMP TABLE tmp_local_verification_workforce (
    person_public_id UUID PRIMARY KEY,
    person_key VARCHAR(100) NOT NULL,
    worker_number VARCHAR(100) NOT NULL,
    relationship_key VARCHAR(100) NOT NULL,
    position_key VARCHAR(100) NOT NULL,
    position_public_id UUID NOT NULL,
    assignment_key VARCHAR(100) NOT NULL,
    work_email VARCHAR(320) NOT NULL,
    external_id VARCHAR(255) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_local_verification_workforce VALUES (
    '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid,
    'DWP-LOCAL-900018',
    'DWP-900018',
    'DWP-900018-PRIMARY',
    'POS-DWP-900018',
    md5('dwp-local-verification-position:900018')::uuid,
    'ASG-DWP-900018-1',
    'joonbin@sk.com',
    'DWP-LOCAL-JOONBIN'
);

DO $$
DECLARE
    target_tenant_id BIGINT;
BEGIN
    SELECT tenant_id
      INTO target_tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
       AND lifecycle_state = 'ACTIVE';

    IF target_tenant_id IS NULL THEN
        RAISE EXCEPTION 'The active default People reference tenant is unavailable';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM int_source_systems source
          JOIN ppl_legal_employers employer
            ON employer.tenant_id = source.tenant_id
           AND employer.employer_key = 'SKAX-KR'
           AND employer.lifecycle_state = 'ACTIVE'
          JOIN ppl_organizations organization
            ON organization.tenant_id = source.tenant_id
           AND organization.organization_key = 'ORG-PEOPLE'
           AND organization.lifecycle_state = 'ACTIVE'
          JOIN ppl_job_profiles job
            ON job.tenant_id = source.tenant_id
           AND job.job_key = 'JOB-TALENT-MANAGER'
           AND job.lifecycle_state = 'ACTIVE'
          JOIN ppl_job_grades grade
            ON grade.tenant_id = source.tenant_id
           AND grade.grade_key = 'G4'
           AND grade.lifecycle_state = 'ACTIVE'
          JOIN ppl_locations location
            ON location.tenant_id = source.tenant_id
           AND location.location_key = 'SEOUL-HQ'
           AND location.lifecycle_state = 'ACTIVE'
          JOIN ppl_assignments manager_assignment
            ON manager_assignment.tenant_id = source.tenant_id
           AND manager_assignment.assignment_key = 'ASG-SK0028-1'
           AND manager_assignment.assignment_status IN ('ACTIVE', 'SUSPENDED')
           AND manager_assignment.effective_start_date <= CURRENT_DATE
           AND (manager_assignment.effective_end_date IS NULL
                OR manager_assignment.effective_end_date >= CURRENT_DATE)
         WHERE source.tenant_id = target_tenant_id
           AND source.source_key = 'skax-demo-hris'
           AND source.lifecycle_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'The SKAX People reference workforce prerequisites are incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ppl_persons person
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE (person.public_id = fixture.person_public_id
                AND (person.tenant_id <> target_tenant_id
                     OR person.person_key <> fixture.person_key))
            OR (person.tenant_id = target_tenant_id
                AND person.person_key = fixture.person_key
                AND person.public_id <> fixture.person_public_id)
            OR (person.tenant_id = target_tenant_id
                AND person.source_system_id = (
                    SELECT source_system_id
                      FROM int_source_systems
                     WHERE tenant_id = target_tenant_id
                       AND source_key = 'skax-demo-hris')
                AND person.external_id = fixture.external_id
                AND person.public_id <> fixture.person_public_id)
    ) THEN
        RAISE EXCEPTION 'The reserved local verification person identity is already in use';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ppl_contacts contact
          JOIN ppl_persons person
            ON person.tenant_id = contact.tenant_id
           AND person.person_id = contact.person_id
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE person.tenant_id = target_tenant_id
           AND person.public_id <> fixture.person_public_id
           AND contact.contact_type = 'EMAIL'
           AND contact.usage_type = 'WORK'
           AND LOWER(BTRIM(contact.display_value)) = fixture.work_email
    ) THEN
        RAISE EXCEPTION 'The reserved local verification work email is already in use';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ppl_workers worker
          JOIN ppl_persons person
            ON person.tenant_id = worker.tenant_id
           AND person.person_id = worker.person_id
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE worker.tenant_id = target_tenant_id
           AND worker.worker_number = fixture.worker_number
           AND person.public_id <> fixture.person_public_id
    ) THEN
        RAISE EXCEPTION 'The reserved local verification worker number is already in use';
    END IF;

    IF EXISTS (
        SELECT 1
         FROM ppl_work_relationships relationship
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE relationship.tenant_id = target_tenant_id
           AND relationship.relationship_key = fixture.relationship_key
           AND relationship.external_id IS DISTINCT FROM
               fixture.external_id || ':relationship'
    ) OR EXISTS (
        SELECT 1
          FROM ppl_positions position
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE position.tenant_id = target_tenant_id
           AND position.position_key = fixture.position_key
           AND (position.public_id <> fixture.position_public_id
                OR position.external_id IS DISTINCT FROM
                    fixture.external_id || ':position')
    ) OR EXISTS (
        SELECT 1
          FROM ppl_assignments assignment
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE assignment.tenant_id = target_tenant_id
           AND assignment.assignment_key = fixture.assignment_key
           AND assignment.external_id IS DISTINCT FROM
               fixture.external_id || ':assignment'
    ) THEN
        RAISE EXCEPTION 'A reserved local verification workforce key is already in use';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ppl_workers worker
          JOIN ppl_persons person
            ON person.tenant_id = worker.tenant_id
           AND person.person_id = worker.person_id
          JOIN ppl_work_relationships relationship
            ON relationship.tenant_id = worker.tenant_id
           AND relationship.worker_id = worker.worker_id
           AND relationship.primary_relationship = TRUE
           AND relationship.end_date IS NULL
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE person.public_id = fixture.person_public_id
           AND relationship.relationship_key <> fixture.relationship_key
    ) THEN
        RAISE EXCEPTION 'The local verification worker already has another primary relationship';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM int_external_mappings mapping
          CROSS JOIN tmp_local_verification_workforce fixture
         WHERE mapping.tenant_id = target_tenant_id
           AND mapping.source_system_id = (
               SELECT source_system_id
                 FROM int_source_systems
                WHERE tenant_id = target_tenant_id
                  AND source_key = 'skax-demo-hris')
           AND mapping.entity_type = 'PERSON'
           AND ((mapping.internal_key = fixture.person_public_id::text
                 AND mapping.external_id <> fixture.external_id)
                OR (mapping.external_id = fixture.external_id
                    AND mapping.internal_key <> fixture.person_public_id::text))
    ) THEN
        RAISE EXCEPTION 'The local verification person already has another source mapping';
    END IF;
END
$$;

INSERT INTO ppl_persons (
    public_id, tenant_id, person_key, display_name, preferred_locale, time_zone,
    lifecycle_state, source_system_id, external_id, created_by, updated_by)
SELECT fixture.person_public_id, tenant.tenant_id, fixture.person_key, '최준빈',
       'ko-KR', 'Asia/Seoul', 'ACTIVE', source.source_system_id,
       fixture.external_id, 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN sys_service_tenants tenant
    ON tenant.tenant_key = 'default' AND tenant.lifecycle_state = 'ACTIVE'
  JOIN int_source_systems source
    ON source.tenant_id = tenant.tenant_id
   AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, person_key) DO UPDATE SET
    public_id = EXCLUDED.public_id,
    display_name = EXCLUDED.display_name,
    preferred_locale = EXCLUDED.preferred_locale,
    time_zone = EXCLUDED.time_zone,
    lifecycle_state = 'ACTIVE',
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    version = ppl_persons.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_person_names (
    tenant_id, person_id, name_type, locale, given_name, family_name,
    formatted_name, effective_start_date, created_by, updated_by)
SELECT person.tenant_id, person.person_id, 'PREFERRED', 'ko-KR', '준빈', '최',
       '최준빈', DATE '2026-01-01', 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN ppl_persons person ON person.public_id = fixture.person_public_id
ON CONFLICT (
    tenant_id, person_id, name_type, locale,
    effective_start_date, effective_sequence)
DO UPDATE SET
    given_name = EXCLUDED.given_name,
    family_name = EXCLUDED.family_name,
    formatted_name = EXCLUDED.formatted_name,
    effective_end_date = NULL,
    version = ppl_person_names.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

UPDATE ppl_contacts contact
   SET primary_contact = FALSE,
       version = contact.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM ppl_persons person, tmp_local_verification_workforce fixture
 WHERE person.public_id = fixture.person_public_id
   AND contact.tenant_id = person.tenant_id
   AND contact.person_id = person.person_id
   AND contact.contact_type = 'EMAIL'
   AND contact.usage_type = 'WORK'
   AND LOWER(BTRIM(contact.display_value)) <> fixture.work_email;

UPDATE ppl_contacts contact
   SET display_value = fixture.work_email,
       primary_contact = TRUE,
       visibility = 'INTERNAL',
       valid_from = DATE '2026-01-01',
       valid_to = NULL,
       version = contact.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM ppl_persons person, tmp_local_verification_workforce fixture
 WHERE person.public_id = fixture.person_public_id
   AND contact.tenant_id = person.tenant_id
   AND contact.person_id = person.person_id
   AND contact.contact_type = 'EMAIL'
   AND contact.usage_type = 'WORK'
   AND LOWER(BTRIM(contact.display_value)) = fixture.work_email;

INSERT INTO ppl_contacts (
    tenant_id, person_id, contact_type, usage_type, display_value,
    primary_contact, visibility, valid_from, created_by, updated_by)
SELECT person.tenant_id, person.person_id, 'EMAIL', 'WORK', fixture.work_email,
       TRUE, 'INTERNAL', DATE '2026-01-01', 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN ppl_persons person ON person.public_id = fixture.person_public_id
 WHERE NOT EXISTS (
       SELECT 1
         FROM ppl_contacts contact
        WHERE contact.tenant_id = person.tenant_id
          AND contact.person_id = person.person_id
          AND contact.contact_type = 'EMAIL'
          AND contact.usage_type = 'WORK'
          AND LOWER(BTRIM(contact.display_value)) = fixture.work_email);

INSERT INTO ppl_workers (
    tenant_id, person_id, worker_number, worker_type, worker_status,
    original_hire_date, source_system_id, external_id, created_by, updated_by)
SELECT person.tenant_id, person.person_id, fixture.worker_number, 'EMPLOYEE', 'ACTIVE',
       DATE '2026-01-01', source.source_system_id, fixture.external_id || ':worker', 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN ppl_persons person ON person.public_id = fixture.person_public_id
  JOIN int_source_systems source
    ON source.tenant_id = person.tenant_id
   AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, worker_number) DO UPDATE SET
    person_id = EXCLUDED.person_id,
    worker_type = EXCLUDED.worker_type,
    worker_status = 'ACTIVE',
    original_hire_date = EXCLUDED.original_hire_date,
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    version = ppl_workers.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_work_relationships (
    tenant_id, relationship_key, worker_id, legal_employer_id,
    relationship_type, primary_relationship, start_date, source_system_id,
    external_id, created_by, updated_by)
SELECT worker.tenant_id, fixture.relationship_key, worker.worker_id,
       employer.legal_employer_id, 'EMPLOYEE', TRUE, DATE '2026-01-01',
       source.source_system_id, fixture.external_id || ':relationship', 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN sys_service_tenants tenant
    ON tenant.tenant_key = 'default' AND tenant.lifecycle_state = 'ACTIVE'
  JOIN ppl_persons person
    ON person.tenant_id = tenant.tenant_id
   AND person.public_id = fixture.person_public_id
  JOIN ppl_workers worker
    ON worker.tenant_id = tenant.tenant_id
   AND worker.person_id = person.person_id
   AND worker.worker_number = fixture.worker_number
  JOIN int_source_systems source
    ON source.tenant_id = worker.tenant_id
   AND source.source_key = 'skax-demo-hris'
  JOIN ppl_legal_employers employer
    ON employer.tenant_id = worker.tenant_id
   AND employer.employer_key = 'SKAX-KR'
ON CONFLICT (tenant_id, relationship_key) DO UPDATE SET
    worker_id = EXCLUDED.worker_id,
    legal_employer_id = EXCLUDED.legal_employer_id,
    relationship_type = EXCLUDED.relationship_type,
    primary_relationship = TRUE,
    start_date = EXCLUDED.start_date,
    end_date = NULL,
    projected_end_date = NULL,
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    version = ppl_work_relationships.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_positions (
    public_id, tenant_id, position_key, title, organization_id, job_profile_id,
    location_id, reports_to_position_id, position_status, position_type,
    criticality, budgeted_fte, valid_from, source_system_id, external_id,
    created_by, updated_by)
SELECT fixture.position_public_id, tenant.tenant_id, fixture.position_key,
       'Integrated Verification Administrator', organization.organization_id,
       job.job_profile_id, location.location_id, manager_assignment.position_id,
       'FILLED', 'REGULAR', 'MEDIUM', 1.0000, DATE '2026-01-01',
       source.source_system_id, fixture.external_id || ':position', 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN sys_service_tenants tenant
    ON tenant.tenant_key = 'default' AND tenant.lifecycle_state = 'ACTIVE'
  JOIN int_source_systems source
    ON source.tenant_id = tenant.tenant_id
   AND source.source_key = 'skax-demo-hris'
  JOIN ppl_organizations organization
    ON organization.tenant_id = tenant.tenant_id
   AND organization.organization_key = 'ORG-PEOPLE'
  JOIN ppl_job_profiles job
    ON job.tenant_id = tenant.tenant_id
   AND job.job_key = 'JOB-TALENT-MANAGER'
  JOIN ppl_locations location
    ON location.tenant_id = tenant.tenant_id
   AND location.location_key = 'SEOUL-HQ'
  JOIN ppl_assignments manager_assignment
    ON manager_assignment.tenant_id = tenant.tenant_id
   AND manager_assignment.assignment_key = 'ASG-SK0028-1'
   AND manager_assignment.effective_start_date <= CURRENT_DATE
   AND (manager_assignment.effective_end_date IS NULL
        OR manager_assignment.effective_end_date >= CURRENT_DATE)
ON CONFLICT (tenant_id, position_key) DO UPDATE SET
    public_id = EXCLUDED.public_id,
    title = EXCLUDED.title,
    organization_id = EXCLUDED.organization_id,
    job_profile_id = EXCLUDED.job_profile_id,
    location_id = EXCLUDED.location_id,
    reports_to_position_id = EXCLUDED.reports_to_position_id,
    position_status = 'FILLED',
    position_type = EXCLUDED.position_type,
    criticality = EXCLUDED.criticality,
    budgeted_fte = EXCLUDED.budgeted_fte,
    valid_from = EXCLUDED.valid_from,
    valid_to = NULL,
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    version = ppl_positions.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_assignments (
    tenant_id, assignment_key, work_relationship_id, effective_start_date,
    assignment_status, primary_assignment, position_id, job_profile_id,
    job_grade_id, organization_id, location_id, manager_assignment_key,
    business_title, cost_center_key, change_reason_code, full_time_equivalent,
    source_system_id, external_id, source_version, created_by, updated_by)
SELECT relationship.tenant_id, fixture.assignment_key,
       relationship.work_relationship_id, DATE '2026-01-01', 'ACTIVE', TRUE,
       position.position_id, job.job_profile_id, grade.job_grade_id,
       organization.organization_id, location.location_id, 'ASG-SK0028-1',
       'Integrated Verification Administrator', organization.cost_center_key,
       'REFERENCE_PROFILE', 1.0000, source.source_system_id,
       fixture.external_id || ':assignment', '2026.08-local-verification', 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN sys_service_tenants tenant
    ON tenant.tenant_key = 'default' AND tenant.lifecycle_state = 'ACTIVE'
  JOIN ppl_persons person
    ON person.tenant_id = tenant.tenant_id
   AND person.public_id = fixture.person_public_id
  JOIN ppl_workers worker
    ON worker.tenant_id = person.tenant_id
   AND worker.person_id = person.person_id
   AND worker.worker_number = fixture.worker_number
  JOIN ppl_work_relationships relationship
    ON relationship.tenant_id = worker.tenant_id
   AND relationship.worker_id = worker.worker_id
   AND relationship.relationship_key = fixture.relationship_key
  JOIN int_source_systems source
    ON source.tenant_id = relationship.tenant_id
   AND source.source_key = 'skax-demo-hris'
  JOIN ppl_positions position
    ON position.tenant_id = relationship.tenant_id
   AND position.position_key = fixture.position_key
  JOIN ppl_job_profiles job
    ON job.tenant_id = relationship.tenant_id
   AND job.job_key = 'JOB-TALENT-MANAGER'
  JOIN ppl_job_grades grade
    ON grade.tenant_id = relationship.tenant_id
   AND grade.grade_key = 'G4'
  JOIN ppl_organizations organization
    ON organization.tenant_id = relationship.tenant_id
   AND organization.organization_key = 'ORG-PEOPLE'
  JOIN ppl_locations location
    ON location.tenant_id = relationship.tenant_id
   AND location.location_key = 'SEOUL-HQ'
ON CONFLICT (tenant_id, assignment_key, effective_start_date, effective_sequence)
DO UPDATE SET
    work_relationship_id = EXCLUDED.work_relationship_id,
    effective_end_date = NULL,
    assignment_status = 'ACTIVE',
    primary_assignment = TRUE,
    position_id = EXCLUDED.position_id,
    job_profile_id = EXCLUDED.job_profile_id,
    job_grade_id = EXCLUDED.job_grade_id,
    organization_id = EXCLUDED.organization_id,
    location_id = EXCLUDED.location_id,
    manager_assignment_key = EXCLUDED.manager_assignment_key,
    business_title = EXCLUDED.business_title,
    cost_center_key = EXCLUDED.cost_center_key,
    change_reason_code = EXCLUDED.change_reason_code,
    full_time_equivalent = EXCLUDED.full_time_equivalent,
    source_system_id = EXCLUDED.source_system_id,
    external_id = EXCLUDED.external_id,
    source_version = EXCLUDED.source_version,
    version = ppl_assignments.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO int_external_mappings (
    tenant_id, source_system_id, entity_type, internal_key, external_id,
    external_version, last_seen_at, created_by, updated_by)
SELECT person.tenant_id, source.source_system_id, 'PERSON', person.public_id::text,
       fixture.external_id, '2026.08-local-verification', CURRENT_TIMESTAMP, 1, 1
  FROM tmp_local_verification_workforce fixture
  JOIN ppl_persons person ON person.public_id = fixture.person_public_id
  JOIN int_source_systems source
    ON source.tenant_id = person.tenant_id
   AND source.source_key = 'skax-demo-hris'
ON CONFLICT (tenant_id, source_system_id, entity_type, external_id)
DO UPDATE SET
    internal_key = EXCLUDED.internal_key,
    external_version = EXCLUDED.external_version,
    last_seen_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

DO $$
BEGIN
    IF (SELECT COUNT(*)
          FROM tmp_local_verification_workforce fixture
          JOIN ppl_persons person ON person.public_id = fixture.person_public_id
          JOIN ppl_workers worker
            ON worker.tenant_id = person.tenant_id
           AND worker.person_id = person.person_id
           AND worker.worker_number = fixture.worker_number
           AND worker.worker_status = 'ACTIVE'
          JOIN ppl_work_relationships relationship
            ON relationship.tenant_id = worker.tenant_id
           AND relationship.worker_id = worker.worker_id
           AND relationship.relationship_key = fixture.relationship_key
           AND relationship.primary_relationship = TRUE
           AND relationship.end_date IS NULL
          JOIN ppl_assignments assignment
            ON assignment.tenant_id = relationship.tenant_id
           AND assignment.work_relationship_id = relationship.work_relationship_id
           AND assignment.assignment_key = fixture.assignment_key
           AND assignment.primary_assignment = TRUE
           AND assignment.assignment_status = 'ACTIVE'
           AND assignment.effective_start_date <= CURRENT_DATE
           AND (assignment.effective_end_date IS NULL
                OR assignment.effective_end_date >= CURRENT_DATE)
          JOIN ppl_positions position
            ON position.tenant_id = assignment.tenant_id
           AND position.position_id = assignment.position_id
           AND position.position_key = fixture.position_key
           AND position.position_status = 'FILLED') <> 1 THEN
        RAISE EXCEPTION 'The local verification SELF workforce binding is incomplete';
    END IF;

    IF (SELECT COUNT(*)
          FROM tmp_local_verification_workforce fixture
          JOIN ppl_persons person ON person.public_id = fixture.person_public_id
          JOIN ppl_contacts contact
            ON contact.tenant_id = person.tenant_id
           AND contact.person_id = person.person_id
           AND contact.contact_type = 'EMAIL'
           AND contact.usage_type = 'WORK'
           AND contact.primary_contact = TRUE
           AND LOWER(BTRIM(contact.display_value)) = fixture.work_email) <> 1 THEN
        RAISE EXCEPTION 'The local verification work contact binding is incomplete';
    END IF;
END
$$;
