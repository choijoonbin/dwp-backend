package com.dwp.services.people.security;

import com.dwp.services.people.hr.HcmPopulationRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

/** People-owned tenant and current-population check for a native legal employer identity. */
@Repository
public class HrisNavigationLegalEntityContextRepository {

    private static final UUID EMPTY_ORGANIZATION =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final NamedParameterJdbcTemplate jdbc;

    public HrisNavigationLegalEntityContextRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isCurrentOperationsLegalEmployer(
            long tenantId,
            HcmPopulationRepository.PopulationScope population,
            UUID legalEmployerPublicId) {
        Set<UUID> organizations = population.organizationIds().isEmpty()
                ? Set.of(EMPTY_ORGANIZATION) : population.organizationIds();
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ppl_legal_employers employer
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id = employer.tenant_id
                       AND relationship.legal_employer_id = employer.legal_employer_id
                       AND relationship.start_date <= CURRENT_DATE
                       AND (relationship.end_date IS NULL
                            OR relationship.end_date >= CURRENT_DATE)
                      JOIN ppl_workers worker
                        ON worker.tenant_id = relationship.tenant_id
                       AND worker.worker_id = relationship.worker_id
                       AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                      JOIN ppl_assignments assignment
                        ON assignment.tenant_id = relationship.tenant_id
                       AND assignment.work_relationship_id = relationship.work_relationship_id
                       AND assignment.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                       AND assignment.effective_start_date <= CURRENT_DATE
                       AND (assignment.effective_end_date IS NULL
                            OR assignment.effective_end_date >= CURRENT_DATE)
                      LEFT JOIN ppl_organizations organization
                        ON organization.tenant_id = assignment.tenant_id
                       AND organization.organization_id = assignment.organization_id
                     WHERE employer.tenant_id = :tenantId
                       AND employer.public_id = :legalEmployerPublicId
                       AND employer.lifecycle_state = 'ACTIVE'
                       AND worker.worker_id <> :actorWorkerId
                       AND (:tenantWide
                            OR assignment.manager_assignment_key = :managerAssignmentKey
                            OR organization.public_id IN (:organizationIds))
                )
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("legalEmployerPublicId", legalEmployerPublicId)
                .addValue("actorWorkerId", population.actorWorkerId())
                .addValue("tenantWide", population.tenantWide())
                .addValue("managerAssignmentKey", population.managerAssignmentKey())
                .addValue("organizationIds", organizations), Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
