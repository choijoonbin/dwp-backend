WITH skax_tenant AS (
    SELECT tenant_id
      FROM ppl_organizations
     WHERE organization_key = 'ROOT'
       AND name = 'SKAX'
)
UPDATE ppl_assignments assignment
   SET effective_end_date = DATE '2023-12-31',
       updated_at = CURRENT_TIMESTAMP
 WHERE assignment.tenant_id IN (SELECT tenant_id FROM skax_tenant)
   AND assignment.assignment_key = 'ASG-E100001-1'
   AND assignment.effective_start_date = DATE '2021-03-15';

WITH skax_tenant AS (
    SELECT tenant_id
      FROM ppl_organizations
     WHERE organization_key = 'ROOT'
       AND name = 'SKAX'
)
UPDATE ppl_assignments assignment
   SET job_grade_id = grade.job_grade_id,
       manager_assignment_key = 'ASG-SK0008-1',
       updated_at = CURRENT_TIMESTAMP
  FROM ppl_job_grades grade
 WHERE assignment.tenant_id IN (SELECT tenant_id FROM skax_tenant)
   AND grade.tenant_id = assignment.tenant_id
   AND grade.grade_key = 'G5'
   AND assignment.assignment_key = 'ASG-E100001-1'
   AND assignment.effective_start_date = DATE '2024-01-01';

WITH skax_tenant AS (
    SELECT tenant_id
      FROM ppl_organizations
     WHERE organization_key = 'ROOT'
       AND name = 'SKAX'
)
UPDATE ppl_assignments assignment
   SET effective_end_date = DATE '2026-08-14',
       updated_at = CURRENT_TIMESTAMP
 WHERE assignment.tenant_id IN (SELECT tenant_id FROM skax_tenant)
   AND assignment.assignment_key = 'ASG-E100002-1'
   AND assignment.effective_start_date = DATE '2023-07-03';

WITH skax_tenant AS (
    SELECT tenant_id
      FROM ppl_organizations
     WHERE organization_key = 'ROOT'
       AND name = 'SKAX'
)
UPDATE ppl_assignments assignment
   SET job_grade_id = grade.job_grade_id,
       updated_at = CURRENT_TIMESTAMP
  FROM ppl_job_grades grade
 WHERE assignment.tenant_id IN (SELECT tenant_id FROM skax_tenant)
   AND grade.tenant_id = assignment.tenant_id
   AND grade.grade_key = 'G3'
   AND assignment.assignment_key = 'ASG-E100002-1'
   AND assignment.effective_start_date = DATE '2026-08-15';
