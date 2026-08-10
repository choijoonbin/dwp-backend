ALTER TABLE ppl_positions
    ADD COLUMN public_id UUID DEFAULT gen_random_uuid(),
    ADD COLUMN reports_to_position_id BIGINT,
    ADD COLUMN position_type VARCHAR(24) NOT NULL DEFAULT 'REGULAR',
    ADD COLUMN criticality VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    ADD COLUMN budgeted_fte NUMERIC(7, 4) NOT NULL DEFAULT 1.0000,
    ADD COLUMN annual_cost_amount NUMERIC(18, 2),
    ADD COLUMN cost_currency CHAR(3),
    ADD COLUMN valid_from DATE NOT NULL DEFAULT DATE '1900-01-01',
    ADD COLUMN valid_to DATE;

UPDATE ppl_positions
   SET public_id = gen_random_uuid()
 WHERE public_id IS NULL;

ALTER TABLE ppl_positions
    ALTER COLUMN public_id SET NOT NULL,
    ADD CONSTRAINT uk_ppl_positions_public_id UNIQUE (public_id),
    ADD CONSTRAINT uk_ppl_positions_tenant_public_id UNIQUE (tenant_id, public_id),
    ADD CONSTRAINT fk_ppl_positions_reports_to
        FOREIGN KEY (tenant_id, reports_to_position_id)
        REFERENCES ppl_positions(tenant_id, position_id),
    ADD CONSTRAINT ck_ppl_positions_not_self
        CHECK (reports_to_position_id IS NULL OR reports_to_position_id <> position_id),
    ADD CONSTRAINT ck_ppl_positions_type
        CHECK (position_type IN ('REGULAR', 'SHARED', 'ASSISTANT', 'TEMPORARY')),
    ADD CONSTRAINT ck_ppl_positions_criticality
        CHECK (criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    ADD CONSTRAINT ck_ppl_positions_fte
        CHECK (budgeted_fte >= 0 AND budgeted_fte <= 10),
    ADD CONSTRAINT ck_ppl_positions_cost
        CHECK (annual_cost_amount IS NULL OR annual_cost_amount >= 0),
    ADD CONSTRAINT ck_ppl_positions_validity
        CHECK (valid_to IS NULL OR valid_to >= valid_from);

CREATE INDEX idx_ppl_positions_reporting
    ON ppl_positions(tenant_id, reports_to_position_id, position_status);
CREATE INDEX idx_ppl_positions_effective
    ON ppl_positions(tenant_id, valid_from, valid_to, position_status);

CREATE TABLE ppl_organization_role_assignments (
    organization_role_assignment_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    role_code VARCHAR(40) NOT NULL,
    person_id BIGINT,
    position_id BIGINT,
    primary_assignment BOOLEAN NOT NULL DEFAULT TRUE,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    source_system_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_organization_role_public_id UNIQUE (public_id),
    CONSTRAINT fk_ppl_organization_role_org
        FOREIGN KEY (tenant_id, organization_id)
        REFERENCES ppl_organizations(tenant_id, organization_id),
    CONSTRAINT fk_ppl_organization_role_person
        FOREIGN KEY (tenant_id, person_id)
        REFERENCES ppl_persons(tenant_id, person_id),
    CONSTRAINT fk_ppl_organization_role_position
        FOREIGN KEY (tenant_id, position_id)
        REFERENCES ppl_positions(tenant_id, position_id),
    CONSTRAINT fk_ppl_organization_role_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_organization_role_code
        CHECK (role_code IN ('LEADER', 'HR_BUSINESS_PARTNER', 'FINANCE_PARTNER',
                             'MATRIX_MANAGER', 'SECURITY_ADMIN')),
    CONSTRAINT ck_ppl_organization_role_holder
        CHECK (person_id IS NOT NULL OR position_id IS NOT NULL),
    CONSTRAINT ck_ppl_organization_role_validity
        CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date)
);

CREATE UNIQUE INDEX uk_ppl_organization_primary_role
    ON ppl_organization_role_assignments(tenant_id, organization_id, role_code)
    WHERE primary_assignment = TRUE AND effective_end_date IS NULL;
CREATE INDEX idx_ppl_organization_role_effective
    ON ppl_organization_role_assignments(
        tenant_id, organization_id, role_code, effective_start_date, effective_end_date);

CREATE TABLE ppl_org_design_policies (
    tenant_id BIGINT PRIMARY KEY,
    minimum_manager_span INTEGER NOT NULL DEFAULT 3,
    maximum_manager_span INTEGER NOT NULL DEFAULT 9,
    maximum_layers INTEGER NOT NULL DEFAULT 7,
    maximum_contingent_percent NUMERIC(5, 2) NOT NULL DEFAULT 20.00,
    maximum_vacancy_percent NUMERIC(5, 2) NOT NULL DEFAULT 15.00,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_ppl_org_policy_span
        CHECK (minimum_manager_span >= 1 AND maximum_manager_span >= minimum_manager_span),
    CONSTRAINT ck_ppl_org_policy_layers CHECK (maximum_layers BETWEEN 2 AND 20),
    CONSTRAINT ck_ppl_org_policy_contingent
        CHECK (maximum_contingent_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_ppl_org_policy_vacancy
        CHECK (maximum_vacancy_percent BETWEEN 0 AND 100)
);

COMMENT ON TABLE ppl_organization_role_assignments IS
    'Effective-dated organization accountabilities such as leader, HRBP, finance partner, and matrix manager.';
COMMENT ON TABLE ppl_org_design_policies IS
    'Tenant-owned explainable thresholds for span, layers, contingent concentration, and vacancy pressure.';

INSERT INTO ppl_org_design_policies (
    tenant_id, minimum_manager_span, maximum_manager_span, maximum_layers,
    maximum_contingent_percent, maximum_vacancy_percent,
    created_by, updated_by)
VALUES (1, 3, 8, 6, 20.00, 18.00, 1, 1)
ON CONFLICT (tenant_id) DO UPDATE SET
    minimum_manager_span = EXCLUDED.minimum_manager_span,
    maximum_manager_span = EXCLUDED.maximum_manager_span,
    maximum_layers = EXCLUDED.maximum_layers,
    maximum_contingent_percent = EXCLUDED.maximum_contingent_percent,
    maximum_vacancy_percent = EXCLUDED.maximum_vacancy_percent,
    version = ppl_org_design_policies.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

WITH current_assignments AS (
    SELECT DISTINCT ON (assignment.tenant_id, assignment.position_id)
           assignment.tenant_id,
           assignment.position_id,
           assignment.manager_assignment_key,
           assignment.full_time_equivalent,
           grade.level_order,
           worker.worker_type
      FROM ppl_assignments assignment
      JOIN ppl_work_relationships relationship
        ON relationship.tenant_id = assignment.tenant_id
       AND relationship.work_relationship_id = assignment.work_relationship_id
      JOIN ppl_workers worker
        ON worker.tenant_id = relationship.tenant_id
       AND worker.worker_id = relationship.worker_id
      LEFT JOIN ppl_job_grades grade
        ON grade.tenant_id = assignment.tenant_id
       AND grade.job_grade_id = assignment.job_grade_id
     WHERE assignment.position_id IS NOT NULL
       AND assignment.effective_start_date <= CURRENT_DATE
       AND (assignment.effective_end_date IS NULL OR assignment.effective_end_date >= CURRENT_DATE)
     ORDER BY assignment.tenant_id, assignment.position_id,
              assignment.primary_assignment DESC,
              assignment.effective_start_date DESC,
              assignment.effective_sequence DESC
), manager_positions AS (
    SELECT child.tenant_id,
           child.position_id,
           manager.position_id AS manager_position_id,
           child.full_time_equivalent,
           child.level_order,
           child.worker_type
      FROM current_assignments child
      LEFT JOIN ppl_assignments manager_assignment
        ON manager_assignment.tenant_id = child.tenant_id
       AND manager_assignment.assignment_key = child.manager_assignment_key
       AND manager_assignment.effective_start_date <= CURRENT_DATE
       AND (manager_assignment.effective_end_date IS NULL
            OR manager_assignment.effective_end_date >= CURRENT_DATE)
      LEFT JOIN ppl_positions manager
        ON manager.tenant_id = manager_assignment.tenant_id
       AND manager.position_id = manager_assignment.position_id
)
UPDATE ppl_positions position
   SET reports_to_position_id = manager_position.manager_position_id,
       budgeted_fte = COALESCE(manager_position.full_time_equivalent, 1.0000),
       annual_cost_amount = CASE
           WHEN manager_position.worker_type = 'CONTINGENT'
               THEN 120000000
           ELSE 42000000 + COALESCE(manager_position.level_order, 1) * 18000000
       END,
       cost_currency = 'KRW',
       criticality = CASE
           WHEN COALESCE(manager_position.level_order, 0) >= 6 THEN 'CRITICAL'
           WHEN COALESCE(manager_position.level_order, 0) >= 5 THEN 'HIGH'
           ELSE 'MEDIUM'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM manager_positions manager_position
 WHERE position.tenant_id = manager_position.tenant_id
   AND position.position_id = manager_position.position_id;

WITH report_counts AS (
    SELECT assignment.tenant_id,
           assignment.assignment_key,
           assignment.position_id,
           assignment.organization_id,
           COUNT(report.assignment_id) AS report_count
      FROM ppl_assignments assignment
      LEFT JOIN ppl_assignments report
        ON report.tenant_id = assignment.tenant_id
       AND report.manager_assignment_key = assignment.assignment_key
       AND report.effective_start_date <= CURRENT_DATE
       AND (report.effective_end_date IS NULL OR report.effective_end_date >= CURRENT_DATE)
     WHERE assignment.position_id IS NOT NULL
       AND assignment.effective_start_date <= CURRENT_DATE
       AND (assignment.effective_end_date IS NULL OR assignment.effective_end_date >= CURRENT_DATE)
     GROUP BY assignment.tenant_id, assignment.assignment_key,
              assignment.position_id, assignment.organization_id
), organization_leaders AS (
    SELECT DISTINCT ON (tenant_id, organization_id)
           tenant_id, organization_id, position_id
      FROM report_counts
     ORDER BY tenant_id, organization_id, report_count DESC, position_id
)
UPDATE ppl_positions position
   SET reports_to_position_id = leader.position_id,
       budgeted_fte = 1.0000,
       annual_cost_amount = CASE
           WHEN position.title ILIKE '%Principal%' OR position.title ILIKE '%Lead%'
               THEN 145000000
           WHEN position.title ILIKE '%Senior%'
               THEN 125000000
           ELSE 105000000
       END,
       cost_currency = 'KRW',
       criticality = CASE
           WHEN position.title ILIKE '%Lead%' OR position.title ILIKE '%Principal%'
               THEN 'HIGH'
           ELSE 'MEDIUM'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM organization_leaders leader
 WHERE position.tenant_id = leader.tenant_id
   AND position.organization_id = leader.organization_id
   AND position.position_status = 'OPEN'
   AND position.reports_to_position_id IS NULL;

WITH current_assignment AS (
    SELECT DISTINCT ON (assignment.tenant_id, assignment.assignment_key)
           assignment.tenant_id,
           assignment.assignment_key,
           assignment.organization_id,
           assignment.position_id,
           relationship.worker_id,
           grade.level_order
      FROM ppl_assignments assignment
      JOIN ppl_work_relationships relationship
        ON relationship.tenant_id = assignment.tenant_id
       AND relationship.work_relationship_id = assignment.work_relationship_id
      LEFT JOIN ppl_job_grades grade
        ON grade.tenant_id = assignment.tenant_id
       AND grade.job_grade_id = assignment.job_grade_id
     WHERE assignment.effective_start_date <= CURRENT_DATE
       AND (assignment.effective_end_date IS NULL OR assignment.effective_end_date >= CURRENT_DATE)
       AND assignment.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
     ORDER BY assignment.tenant_id, assignment.assignment_key,
              assignment.primary_assignment DESC,
              assignment.effective_start_date DESC,
              assignment.effective_sequence DESC
), report_counts AS (
    SELECT manager.tenant_id,
           manager.assignment_key,
           manager.organization_id,
           manager.position_id,
           manager.worker_id,
           manager.level_order,
           COUNT(report.assignment_key) AS report_count
      FROM current_assignment manager
      LEFT JOIN ppl_assignments report
        ON report.tenant_id = manager.tenant_id
       AND report.manager_assignment_key = manager.assignment_key
       AND report.effective_start_date <= CURRENT_DATE
       AND (report.effective_end_date IS NULL OR report.effective_end_date >= CURRENT_DATE)
     GROUP BY manager.tenant_id, manager.assignment_key, manager.organization_id,
              manager.position_id, manager.worker_id, manager.level_order
), ranked_leaders AS (
    SELECT report_counts.*,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, organization_id
               ORDER BY report_count DESC, level_order DESC NULLS LAST, assignment_key
           ) AS rank_in_org
      FROM report_counts
)
INSERT INTO ppl_organization_role_assignments (
    tenant_id, organization_id, role_code, person_id, position_id,
    primary_assignment, effective_start_date, source_system_id,
    created_by, updated_by)
SELECT leader.tenant_id,
       leader.organization_id,
       'LEADER',
       worker.person_id,
       leader.position_id,
       TRUE,
       DATE '2026-01-01',
       source.source_system_id,
       1,
       1
  FROM ranked_leaders leader
  JOIN ppl_workers worker
    ON worker.tenant_id = leader.tenant_id
   AND worker.worker_id = leader.worker_id
  LEFT JOIN int_source_systems source
    ON source.tenant_id = leader.tenant_id
   AND source.source_key = 'skax-demo-hris'
 WHERE leader.tenant_id = 1
   AND leader.rank_in_org = 1
ON CONFLICT (tenant_id, organization_id, role_code)
    WHERE primary_assignment = TRUE AND effective_end_date IS NULL
DO UPDATE SET
    person_id = EXCLUDED.person_id,
    position_id = EXCLUDED.position_id,
    source_system_id = EXCLUDED.source_system_id,
    version = ppl_organization_role_assignments.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO ppl_organization_scenarios (
    organization_scenario_id, tenant_id, scenario_key, name, description,
    baseline_date, effective_date, baseline_fingerprint, lifecycle_state,
    owner_user_id, created_by, updated_by)
VALUES (
    md5('skax-scenario:ai-scale-up-2027')::uuid,
    1,
    'ai-scale-up-2027',
    'AI Scale-up 2027',
    '고객 경험 조직을 디지털 컨설팅 체계에 결합하는 검토용 조직 설계 시나리오',
    DATE '2026-08-10',
    DATE '2027-01-01',
    'd136a82ee1b6a0c0c818939f8a234f453b707a069ae2e9f6d104625667b16028',
    'DRAFT',
    1,
    1,
    1)
ON CONFLICT (tenant_id, scenario_key) DO NOTHING;

INSERT INTO ppl_organization_scenario_changes (
    organization_scenario_change_id, tenant_id, organization_scenario_id,
    change_sequence, change_type, target_kind, target_reference,
    related_reference, effective_date, before_snapshot, after_snapshot,
    validation_state, validation_message, created_by, updated_by)
SELECT md5('skax-scenario-change:ai-scale-up-2027:cx')::uuid,
       1,
       scenario.organization_scenario_id,
       1,
       'MOVE_ORGANIZATION',
       'ORGANIZATION',
       customer_experience.public_id::text,
       digital_consulting.public_id::text,
       scenario.effective_date,
       jsonb_build_object(
           'parentOrganizationId', enterprise_transformation.public_id::text,
           'parentName', enterprise_transformation.name),
       jsonb_build_object(
           'parentOrganizationId', digital_consulting.public_id::text,
           'parentName', digital_consulting.name),
       'VALID',
       '정책 기준과 순환 구조 검증을 통과한 데모 변경',
       1,
       1
  FROM ppl_organization_scenarios scenario
  JOIN ppl_organizations customer_experience
    ON customer_experience.tenant_id = scenario.tenant_id
   AND customer_experience.organization_key = 'ORG-CX'
  JOIN ppl_organizations enterprise_transformation
    ON enterprise_transformation.tenant_id = scenario.tenant_id
   AND enterprise_transformation.organization_key = 'ORG-DX'
  JOIN ppl_organizations digital_consulting
    ON digital_consulting.tenant_id = scenario.tenant_id
   AND digital_consulting.organization_key = 'ORG-CONSULT'
 WHERE scenario.tenant_id = 1
   AND scenario.scenario_key = 'ai-scale-up-2027'
ON CONFLICT (organization_scenario_id, change_sequence) DO NOTHING;
