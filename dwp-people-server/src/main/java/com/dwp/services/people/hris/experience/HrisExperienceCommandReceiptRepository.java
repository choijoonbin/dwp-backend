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
                       event.expected_owner_version AS origin_target_revision,
                       NULL::uuid AS approval_request_id,
                       NULL::bigint AS applied_target_version,
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
                SELECT request.public_id,exact.target_worker_id,
                       organization.public_id AS organization_public_id,
                       request.version AS source_revision,
                       request.expected_target_version AS origin_target_revision,
                       request.approval_case_public_id AS approval_request_id,
                       request.applied_target_version AS applied_target_version,
                       request.field_groups::text AS field_groups,
                       NULL::varchar AS event_type
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons person
                    ON person.tenant_id=request.tenant_id
                   AND person.person_id=request.target_person_id
                  JOIN LATERAL (
                       SELECT CASE WHEN count(DISTINCT candidate.worker_id)=1
                                      THEN max(candidate.worker_id) END
                                      AS target_worker_id,
                              CASE WHEN count(DISTINCT candidate.organization_id)=1
                                      THEN max(candidate.organization_id) END
                                      AS organization_id
                         FROM (
                              SELECT worker.worker_id,assignment.organization_id
                                FROM ppl_workers worker
                                LEFT JOIN ppl_work_relationships relationship
                                  ON relationship.tenant_id=worker.tenant_id
                                 AND relationship.worker_id=worker.worker_id
                                LEFT JOIN ppl_assignments assignment
                                  ON assignment.tenant_id=relationship.tenant_id
                                 AND assignment.work_relationship_id=
                                     relationship.work_relationship_id
                               WHERE request.target_aggregate_type='PERSON'
                                 AND request.target_aggregate_public_id=person.public_id
                                 AND worker.tenant_id=person.tenant_id
                                 AND worker.person_id=person.person_id
                              UNION ALL
                              SELECT worker.worker_id,assignment.organization_id
                                FROM ppl_workers worker
                                LEFT JOIN ppl_work_relationships relationship
                                  ON relationship.tenant_id=worker.tenant_id
                                 AND relationship.worker_id=worker.worker_id
                                LEFT JOIN ppl_assignments assignment
                                  ON assignment.tenant_id=relationship.tenant_id
                                 AND assignment.work_relationship_id=
                                     relationship.work_relationship_id
                               WHERE request.target_aggregate_type='WORKER'
                                 AND worker.tenant_id=person.tenant_id
                                 AND worker.person_id=person.person_id
                                 AND worker.public_id=request.target_aggregate_public_id
                              UNION ALL
                              SELECT worker.worker_id,assignment.organization_id
                                FROM ppl_work_relationships relationship
                                JOIN ppl_workers worker
                                  ON worker.tenant_id=relationship.tenant_id
                                 AND worker.worker_id=relationship.worker_id
                                LEFT JOIN ppl_assignments assignment
                                  ON assignment.tenant_id=relationship.tenant_id
                                 AND assignment.work_relationship_id=
                                     relationship.work_relationship_id
                               WHERE request.target_aggregate_type=
                                     'EMPLOYMENT_RELATIONSHIP'
                                 AND relationship.tenant_id=person.tenant_id
                                 AND worker.person_id=person.person_id
                                 AND relationship.public_id=
                                     request.target_aggregate_public_id
                              UNION ALL
                              SELECT worker.worker_id,assignment.organization_id
                                FROM ppl_assignments assignment
                                JOIN ppl_work_relationships relationship
                                  ON relationship.tenant_id=assignment.tenant_id
                                 AND relationship.work_relationship_id=
                                     assignment.work_relationship_id
                                JOIN ppl_workers worker
                                  ON worker.tenant_id=relationship.tenant_id
                                 AND worker.worker_id=relationship.worker_id
                               WHERE request.target_aggregate_type='ASSIGNMENT'
                                 AND assignment.tenant_id=person.tenant_id
                                 AND worker.person_id=person.person_id
                                 AND assignment.public_id=
                                     request.target_aggregate_public_id
                         ) candidate
                        HAVING count(*) > 0
                            OR (request.target_aggregate_type='PERSON'
                                AND request.target_aggregate_public_id=person.public_id)
                  ) exact ON true
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id=request.tenant_id
                   AND organization.organization_id=exact.organization_id
                 WHERE request.tenant_id=:tenant AND request.public_id=:target
                """, parameters(tenantId).addValue("target", requestPublicId), this::target)
                .stream().findFirst();
    }

    boolean exactAppliedOrigin(
            long tenantId,
            UUID eventId,
            UUID requestPublicId,
            UUID receiptId,
            UUID correlationId,
            long requestVersion,
            long appliedTargetVersion) {
        establishTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_people_outbox_events
                 WHERE tenant_id=:tenant
                   AND event_id=:event
                   AND aggregate_type='EMPLOYEE_CHANGE_REQUEST'
                   AND aggregate_id=:request
                   AND event_type='EmployeeChangeApplied.v1'
                   AND correlation_id=:correlation
                   AND payload->>'eventId'=:eventText
                   AND payload->>'aggregateId'=:request
                   AND payload->>'requestPublicId'=:request
                   AND payload->>'causationId'=:receipt
                   AND payload->>'correlationId'=:correlation
                   AND payload->>'aggregateVersion'=:requestVersion
                   AND payload->>'appliedTargetVersion'=:appliedTargetVersion
                   AND payload->>'tenantId'=:tenantText
                """, parameters(tenantId)
                .addValue("event", eventId)
                .addValue("eventText", eventId.toString())
                .addValue("request", requestPublicId.toString())
                .addValue("receipt", receiptId.toString())
                .addValue("correlation", correlationId.toString())
                .addValue("requestVersion", Long.toString(requestVersion))
                .addValue("appliedTargetVersion", Long.toString(appliedTargetVersion))
                .addValue("tenantText", Long.toString(tenantId)), Integer.class);
        return count != null && count == 1;
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
                    result.getLong("origin_target_revision"),
                    result.getObject("approval_request_id", UUID.class),
                    result.getObject("applied_target_version", Long.class),
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
            long originTargetRevision,
            UUID approvalRequestId,
            Long appliedTargetVersion,
            List<String> fieldGroups,
            String eventType) {
        TargetEvidence {
            fieldGroups = List.copyOf(fieldGroups);
        }
    }
}
