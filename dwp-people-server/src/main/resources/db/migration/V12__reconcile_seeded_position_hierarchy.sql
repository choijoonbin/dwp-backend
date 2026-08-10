WITH effective_assignment AS (
    SELECT DISTINCT ON (assignment.tenant_id, assignment.position_id)
           assignment.tenant_id,
           assignment.position_id,
           manager.position_id AS manager_position_id
      FROM ppl_assignments assignment
      JOIN ppl_positions position
        ON position.tenant_id = assignment.tenant_id
       AND position.position_id = assignment.position_id
      JOIN int_source_systems source
        ON source.tenant_id = position.tenant_id
       AND source.source_system_id = position.source_system_id
       AND source.source_key = 'skax-demo-hris'
      LEFT JOIN ppl_assignments manager_assignment
        ON manager_assignment.tenant_id = assignment.tenant_id
       AND manager_assignment.assignment_key = assignment.manager_assignment_key
       AND manager_assignment.effective_start_date <= CURRENT_DATE
       AND (manager_assignment.effective_end_date IS NULL
            OR manager_assignment.effective_end_date >= CURRENT_DATE)
      LEFT JOIN ppl_positions manager
        ON manager.tenant_id = manager_assignment.tenant_id
       AND manager.position_id = manager_assignment.position_id
     WHERE assignment.effective_start_date <= CURRENT_DATE
       AND (assignment.effective_end_date IS NULL
            OR assignment.effective_end_date >= CURRENT_DATE)
     ORDER BY assignment.tenant_id, assignment.position_id,
              assignment.primary_assignment DESC,
              assignment.effective_start_date DESC,
              assignment.effective_sequence DESC
)
UPDATE ppl_positions position
   SET reports_to_position_id = effective.manager_position_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM effective_assignment effective
 WHERE position.tenant_id = effective.tenant_id
   AND position.position_id = effective.position_id
   AND effective.manager_position_id IS NOT NULL
   AND position.reports_to_position_id IS DISTINCT FROM effective.manager_position_id;

UPDATE ppl_positions position
   SET position_status = 'OPEN',
       availability_date = COALESCE(position.availability_date, CURRENT_DATE),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM int_source_systems source
 WHERE source.tenant_id = position.tenant_id
   AND source.source_system_id = position.source_system_id
   AND source.source_key = 'skax-demo-hris'
   AND position.position_status = 'FILLED'
   AND NOT EXISTS (
       SELECT 1
         FROM ppl_assignments assignment
        WHERE assignment.tenant_id = position.tenant_id
          AND assignment.position_id = position.position_id
          AND assignment.effective_start_date <= CURRENT_DATE
          AND (assignment.effective_end_date IS NULL
               OR assignment.effective_end_date >= CURRENT_DATE)
   );

WITH primary_leader AS (
    SELECT DISTINCT ON (role.tenant_id, role.organization_id)
           role.tenant_id,
           role.organization_id,
           role.position_id
      FROM ppl_organization_role_assignments role
     WHERE role.role_code = 'LEADER'
       AND role.primary_assignment = TRUE
       AND role.position_id IS NOT NULL
       AND role.effective_start_date <= CURRENT_DATE
       AND (role.effective_end_date IS NULL OR role.effective_end_date >= CURRENT_DATE)
     ORDER BY role.tenant_id, role.organization_id,
              role.effective_start_date DESC,
              role.organization_role_assignment_id DESC
)
UPDATE ppl_positions position
   SET reports_to_position_id = leader.position_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM primary_leader leader
  JOIN int_source_systems source
    ON source.tenant_id = leader.tenant_id
   AND source.source_key = 'skax-demo-hris'
 WHERE position.tenant_id = leader.tenant_id
   AND position.organization_id = leader.organization_id
   AND position.source_system_id = source.source_system_id
   AND position.reports_to_position_id IS NULL
   AND position.position_id <> leader.position_id;

COMMENT ON COLUMN ppl_positions.reports_to_position_id IS
    'Operational position hierarchy reconciled from the authoritative HRIS manager assignment.';
