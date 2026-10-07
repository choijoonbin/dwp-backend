package com.dwp.services.people.hr.assignment;

/** Canonical target and proposal projections used by assignment-governance queries. */
final class AssignmentProposalSql {

    static final String TARGET_SELECT = """
            SELECT assignment.assignment_id, assignment.public_id AS assignment_public_id,
                   assignment.assignment_key, assignment.assignment_status,
                   assignment.primary_assignment, assignment.effective_start_date,
                   assignment.effective_end_date, assignment.effective_sequence,
                   assignment.business_title, assignment.worker_hours,
                   assignment.full_time_equivalent, assignment.change_reason_code,
                   assignment.version AS assignment_version,
                   relationship.work_relationship_id,
                   relationship.public_id AS relationship_public_id,
                   relationship.version AS relationship_version,
                   worker.worker_id, worker.public_id AS worker_public_id,
                   worker.worker_number, worker.version AS worker_version,
                   person.display_name AS person_display_name,
                   organization.public_id AS organization_public_id,
                   organization.name AS organization_name,
                   job.job_key AS job_profile_key, job.name AS job_name,
                   location.location_key, location.name AS location_name,
                   assignment.manager_assignment_key,
                   manager.public_id AS manager_assignment_public_id
              FROM ppl_assignments assignment
              JOIN ppl_work_relationships relationship
                ON relationship.tenant_id = assignment.tenant_id
               AND relationship.work_relationship_id = assignment.work_relationship_id
              JOIN ppl_workers worker
                ON worker.tenant_id = relationship.tenant_id
               AND worker.worker_id = relationship.worker_id
              JOIN ppl_persons person
                ON person.tenant_id = worker.tenant_id
               AND person.person_id = worker.person_id
              LEFT JOIN ppl_organizations organization
                ON organization.tenant_id = assignment.tenant_id
               AND organization.organization_id = assignment.organization_id
              LEFT JOIN ppl_job_profiles job
                ON job.tenant_id = assignment.tenant_id
               AND job.job_profile_id = assignment.job_profile_id
              LEFT JOIN ppl_locations location
                ON location.tenant_id = assignment.tenant_id
               AND location.location_id = assignment.location_id
              LEFT JOIN LATERAL (
                  SELECT candidate.public_id
                    FROM ppl_assignments candidate
                   WHERE candidate.tenant_id = assignment.tenant_id
                     AND candidate.assignment_key = assignment.manager_assignment_key
                   ORDER BY candidate.effective_start_date DESC,
                            candidate.effective_sequence DESC
                   LIMIT 1
              ) manager ON TRUE
            """;

    static final String PROPOSAL_SELECT = """
            SELECT proposal.assignment_change_proposal_id, proposal.public_id,
                   proposal.tenant_id, proposal.target_assignment_id,
                   proposal.target_worker_id, proposal.target_work_relationship_id,
                   assignment.public_id AS assignment_public_id,
                   relationship.public_id AS relationship_public_id,
                   worker.public_id AS worker_public_id,
                   assignment.assignment_key, worker.worker_number,
                   person.display_name AS person_display_name,
                   proposal.change_type, proposal.effective_date, proposal.reason_code,
                   proposal.proposed_changes::text AS proposed_changes,
                   proposal.lifecycle_state,
                   proposal.validation_findings::text AS validation_findings,
                   proposal.content_sha256, proposal.validation_sha256,
                   proposal.target_worker_version,
                   proposal.target_relationship_version,
                   proposal.target_assignment_version,
                   proposal.aggregate_version, proposal.validated_at,
                   proposal.submitted_at, proposal.cancelled_at,
                   proposal.cancellation_reason, proposal.created_at, proposal.updated_at
              FROM ppl_assignment_change_proposals proposal
              JOIN ppl_assignments assignment
                ON assignment.tenant_id = proposal.tenant_id
               AND assignment.assignment_id = proposal.target_assignment_id
              JOIN ppl_work_relationships relationship
                ON relationship.tenant_id = proposal.tenant_id
               AND relationship.work_relationship_id = proposal.target_work_relationship_id
              JOIN ppl_workers worker
                ON worker.tenant_id = proposal.tenant_id
               AND worker.worker_id = proposal.target_worker_id
              JOIN ppl_persons person
                ON person.tenant_id = worker.tenant_id
               AND person.person_id = worker.person_id
            """;

    private AssignmentProposalSql() { }
}
