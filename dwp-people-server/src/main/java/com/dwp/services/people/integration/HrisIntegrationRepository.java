package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HrisIntegrationRepository extends HrisIntegrationManagementRepository {
    public HrisIntegrationRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    /** Confirms that the caller-selected IDs belong to one active People tenant. */
    public boolean isActiveTenantBinding(Long tenantId, UUID providerTenantId) {
        Boolean bound = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sys_service_tenants tenant
                     WHERE tenant.tenant_id = :tenantId
                       AND tenant.provider_tenant_id = :providerTenantId
                       AND tenant.lifecycle_state = 'ACTIVE')
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("providerTenantId", providerTenantId), Boolean.class);
        return Boolean.TRUE.equals(bound);
    }

    /** Reads the owner-native identity and current assignment produced by an HRIS import. */
    public Optional<WorkforceIdentityProjection> findWorkforceIdentity(
            Long tenantId,
            String workerNumber) {
        List<WorkforceIdentityProjection> matches = jdbc.query("""
                SELECT worker.worker_id,
                       person.public_id AS person_public_id,
                       worker.public_id AS worker_public_id,
                       assignment.public_id AS assignment_public_id,
                       employer.public_id AS legal_employer_public_id,
                       worker.external_id,
                       worker.worker_number,
                       person.display_name,
                       person_name.given_name,
                       person_name.family_name,
                       work_email.display_value AS work_email,
                       assignment.business_title,
                       person.preferred_locale,
                       worker.worker_status,
                       assignment.source_version
                  FROM ppl_persons person
                  JOIN ppl_workers worker
                    ON worker.tenant_id = person.tenant_id
                   AND worker.person_id = person.person_id
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = worker.tenant_id
                   AND relationship.worker_id = worker.worker_id
                   AND relationship.start_date <= CURRENT_DATE
                   AND (relationship.end_date IS NULL
                        OR relationship.end_date >= CURRENT_DATE)
                   AND relationship.primary_relationship = TRUE
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id = relationship.tenant_id
                   AND employer.legal_employer_id = relationship.legal_employer_id
                   AND employer.lifecycle_state = 'ACTIVE'
                  JOIN LATERAL (
                      SELECT candidate.public_id, candidate.business_title,
                             candidate.source_version
                        FROM ppl_assignments candidate
                       WHERE candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                         AND candidate.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC,
                                candidate.assignment_id DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT candidate.given_name, candidate.family_name
                        FROM ppl_person_names candidate
                       WHERE candidate.tenant_id = person.tenant_id
                         AND candidate.person_id = person.person_id
                         AND candidate.name_type = 'PREFERRED'
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC
                       LIMIT 1
                  ) person_name ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT candidate.display_value
                        FROM ppl_contacts candidate
                       WHERE candidate.tenant_id = person.tenant_id
                         AND candidate.person_id = person.person_id
                         AND candidate.contact_type = 'EMAIL'
                         AND candidate.usage_type = 'WORK'
                         AND (candidate.valid_from IS NULL
                              OR candidate.valid_from <= CURRENT_DATE)
                         AND (candidate.valid_to IS NULL
                              OR candidate.valid_to >= CURRENT_DATE)
                       ORDER BY candidate.primary_contact DESC, candidate.contact_id DESC
                       LIMIT 1
                  ) work_email ON TRUE
                 WHERE person.tenant_id = :tenantId
                   AND person.lifecycle_state = 'ACTIVE'
                   AND worker.worker_number = :workerNumber
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE', 'PENDING')
                 ORDER BY relationship.start_date DESC,
                          relationship.work_relationship_id DESC
                """, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("workerNumber", workerNumber),
                (result, ignored) -> new WorkforceIdentityProjection(
                        result.getLong("worker_id"),
                        result.getObject("person_public_id", UUID.class),
                        result.getObject("worker_public_id", UUID.class),
                        result.getObject("assignment_public_id", UUID.class),
                        result.getObject("legal_employer_public_id", UUID.class),
                        result.getString("external_id"),
                        result.getString("worker_number"),
                        result.getString("display_name"),
                        result.getString("given_name"),
                        result.getString("family_name"),
                        result.getString("work_email"),
                        result.getString("business_title"),
                        result.getString("preferred_locale"),
                        result.getString("worker_status"),
                        result.getString("source_version")));
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Workforce identity has multiple current primary relationship lineages.");
        }
        return matches.stream().findFirst();
    }

    public record Receipt(
            long receiptId,
            UUID syncRunId,
            String state,
            String payloadSha256,
            boolean acquired) {
    }
    public record PersonUpsert(long personId, UUID publicId, boolean inserted) {
    }
    public record MappingRuntime(
            UUID mappingProfileId,
            long sourceSystemId,
            String profileKey,
            String adapterType,
            String sourceSchemaVersion,
            String targetSchemaVersion,
            String mappingDefinition,
            long version) {
    }

    public record WorkforceIdentityProjection(
            long workerId,
            UUID personPublicId,
            UUID workerPublicId,
            UUID assignmentPublicId,
            UUID legalEmployerPublicId,
            String externalId,
            String workerNumber,
            String displayName,
            String givenName,
            String familyName,
            String workEmail,
            String jobTitle,
            String preferredLocale,
            String workerStatus,
            String sourceVersion) {
    }
}
