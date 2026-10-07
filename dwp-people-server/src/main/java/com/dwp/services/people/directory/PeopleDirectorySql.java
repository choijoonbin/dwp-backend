package com.dwp.services.people.directory;

/** Stable select projection shared by directory lookup variants. */
final class PeopleDirectorySql {

    /**
     * A person is visible only when every current candidate employment belongs to the
     * actor's target population. This prevents a second, unauthorized employment from
     * being hidden behind an otherwise authorized worker row.
     */
    static final String TARGET_POPULATION_PREDICATE = """
             AND EXISTS (
                   SELECT 1
                     FROM ppl_workers candidate_worker
                     JOIN ppl_work_relationships candidate_relationship
                       ON candidate_relationship.tenant_id = candidate_worker.tenant_id
                      AND candidate_relationship.worker_id = candidate_worker.worker_id
                      AND candidate_relationship.start_date <= :asOf
                      AND (candidate_relationship.end_date IS NULL
                           OR candidate_relationship.end_date >= :asOf)
                     JOIN ppl_legal_employers candidate_employer
                       ON candidate_employer.tenant_id = candidate_relationship.tenant_id
                      AND candidate_employer.legal_employer_id =
                          candidate_relationship.legal_employer_id
                    WHERE candidate_worker.tenant_id = p.tenant_id
                      AND candidate_worker.person_id = p.person_id
                      AND candidate_worker.worker_status IN ('ACTIVE', 'LEAVE', 'PENDING')
             )
             AND NOT EXISTS (
                   SELECT 1
                     FROM ppl_workers candidate_worker
                     JOIN ppl_work_relationships candidate_relationship
                       ON candidate_relationship.tenant_id = candidate_worker.tenant_id
                      AND candidate_relationship.worker_id = candidate_worker.worker_id
                      AND candidate_relationship.start_date <= :asOf
                      AND (candidate_relationship.end_date IS NULL
                           OR candidate_relationship.end_date >= :asOf)
                     JOIN ppl_legal_employers candidate_employer
                       ON candidate_employer.tenant_id = candidate_relationship.tenant_id
                      AND candidate_employer.legal_employer_id =
                          candidate_relationship.legal_employer_id
                    WHERE candidate_worker.tenant_id = p.tenant_id
                      AND candidate_worker.person_id = p.person_id
                      AND candidate_worker.worker_status IN ('ACTIVE', 'LEAVE', 'PENDING')
                      AND NOT EXISTS (
                            SELECT 1
                              FROM ppl_workers population_worker
                              JOIN ppl_work_relationships population_relationship
                                ON population_relationship.tenant_id =
                                   population_worker.tenant_id
                               AND population_relationship.worker_id =
                                   population_worker.worker_id
                               AND population_relationship.start_date <= CURRENT_DATE
                               AND (population_relationship.end_date IS NULL
                                    OR population_relationship.end_date >= CURRENT_DATE)
                              JOIN ppl_assignments population_assignment
                                ON population_assignment.tenant_id =
                                   population_relationship.tenant_id
                               AND population_assignment.work_relationship_id =
                                   population_relationship.work_relationship_id
                               AND population_assignment.assignment_status IN
                                   ('ACTIVE', 'SUSPENDED', 'PENDING')
                               AND population_assignment.effective_start_date <= CURRENT_DATE
                               AND (population_assignment.effective_end_date IS NULL
                                    OR population_assignment.effective_end_date >= CURRENT_DATE)
                              LEFT JOIN ppl_organizations population_organization
                                ON population_organization.tenant_id =
                                   population_assignment.tenant_id
                               AND population_organization.organization_id =
                                   population_assignment.organization_id
                             WHERE population_worker.tenant_id = p.tenant_id
                               AND population_worker.worker_id = candidate_worker.worker_id
                               AND population_relationship.work_relationship_id =
                                   candidate_relationship.work_relationship_id
                               AND population_worker.worker_status IN ('ACTIVE', 'LEAVE')
                               AND population_worker.worker_id <> :populationActorWorkerId
                               AND (:populationTenantWide
                                    OR population_assignment.manager_assignment_key =
                                       :populationManagerAssignmentKey
                                    OR population_organization.public_id IN
                                       (:populationOrganizationIds))
                      )
             )
            """;

    static final String DIRECTORY_SELECT = """
            SELECT p.person_id,
                   p.public_id,
                   p.display_name,
                   p.preferred_locale,
                   p.time_zone,
                   p.lifecycle_state,
                   w.worker_number,
                   w.worker_type,
                   w.worker_status,
                   w.original_hire_date,
                   a.assignment_key,
                   a.business_title,
                   a.manager_assignment_key,
                   a.effective_start_date AS assignment_effective_from,
                   org.public_id AS organization_public_id,
                   org.organization_key,
                   org.name AS organization_name,
                   job.name AS job_profile_name,
                   job.management_level,
                   grade.grade_key,
                   grade.name AS grade_name,
                   loc.location_key,
                   loc.name AS location_name,
                   employer.legal_name AS legal_employer_name,
                   manager_person.public_id AS manager_person_public_id,
                   manager_person.display_name AS manager_display_name,
                   COALESCE(report_count.direct_report_count, 0) AS direct_report_count,
                   contact.display_value AS work_email,
                   media.object_key AS profile_image_key
              FROM ppl_persons p
              LEFT JOIN LATERAL (
                    SELECT candidate.*
                      FROM ppl_workers candidate
                     WHERE candidate.tenant_id = p.tenant_id
                       AND candidate.person_id = p.person_id
                     ORDER BY CASE candidate.worker_status
                                  WHEN 'ACTIVE' THEN 0 WHEN 'LEAVE' THEN 1
                                  WHEN 'PENDING' THEN 2 ELSE 3 END,
                              candidate.worker_id
                     LIMIT 1
              ) w ON TRUE
              LEFT JOIN LATERAL (
                    SELECT candidate.*, relationship.legal_employer_id
                      FROM ppl_assignments candidate
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id = candidate.tenant_id
                       AND relationship.work_relationship_id = candidate.work_relationship_id
                     WHERE candidate.tenant_id = p.tenant_id
                       AND relationship.worker_id = w.worker_id
                       AND candidate.effective_start_date <= :asOf
                       AND (candidate.effective_end_date IS NULL
                            OR candidate.effective_end_date >= :asOf)
                       AND (NOT :populationEnforced
                            OR :populationTenantWide
                            OR candidate.manager_assignment_key =
                               :populationManagerAssignmentKey
                            OR EXISTS (
                                SELECT 1
                                  FROM ppl_organizations boundary_organization
                                 WHERE boundary_organization.tenant_id = candidate.tenant_id
                                   AND boundary_organization.organization_id =
                                       candidate.organization_id
                                   AND boundary_organization.public_id IN
                                       (:populationOrganizationIds)))
                     ORDER BY candidate.primary_assignment DESC,
                              candidate.effective_start_date DESC,
                              candidate.effective_sequence DESC,
                              candidate.assignment_id DESC
                     LIMIT 1
              ) a ON TRUE
              LEFT JOIN ppl_organizations org
                ON org.tenant_id = p.tenant_id AND org.organization_id = a.organization_id
              LEFT JOIN ppl_job_profiles job
                ON job.tenant_id = p.tenant_id AND job.job_profile_id = a.job_profile_id
              LEFT JOIN ppl_job_grades grade
                ON grade.tenant_id = p.tenant_id AND grade.job_grade_id = a.job_grade_id
              LEFT JOIN ppl_locations loc
                ON loc.tenant_id = p.tenant_id AND loc.location_id = a.location_id
              LEFT JOIN ppl_legal_employers employer
                ON employer.tenant_id = p.tenant_id
               AND employer.legal_employer_id = a.legal_employer_id
              LEFT JOIN LATERAL (
                    SELECT manager.*
                      FROM ppl_assignments manager
                     WHERE manager.tenant_id = p.tenant_id
                       AND manager.assignment_key = a.manager_assignment_key
                       AND manager.effective_start_date <= :asOf
                       AND (manager.effective_end_date IS NULL
                            OR manager.effective_end_date >= :asOf)
                     ORDER BY manager.effective_start_date DESC,
                              manager.effective_sequence DESC,
                              manager.assignment_id DESC
                     LIMIT 1
              ) manager_assignment ON TRUE
              LEFT JOIN ppl_work_relationships manager_relationship
                ON manager_relationship.tenant_id = manager_assignment.tenant_id
               AND manager_relationship.work_relationship_id = manager_assignment.work_relationship_id
              LEFT JOIN ppl_workers manager_worker
                ON manager_worker.tenant_id = manager_relationship.tenant_id
               AND manager_worker.worker_id = manager_relationship.worker_id
              LEFT JOIN ppl_persons manager_person
                ON manager_person.tenant_id = manager_worker.tenant_id
               AND manager_person.person_id = manager_worker.person_id
              LEFT JOIN LATERAL (
                    SELECT COUNT(*)::INTEGER AS direct_report_count
                      FROM ppl_assignments report
                     WHERE report.tenant_id = p.tenant_id
                       AND report.manager_assignment_key = a.assignment_key
                       AND report.effective_start_date <= :asOf
                       AND (report.effective_end_date IS NULL
                            OR report.effective_end_date >= :asOf)
                       AND report.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
              ) report_count ON TRUE
              LEFT JOIN LATERAL (
                    SELECT candidate.display_value
                      FROM ppl_contacts candidate
                     WHERE candidate.tenant_id = p.tenant_id
                       AND candidate.person_id = p.person_id
                       AND candidate.contact_type = 'EMAIL'
                       AND candidate.usage_type = 'WORK'
                       AND candidate.visibility IN ('PUBLIC', 'INTERNAL')
                       AND (candidate.valid_from IS NULL OR candidate.valid_from <= :asOf)
                       AND (candidate.valid_to IS NULL OR candidate.valid_to >= :asOf)
                     ORDER BY candidate.primary_contact DESC, candidate.contact_id DESC
                     LIMIT 1
              ) contact ON TRUE
              LEFT JOIN LATERAL (
                    SELECT candidate.object_key
                      FROM ppl_profile_media candidate
                     WHERE candidate.tenant_id = p.tenant_id
                       AND candidate.person_id = p.person_id
                       AND candidate.lifecycle_state = 'ACTIVE'
                       AND candidate.visibility IN ('PUBLIC', 'INTERNAL')
                     ORDER BY candidate.profile_media_id DESC
                     LIMIT 1
              ) media ON TRUE
            """;

    private PeopleDirectorySql() { }
}
