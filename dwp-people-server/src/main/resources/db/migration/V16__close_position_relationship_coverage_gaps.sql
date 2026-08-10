-- Enforce source-aware relationship history after the reference graph bootstrap
-- and the governed scenario baseline stabilization.
CREATE EXTENSION IF NOT EXISTS btree_gist;

DROP INDEX uk_ppl_position_relationship_current_primary;

-- V14 bridged the reference date with bounded rows. INFERRED relationships are
-- deterministic projections of the current organization leader and can be
-- rebuilt safely. Normalize every derived slice before installing the temporal
-- exclusion constraint so rerun fixtures cannot leave overlapping fallbacks.
DELETE FROM ppl_position_relationships
 WHERE external_id LIKE 'position-baseline-fallback:%'
    OR relationship_source = 'INFERRED';

CREATE UNIQUE INDEX uk_ppl_position_relationship_current_primary_source
    ON ppl_position_relationships(
        tenant_id, child_position_id, relationship_type, relationship_source)
    WHERE primary_relationship = TRUE AND effective_end_date IS NULL;

ALTER TABLE ppl_position_relationships
    ADD CONSTRAINT ex_ppl_position_relationship_primary_period
    EXCLUDE USING gist (
        tenant_id WITH =,
        child_position_id WITH =,
        relationship_type WITH =,
        relationship_source WITH =,
        daterange(
            effective_start_date,
            COALESCE(effective_end_date + 1, 'infinity'::date),
            '[)') WITH &&
    ) WHERE (primary_relationship = TRUE);

-- Preserve an explicit position hierarchy as a fallback when HRIS assignments
-- only cover part of a position's effective history.
INSERT INTO ppl_position_relationships (
    tenant_id, child_position_id, parent_position_id,
    relationship_type, primary_relationship, relationship_source,
    effective_start_date, effective_end_date,
    source_system_id, external_id, created_by, updated_by)
SELECT position.tenant_id,
       position.position_id,
       position.reports_to_position_id,
       'SUPERVISORY',
       TRUE,
       'POSITION',
       position.valid_from,
       position.valid_to,
       position.source_system_id,
       'position-static:' || position.position_key,
       1,
       1
  FROM ppl_positions position
 WHERE position.reports_to_position_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
         FROM ppl_position_relationships relationship
        WHERE relationship.tenant_id = position.tenant_id
          AND relationship.child_position_id = position.position_id
          AND relationship.relationship_type = 'SUPERVISORY'
          AND relationship.relationship_source = 'POSITION'
   )
ON CONFLICT (tenant_id, source_system_id, external_id) DO NOTHING;

-- The inferred relationship is a lowest-priority continuity fallback. It is
-- retained beside explicit HRIS/position history so scheduled future changes
-- do not disconnect the chart before their effective date.
WITH current_primary_leader AS (
    SELECT DISTINCT ON (role.tenant_id, role.organization_id)
           role.tenant_id,
           role.organization_id,
           role.position_id,
           role.effective_start_date,
           role.effective_end_date
      FROM ppl_organization_role_assignments role
     WHERE role.role_code = 'LEADER'
       AND role.primary_assignment = TRUE
       AND role.position_id IS NOT NULL
       AND role.effective_end_date IS NULL
     ORDER BY role.tenant_id, role.organization_id,
              role.effective_start_date DESC,
              role.organization_role_assignment_id DESC
), inferred_relationship AS (
    SELECT position.tenant_id,
           position.position_id AS child_position_id,
           leader.position_id AS parent_position_id,
           GREATEST(position.valid_from, leader.effective_start_date)
               AS effective_start_date,
           position.valid_to AS effective_end_date,
           position.source_system_id,
           position.position_key
      FROM ppl_positions position
      JOIN current_primary_leader leader
        ON leader.tenant_id = position.tenant_id
       AND leader.organization_id = position.organization_id
       AND leader.position_id <> position.position_id
)
INSERT INTO ppl_position_relationships (
    tenant_id, child_position_id, parent_position_id,
    relationship_type, primary_relationship, relationship_source,
    effective_start_date, effective_end_date,
    source_system_id, external_id, created_by, updated_by)
SELECT inferred.tenant_id,
       inferred.child_position_id,
       inferred.parent_position_id,
       'SUPERVISORY',
       TRUE,
       'INFERRED',
       inferred.effective_start_date,
       inferred.effective_end_date,
       inferred.source_system_id,
       'position-inferred:' || inferred.position_key,
       1,
       1
  FROM inferred_relationship inferred
ON CONFLICT (tenant_id, source_system_id, external_id) DO UPDATE
    SET parent_position_id = EXCLUDED.parent_position_id,
        effective_start_date = EXCLUDED.effective_start_date,
        effective_end_date = EXCLUDED.effective_end_date,
        updated_at = CURRENT_TIMESTAMP,
        updated_by = EXCLUDED.updated_by,
        version = ppl_position_relationships.version + 1;
