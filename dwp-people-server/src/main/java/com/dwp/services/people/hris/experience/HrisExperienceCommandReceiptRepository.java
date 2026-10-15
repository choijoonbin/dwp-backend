package com.dwp.services.people.hris.experience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only adapter over the existing V54 command-receipt ledger. */
@Repository
class HrisExperienceCommandReceiptRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    HrisExperienceCommandReceiptRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<ReceiptRecord> find(long tenantId, UUID receiptId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT command_receipt_id,tenant_id,command_type,idempotency_key,
                       request_hash,actor_public_id,originating_action,
                       subject_principal_public_id,population_scope_digest,
                       field_policy_revision,purpose_code,authorization_revision,
                       originating_context_digest,target_type,target_public_id,
                       expected_version,lifecycle_state,result_code,
                       result_reference::text AS result_reference,correlation_id,created_at
                  FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                """, parameters(tenantId).addValue("receipt", receiptId), this::receipt)
                .stream().findFirst();
    }

    Optional<TargetEvidence> assignmentTarget(long tenantId, UUID eventPublicId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT event.public_id,event.target_worker_id,
                       CASE WHEN event.event_payload #>>
                           '{assignment,organizationRef,publicId}' ~*
                           '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                            THEN (event.event_payload #>>
                              '{assignment,organizationRef,publicId}')::uuid
                            ELSE NULL END AS organization_public_id,
                       event.version AS source_revision,
                       '["EMPLOYMENT"]'::jsonb::text AS field_groups,
                       event.event_type
                  FROM ppl_assignment_events event
                 WHERE event.tenant_id=:tenant AND event.public_id=:target
                """, parameters(tenantId).addValue("target", eventPublicId), this::target)
                .stream().findFirst();
    }

    Optional<TargetEvidence> employeeChangeTarget(long tenantId, UUID requestPublicId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT request.public_id,worker.worker_id AS target_worker_id,
                       assignment.organization_public_id,
                       request.version AS source_revision,
                       request.field_groups::text AS field_groups,
                       NULL::varchar AS event_type
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons person
                    ON person.tenant_id=request.tenant_id
                   AND person.person_id=request.target_person_id
                  LEFT JOIN LATERAL (
                       SELECT value.worker_id
                         FROM ppl_workers value
                        WHERE value.tenant_id=person.tenant_id
                          AND value.person_id=person.person_id
                        ORDER BY CASE value.worker_status
                                   WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                                 value.worker_id
                        LIMIT 1
                  ) worker ON true
                  LEFT JOIN LATERAL (
                       SELECT organization.public_id AS organization_public_id
                         FROM ppl_assignments assignment
                         JOIN ppl_organizations organization
                           ON organization.tenant_id=assignment.tenant_id
                          AND organization.organization_id=assignment.organization_id
                        WHERE assignment.tenant_id=request.tenant_id
                          AND assignment.worker_id=worker.worker_id
                        ORDER BY assignment.primary_assignment DESC,
                                 assignment.effective_start_date DESC,
                                 assignment.assignment_id DESC
                        LIMIT 1
                  ) assignment ON true
                 WHERE request.tenant_id=:tenant AND request.public_id=:target
                """, parameters(tenantId).addValue("target", requestPublicId), this::target)
                .stream().findFirst();
    }

    private ReceiptRecord receipt(ResultSet result, int ignored) throws SQLException {
        return new ReceiptRecord(
                result.getObject("command_receipt_id", UUID.class),
                result.getLong("tenant_id"), result.getString("command_type"),
                result.getString("idempotency_key"), result.getString("request_hash"),
                result.getObject("actor_public_id", UUID.class),
                result.getString("originating_action"),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"), result.getString("purpose_code"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                result.getString("target_type"),
                result.getObject("target_public_id", UUID.class),
                result.getObject("expected_version", Long.class),
                result.getString("lifecycle_state"), result.getString("result_code"),
                result.getString("result_reference"),
                result.getObject("correlation_id", UUID.class),
                result.getTimestamp("created_at").toInstant());
    }

    private TargetEvidence target(ResultSet result, int ignored) throws SQLException {
        try {
            return new TargetEvidence(
                    result.getObject("public_id", UUID.class),
                    result.getObject("target_worker_id", Long.class),
                    result.getObject("organization_public_id", UUID.class),
                    result.getLong("source_revision"),
                    List.copyOf(mapper.readValue(
                            result.getString("field_groups"), STRING_LIST)),
                    result.getString("event_type"));
        } catch (Exception exception) {
            throw new SQLException("HRIS receipt target evidence is invalid.", exception);
        }
    }

    private void establishTenant(long tenantId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', CAST(:tenant AS text), true)",
                parameters(tenantId), String.class);
    }

    private MapSqlParameterSource parameters(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    record ReceiptRecord(
            UUID receiptId,
            long tenantId,
            String commandType,
            String idempotencyKey,
            String requestDigest,
            UUID actorPublicId,
            String action,
            UUID subjectPublicId,
            String populationDigest,
            long fieldRevision,
            String purpose,
            long authorizationRevision,
            String contextDigest,
            String targetType,
            UUID targetPublicId,
            Long expectedVersion,
            String lifecycleState,
            String resultCode,
            String resultReference,
            UUID correlationId,
            Instant createdAt) {
    }

    record TargetEvidence(
            UUID publicId,
            Long workerId,
            UUID organizationPublicId,
            long sourceRevision,
            List<String> fieldGroups,
            String eventType) {
        TargetEvidence {
            fieldGroups = List.copyOf(fieldGroups);
        }
    }
}
