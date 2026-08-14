ALTER TABLE ppl_workers
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uk_ppl_workers_public_id UNIQUE (public_id);

ALTER TABLE ppl_work_relationships
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uk_ppl_work_relationships_public_id UNIQUE (public_id);

ALTER TABLE ppl_assignments
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD CONSTRAINT uk_ppl_assignments_public_id UNIQUE (public_id);

CREATE UNIQUE INDEX uk_ppl_assignments_current_primary
    ON ppl_assignments(tenant_id, work_relationship_id)
    WHERE primary_assignment = TRUE
      AND effective_end_date IS NULL
      AND assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING');

COMMENT ON TABLE ppl_persons IS
    'Stable natural-person identity. Employment lifecycle data belongs to worker, work relationship, and assignment entities.';
COMMENT ON TABLE ppl_workers IS
    'Tenant worker identity linked to one person; a person may have multiple worker records over time or worker types.';
COMMENT ON TABLE ppl_work_relationships IS
    'Effective relationship between a worker and legal employer. Rehire creates a new relationship without replacing the person.';
COMMENT ON TABLE ppl_assignments IS
    'Effective-dated work assignment within a work relationship, including job, organization, location, manager, hours, and FTE.';

COMMENT ON COLUMN ppl_workers.public_id IS
    'Stable API identifier for the Worker entity. Internal worker_id is never exposed as an integration contract.';
COMMENT ON COLUMN ppl_work_relationships.public_id IS
    'Stable API identifier for the WorkRelationship entity.';
COMMENT ON COLUMN ppl_assignments.public_id IS
    'Stable API identifier for the Assignment entity independent of effective-dated assignment_key slices.';
