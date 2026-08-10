CREATE TABLE ppl_position_relationships (
    position_relationship_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    child_position_id BIGINT NOT NULL,
    parent_position_id BIGINT NOT NULL,
    relationship_type VARCHAR(24) NOT NULL DEFAULT 'SUPERVISORY',
    primary_relationship BOOLEAN NOT NULL DEFAULT TRUE,
    relationship_source VARCHAR(20) NOT NULL DEFAULT 'POSITION',
    effective_start_date DATE NOT NULL,
    effective_end_date DATE,
    source_system_id BIGINT,
    external_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_ppl_position_relationship_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_position_relationship_external
        UNIQUE (tenant_id, source_system_id, external_id),
    CONSTRAINT fk_ppl_position_relationship_child
        FOREIGN KEY (tenant_id, child_position_id)
        REFERENCES ppl_positions(tenant_id, position_id),
    CONSTRAINT fk_ppl_position_relationship_parent
        FOREIGN KEY (tenant_id, parent_position_id)
        REFERENCES ppl_positions(tenant_id, position_id),
    CONSTRAINT fk_ppl_position_relationship_source
        FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES int_source_systems(tenant_id, source_system_id),
    CONSTRAINT ck_ppl_position_relationship_not_self
        CHECK (child_position_id <> parent_position_id),
    CONSTRAINT ck_ppl_position_relationship_type
        CHECK (relationship_type IN ('SUPERVISORY', 'MATRIX', 'FUNCTIONAL')),
    CONSTRAINT ck_ppl_position_relationship_source_kind
        CHECK (relationship_source IN ('HRIS', 'POSITION', 'INFERRED')),
    CONSTRAINT ck_ppl_position_relationship_dates
        CHECK (effective_end_date IS NULL OR effective_end_date >= effective_start_date)
);

CREATE INDEX idx_ppl_position_relationship_effective
    ON ppl_position_relationships(
        tenant_id, child_position_id, relationship_type,
        effective_start_date, effective_end_date);
CREATE INDEX idx_ppl_position_relationship_parent
    ON ppl_position_relationships(
        tenant_id, parent_position_id, relationship_type,
        effective_start_date, effective_end_date);
CREATE UNIQUE INDEX uk_ppl_position_relationship_current_primary
    ON ppl_position_relationships(tenant_id, child_position_id, relationship_type)
    WHERE primary_relationship = TRUE AND effective_end_date IS NULL;

WITH effective_manager AS (
    SELECT DISTINCT ON (
               assignment.tenant_id,
               assignment.position_id,
               assignment.effective_start_date)
           assignment.tenant_id,
           assignment.position_id AS child_position_id,
           manager.position_id AS parent_position_id,
           assignment.effective_start_date,
           assignment.effective_end_date,
           assignment.source_system_id,
           assignment.assignment_key
      FROM ppl_assignments assignment
      JOIN LATERAL (
          SELECT manager_assignment.position_id
            FROM ppl_assignments manager_assignment
           WHERE manager_assignment.tenant_id = assignment.tenant_id
             AND manager_assignment.assignment_key = assignment.manager_assignment_key
             AND manager_assignment.position_id IS NOT NULL
             AND manager_assignment.effective_start_date <= assignment.effective_start_date
             AND (manager_assignment.effective_end_date IS NULL
                  OR manager_assignment.effective_end_date >= assignment.effective_start_date)
           ORDER BY manager_assignment.effective_start_date DESC,
                    manager_assignment.effective_sequence DESC
           LIMIT 1
      ) manager ON TRUE
     WHERE assignment.position_id IS NOT NULL
       AND assignment.manager_assignment_key IS NOT NULL
     ORDER BY assignment.tenant_id, assignment.position_id,
              assignment.effective_start_date,
              assignment.primary_assignment DESC,
              assignment.effective_sequence DESC
)
INSERT INTO ppl_position_relationships (
    tenant_id, child_position_id, parent_position_id,
    relationship_type, primary_relationship, relationship_source,
    effective_start_date, effective_end_date,
    source_system_id, external_id, created_by, updated_by)
SELECT manager.tenant_id,
       manager.child_position_id,
       manager.parent_position_id,
       'SUPERVISORY',
       TRUE,
       'HRIS',
       manager.effective_start_date,
       manager.effective_end_date,
       manager.source_system_id,
       'position-manager:' || manager.assignment_key || ':' || manager.effective_start_date,
       1,
       1
  FROM effective_manager manager
 WHERE manager.child_position_id <> manager.parent_position_id
ON CONFLICT (tenant_id, source_system_id, external_id) DO NOTHING;

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
   )
ON CONFLICT (tenant_id, source_system_id, external_id) DO NOTHING;

WITH primary_leader AS (
    SELECT DISTINCT ON (role.tenant_id, role.organization_id)
           role.tenant_id,
           role.organization_id,
           role.position_id,
           role.source_system_id
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
INSERT INTO ppl_position_relationships (
    tenant_id, child_position_id, parent_position_id,
    relationship_type, primary_relationship, relationship_source,
    effective_start_date, source_system_id, external_id,
    created_by, updated_by)
SELECT position.tenant_id,
       position.position_id,
       leader.position_id,
       'SUPERVISORY',
       TRUE,
       'INFERRED',
       GREATEST(position.valid_from, CURRENT_DATE),
       position.source_system_id,
       'position-inferred:' || position.position_key,
       1,
       1
  FROM ppl_positions position
  JOIN primary_leader leader
    ON leader.tenant_id = position.tenant_id
   AND leader.organization_id = position.organization_id
   AND leader.position_id <> position.position_id
 WHERE NOT EXISTS (
       SELECT 1
         FROM ppl_position_relationships relationship
        WHERE relationship.tenant_id = position.tenant_id
          AND relationship.child_position_id = position.position_id
          AND relationship.relationship_type = 'SUPERVISORY'
   )
ON CONFLICT (tenant_id, source_system_id, external_id) DO NOTHING;

COMMENT ON TABLE ppl_position_relationships IS
    'Effective-dated solid, matrix, and functional position reporting relationships with provenance.';
