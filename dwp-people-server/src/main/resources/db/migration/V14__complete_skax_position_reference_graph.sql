-- Keep the SKAX reference tenant internally consistent at its documented baseline.
-- Production tenants derive the same facts from effective-dated HRIS deliveries.
UPDATE ppl_positions position
   SET position_status = 'OPEN',
       availability_date = COALESCE(position.availability_date, DATE '2026-08-10'),
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE position.tenant_id = 1
   AND position.position_status = 'FILLED'
   AND NOT EXISTS (
       SELECT 1
         FROM ppl_assignments assignment
        WHERE assignment.tenant_id = position.tenant_id
          AND assignment.position_id = position.position_id
          AND assignment.effective_start_date <= DATE '2026-08-10'
          AND (assignment.effective_end_date IS NULL
               OR assignment.effective_end_date >= DATE '2026-08-10'));

WITH company_leader AS (
    SELECT role.tenant_id, role.position_id
      FROM ppl_organization_role_assignments role
      JOIN ppl_organizations organization
        ON organization.tenant_id = role.tenant_id
       AND organization.organization_id = role.organization_id
       AND organization.organization_type = 'COMPANY'
     WHERE role.tenant_id = 1
       AND role.role_code = 'LEADER'
       AND role.primary_assignment = TRUE
       AND role.position_id IS NOT NULL
       AND role.effective_start_date <= DATE '2026-08-10'
       AND (role.effective_end_date IS NULL
            OR role.effective_end_date >= DATE '2026-08-10')
), organization_leader AS (
    SELECT DISTINCT ON (role.tenant_id, role.organization_id)
           role.tenant_id,
           role.organization_id,
           role.position_id,
           role.source_system_id
      FROM ppl_organization_role_assignments role
     WHERE role.tenant_id = 1
       AND role.role_code = 'LEADER'
       AND role.primary_assignment = TRUE
       AND role.position_id IS NOT NULL
       AND role.effective_start_date <= DATE '2026-08-10'
       AND (role.effective_end_date IS NULL
            OR role.effective_end_date >= DATE '2026-08-10')
     ORDER BY role.tenant_id, role.organization_id,
              role.effective_start_date DESC,
              role.organization_role_assignment_id DESC
), missing_parent AS (
    SELECT position.tenant_id,
           position.position_id AS child_position_id,
           leader.position_id AS parent_position_id,
           position.source_system_id,
           position.position_key,
           (
               SELECT MIN(next_relationship.effective_start_date)
                 FROM ppl_position_relationships next_relationship
                WHERE next_relationship.tenant_id = position.tenant_id
                  AND next_relationship.child_position_id = position.position_id
                  AND next_relationship.relationship_type = 'SUPERVISORY'
                  AND next_relationship.primary_relationship = TRUE
                  AND next_relationship.effective_start_date > DATE '2026-08-10'
           ) AS next_start_date
      FROM ppl_positions position
      JOIN organization_leader leader
        ON leader.tenant_id = position.tenant_id
       AND leader.organization_id = position.organization_id
       AND leader.position_id <> position.position_id
      LEFT JOIN company_leader root_leader
        ON root_leader.tenant_id = position.tenant_id
       AND root_leader.position_id = position.position_id
     WHERE position.tenant_id = 1
       AND root_leader.position_id IS NULL
       AND position.valid_from <= DATE '2026-08-10'
       AND (position.valid_to IS NULL OR position.valid_to >= DATE '2026-08-10')
       AND NOT EXISTS (
           SELECT 1
             FROM ppl_position_relationships relationship
            WHERE relationship.tenant_id = position.tenant_id
              AND relationship.child_position_id = position.position_id
              AND relationship.relationship_type = 'SUPERVISORY'
              AND relationship.primary_relationship = TRUE
              AND relationship.effective_start_date <= DATE '2026-08-10'
              AND (relationship.effective_end_date IS NULL
                   OR relationship.effective_end_date >= DATE '2026-08-10'))
)
INSERT INTO ppl_position_relationships (
    tenant_id, child_position_id, parent_position_id,
    relationship_type, primary_relationship, relationship_source,
    effective_start_date, effective_end_date,
    source_system_id, external_id, created_by, updated_by)
SELECT missing.tenant_id,
       missing.child_position_id,
       missing.parent_position_id,
       'SUPERVISORY',
       TRUE,
       'INFERRED',
       DATE '2026-08-10',
       missing.next_start_date - 1,
       missing.source_system_id,
       'position-baseline-fallback:' || missing.position_key,
       1,
       1
  FROM missing_parent missing
ON CONFLICT (tenant_id, source_system_id, external_id) DO NOTHING;
