package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped persistence for the V54 employee-change lifecycle. */
@Repository
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    EmployeeChangeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void establishTenant(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException("Employee-change tenant scope was not established.");
        }
    }

    Optional<TargetRow> targetForMutation(
            long tenantId,
            UUID targetPersonPublicId,
            String targetAggregateType,
            UUID targetAggregatePublicId) {
        establishTenant(tenantId);
        String sql = switch (targetAggregateType) {
            case "PERSON" -> """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           person.public_id AS aggregate_public_id,
                           person.version AS aggregate_version,
                           person.display_name,
                           CURRENT_DATE AS effective_from,NULL::DATE AS effective_to
                      FROM ppl_persons person
                      JOIN LATERAL (
                           SELECT candidate.worker_id
                             FROM ppl_workers candidate
                            WHERE candidate.tenant_id=person.tenant_id
                              AND candidate.person_id=person.person_id
                            ORDER BY (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                     candidate.worker_id
                            LIMIT 1
                      ) worker ON TRUE
                     WHERE person.tenant_id=:tenant AND person.public_id=:person
                       AND person.public_id=:aggregate
                     FOR UPDATE OF person
                    """;
            case "WORKER" -> """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           worker.public_id AS aggregate_public_id,
                           worker.version AS aggregate_version,
                           person.display_name,
                           COALESCE(worker.original_hire_date,CURRENT_DATE) AS effective_from,
                           NULL::DATE AS effective_to
                      FROM ppl_workers worker
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE worker.tenant_id=:tenant AND person.public_id=:person
                       AND worker.public_id=:aggregate
                     FOR UPDATE OF worker
                    """;
            case "EMPLOYMENT_RELATIONSHIP" -> """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           relationship.public_id AS aggregate_public_id,
                           relationship.version AS aggregate_version,
                           person.display_name,relationship.start_date AS effective_from,
                           relationship.end_date AS effective_to
                      FROM ppl_work_relationships relationship
                      JOIN ppl_workers worker
                        ON worker.tenant_id=relationship.tenant_id
                       AND worker.worker_id=relationship.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE relationship.tenant_id=:tenant AND person.public_id=:person
                       AND relationship.public_id=:aggregate
                     FOR UPDATE OF relationship
                    """;
            case "ASSIGNMENT" -> """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           assignment.public_id AS aggregate_public_id,
                           assignment.version AS aggregate_version,
                           person.display_name,assignment.effective_start_date AS effective_from,
                           assignment.effective_end_date AS effective_to
                      FROM ppl_assignments assignment
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id=assignment.tenant_id
                       AND relationship.work_relationship_id=assignment.work_relationship_id
                      JOIN ppl_workers worker
                        ON worker.tenant_id=relationship.tenant_id
                       AND worker.worker_id=relationship.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE assignment.tenant_id=:tenant AND person.public_id=:person
                       AND assignment.public_id=:aggregate
                     FOR UPDATE OF assignment
                    """;
            default -> throw invalid("Unsupported employee-change target aggregate.");
        };
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("person", targetPersonPublicId)
                        .addValue("aggregate", targetAggregatePublicId),
                (result, ignored) -> new TargetRow(
                        result.getLong("person_id"),
                        result.getLong("worker_id"),
                        result.getObject("person_public_id", UUID.class),
                        targetAggregateType,
                        result.getObject("aggregate_public_id", UUID.class),
                        result.getLong("aggregate_version"),
                        result.getString("display_name"),
                        result.getObject("effective_from", LocalDate.class),
                        result.getObject("effective_to", LocalDate.class)))
                .stream().findFirst();
    }

    long requesterPersonId(long tenantId, UUID requesterPublicId) {
        establishTenant(tenantId);
        List<Long> rows = jdbc.query("""
                SELECT person_id FROM ppl_persons
                 WHERE tenant_id=:tenant AND public_id=:person
                 FOR SHARE
                """, new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("person", requesterPublicId),
                (result, ignored) -> result.getLong("person_id"));
        if (rows.size() != 1) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The current identity is not bound to a People person.");
        }
        return rows.getFirst();
    }

    RequestRow create(
            long tenantId,
            UUID requestPublicId,
            long requesterPersonId,
            UUID requesterPublicId,
            TargetRow target,
            EmployeeChangeCanonicalizer.CanonicalPatch patch,
            EmployeeChangePatchProtector.ProtectedPatch protectedPatch,
            LocalDate effectiveDate,
            String populationDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String contextDigest,
            UUID correlationId,
            long actorUserId,
            List<EmployeeChangeDtos.EvidenceReference> evidence,
            String purposeCode) {
        establishTenant(tenantId);
        String fieldGroups = json(patch.fieldGroups());
        List<RequestRow> rows = jdbc.query("""
                INSERT INTO ppl_employee_change_requests(
                    public_id,tenant_id,requester_person_id,target_person_id,
                    target_aggregate_type,target_aggregate_public_id,change_type,
                    effective_date,lifecycle_state,expected_target_version,
                    patch_schema_key,patch_schema_version,encrypted_patch,patch_hash,
                    key_reference,field_groups,population_scope_digest,
                    field_policy_revision,authorization_revision,
                    originating_context_digest,correlation_id,version,created_by,updated_by)
                VALUES(:publicId,:tenant,:requester,:targetPerson,:targetType,:targetAggregate,
                    :changeType,:effectiveDate,'DRAFT',:targetVersion,:schemaKey,1,
                    :encryptedPatch,:patchHash,:keyReference,CAST(:fieldGroups AS jsonb),
                    :populationDigest,:fieldRevision,:authorizationRevision,
                    :contextDigest,:correlation,1,:actor,:actor)
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("publicId", requestPublicId)
                .addValue("tenant", tenantId)
                .addValue("requester", requesterPersonId)
                .addValue("targetPerson", target.personId())
                .addValue("targetType", target.aggregateType())
                .addValue("targetAggregate", target.aggregatePublicId())
                .addValue("changeType", patch.changeType())
                .addValue("effectiveDate", effectiveDate)
                .addValue("targetVersion", target.aggregateVersion())
                .addValue("schemaKey", patch.schemaKey())
                .addValue("encryptedPatch", protectedPatch.bytes())
                .addValue("patchHash", patch.digest())
                .addValue("keyReference", protectedPatch.keyReference())
                .addValue("fieldGroups", fieldGroups)
                .addValue("populationDigest", populationDigest)
                .addValue("fieldRevision", fieldPolicyRevision)
                .addValue("authorizationRevision", authorizationRevision)
                .addValue("contextDigest", contextDigest)
                .addValue("correlation", correlationId)
                .addValue("actor", actorUserId), this::requestRow);
        if (rows.size() != 1) throw new IllegalStateException("Employee-change insert failed.");
        RequestRow row = rows.getFirst().withIdentity(
                requesterPublicId, target.personPublicId(), target.workerId(),
                target.displayName());
        int evidenceVersion = 1;
        for (EmployeeChangeDtos.EvidenceReference reference : evidence) {
            UUID objectPublicId = UUID.nameUUIDFromBytes(
                    reference.objectRef().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int inserted = jdbc.update("""
                    INSERT INTO ppl_employee_change_evidence(
                        tenant_id,employee_change_request_id,object_public_id,object_version,
                        content_sha256,classification,purpose_code,created_by)
                    VALUES(:tenant,:requestId,:objectId,:objectVersion,:digest,
                        'RESTRICTED',:purpose,:actor)
                    """, new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("requestId", row.requestId())
                    .addValue("objectId", objectPublicId)
                    .addValue("objectVersion", evidenceVersion++)
                    .addValue("digest", reference.digest())
                    .addValue("purpose", purposeCode)
                    .addValue("actor", actorUserId));
            if (inserted != 1) throw new IllegalStateException("Employee-change evidence insert failed.");
        }
        return row;
    }

    Optional<RequestRow> requestForMutation(long tenantId, UUID requestPublicId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT request.*,
                       requester.public_id AS requester_public_id,
                       target.public_id AS target_person_public_id,
                       worker.worker_id AS target_worker_id,
                       target.display_name AS target_display_name
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                  JOIN ppl_persons target
                    ON target.tenant_id=request.tenant_id
                   AND target.person_id=request.target_person_id
                  JOIN LATERAL (
                       SELECT candidate.worker_id
                         FROM ppl_workers candidate
                        WHERE candidate.tenant_id=target.tenant_id
                          AND candidate.person_id=target.person_id
                        ORDER BY (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                 WHERE request.tenant_id=:tenant AND request.public_id=:publicId
                 FOR UPDATE OF request
                """, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("publicId", requestPublicId),
                (result, ignored) -> requestRow(result, ignored).withIdentity(
                        result.getObject("requester_public_id", UUID.class),
                        result.getObject("target_person_public_id", UUID.class),
                        result.getLong("target_worker_id"),
                        result.getString("target_display_name")))
                .stream().findFirst();
    }

    RequestRow submit(
            long tenantId,
            RequestRow row,
            UUID approvalRequestId,
            String workflowKey,
            long workflowVersion,
            Instant now,
            long actorUserId) {
        List<RequestRow> rows = jdbc.query("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='SUBMITTED',approval_case_public_id=:approval,
                       approval_workflow_key=:workflowKey,
                       approval_workflow_version=:workflowVersion,
                       approval_subject_revision=version+1,submitted_at=:submitted,
                       version=version+1,updated_by=:actor,updated_at=:submitted
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='DRAFT' AND version=:version
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("approval", approvalRequestId)
                .addValue("workflowKey", workflowKey)
                .addValue("workflowVersion", workflowVersion)
                .addValue("submitted", Timestamp.from(now))
                .addValue("actor", actorUserId)
                .addValue("tenant", tenantId)
                .addValue("requestId", row.requestId())
                .addValue("version", row.version()), this::requestRow);
        if (rows.size() != 1) throw versionConflict("Employee-change submission changed.");
        return rows.getFirst().withIdentity(
                row.requesterPublicId(), row.targetPersonPublicId(),
                row.targetWorkerId(), row.targetDisplayName());
    }

    long applyTarget(
            long tenantId,
            RequestRow request,
            List<EmployeeChangeDtos.PatchOperation> operations,
            long actorUserId) {
        String table;
        String idColumn;
        Map<String, Column> allowed;
        switch (request.targetAggregateType()) {
            case "PERSON" -> {
                table = "ppl_persons";
                idColumn = "public_id";
                allowed = Map.of(
                        "/displayName", new Column("display_name", false, ValueKind.TEXT),
                        "/preferredLocale", new Column("preferred_locale", true, ValueKind.LOCALE),
                        "/timeZone", new Column("time_zone", true, ValueKind.TIME_ZONE));
            }
            case "WORKER" -> {
                table = "ppl_workers";
                idColumn = "public_id";
                allowed = Map.of(
                        "/workerType", new Column("worker_type", false, ValueKind.WORKER_TYPE),
                        "/workerStatus", new Column("worker_status", false, ValueKind.WORKER_STATUS),
                        "/originalHireDate", new Column("original_hire_date", true, ValueKind.DATE));
            }
            case "EMPLOYMENT_RELATIONSHIP" -> {
                table = "ppl_work_relationships";
                idColumn = "public_id";
                allowed = Map.of(
                        "/relationshipType", new Column(
                                "relationship_type", false, ValueKind.WORKER_TYPE),
                        "/primaryRelationship", new Column(
                                "primary_relationship", false, ValueKind.BOOLEAN),
                        "/startDate", new Column("start_date", false, ValueKind.DATE),
                        "/endDate", new Column("end_date", true, ValueKind.DATE),
                        "/projectedEndDate", new Column(
                                "projected_end_date", true, ValueKind.DATE));
            }
            case "ASSIGNMENT" -> {
                table = "ppl_assignments";
                idColumn = "public_id";
                allowed = Map.of(
                        "/assignmentStatus", new Column(
                                "assignment_status", false, ValueKind.ASSIGNMENT_STATUS),
                        "/businessTitle", new Column("business_title", true, ValueKind.TITLE),
                        "/costCenterKey", new Column("cost_center_key", true, ValueKind.KEY),
                        "/workerHours", new Column("worker_hours", true, ValueKind.HOURS),
                        "/fullTimeEquivalent", new Column(
                                "full_time_equivalent", true, ValueKind.FTE),
                        "/changeReasonCode", new Column(
                                "change_reason_code", true, ValueKind.CODE));
            }
            default -> throw invalid("Unsupported target aggregate type.");
        }
        List<String> assignments = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("aggregate", request.targetAggregatePublicId())
                .addValue("version", request.expectedTargetVersion())
                .addValue("actor", actorUserId);
        int index = 0;
        for (EmployeeChangeDtos.PatchOperation operation : operations) {
            Column column = allowed.get(operation.path());
            if (column == null) throw invalid("The frozen patch is outside its target schema.");
            Object value = value(operation, column);
            String parameter = "value" + index++;
            assignments.add(column.name() + "=:" + parameter);
            parameters.addValue(parameter, value);
        }
        assignments.add("version=version+1");
        assignments.add("updated_by=:actor");
        assignments.add("updated_at=clock_timestamp()");
        String sql = "UPDATE " + table + " SET " + String.join(",", assignments)
                + " WHERE tenant_id=:tenant AND " + idColumn
                + "=:aggregate AND version=:version RETURNING version";
        List<Long> versions = jdbc.query(sql, parameters,
                (result, ignored) -> result.getLong("version"));
        if (versions.size() != 1) {
            throw versionConflict("Employee-change target version is stale.");
        }
        return versions.getFirst();
    }

    RequestRow beginApply(
            long tenantId,
            RequestRow request,
            Instant now,
            long actorUserId) {
        List<RequestRow> rows = jdbc.query("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPLYING',version=version+1,
                       updated_by=:actor,updated_at=:now
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='APPROVED' AND version=:version
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("actor", actorUserId)
                .addValue("tenant", tenantId)
                .addValue("requestId", request.requestId())
                .addValue("version", request.version()), this::requestRow);
        if (rows.size() != 1) throw versionConflict("Employee-change apply state changed.");
        return rows.getFirst().withIdentity(
                request.requesterPublicId(), request.targetPersonPublicId(),
                request.targetWorkerId(), request.targetDisplayName());
    }

    RequestRow markApplied(
            long tenantId,
            RequestRow request,
            long targetVersion,
            Instant now,
            long actorUserId) {
        List<RequestRow> rows = jdbc.query("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPLIED',applied_target_version=:targetVersion,
                       applied_at=:applied,version=version+1,
                       updated_by=:actor,updated_at=:applied
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='APPLYING' AND version=:version
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("targetVersion", targetVersion)
                .addValue("applied", Timestamp.from(now))
                .addValue("actor", actorUserId)
                .addValue("tenant", tenantId)
                .addValue("requestId", request.requestId())
                .addValue("version", request.version()), this::requestRow);
        if (rows.size() != 1) throw versionConflict("Employee-change apply state changed.");
        return rows.getFirst().withIdentity(
                request.requesterPublicId(), request.targetPersonPublicId(),
                request.targetWorkerId(), request.targetDisplayName());
    }

    Optional<ReceiptRow> insertReceipt(
            long tenantId,
            UUID receiptId,
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
            UUID targetPublicId,
            Long expectedVersion,
            UUID correlationId,
            Instant now) {
        List<ReceiptRow> rows = jdbc.query("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,correlation_id,created_at,updated_at)
                VALUES(:receipt,:tenant,:commandType,:idempotency,:digest,:actor,:action,
                    :subject,:population,:fieldRevision,:purpose,:authorizationRevision,
                    :contextDigest,'EMPLOYEE_CHANGE_REQUEST',:target,:expectedVersion,
                    'RUNNING',:correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("receipt", receiptId)
                .addValue("tenant", tenantId)
                .addValue("commandType", commandType)
                .addValue("idempotency", idempotencyKey)
                .addValue("digest", requestDigest)
                .addValue("actor", actorPublicId)
                .addValue("action", action)
                .addValue("subject", subjectPublicId)
                .addValue("population", populationDigest)
                .addValue("fieldRevision", fieldRevision)
                .addValue("purpose", purpose)
                .addValue("authorizationRevision", authorizationRevision)
                .addValue("contextDigest", contextDigest)
                .addValue("target", targetPublicId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("correlation", correlationId)
                .addValue("now", Timestamp.from(now)), this::receiptRow);
        return rows.stream().findFirst();
    }

    Optional<ReceiptRow> receipt(
            long tenantId,
            UUID subjectPublicId,
            String action,
            String idempotencyKey,
            String commandType,
            UUID correlationId) {
        List<ReceiptRow> rows = jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant
                   AND ((subject_principal_public_id=:subject
                         AND originating_action=:action AND idempotency_key=:idempotency)
                        OR (command_type=:commandType AND correlation_id=:correlation))
                 ORDER BY CASE WHEN subject_principal_public_id=:subject
                                    AND originating_action=:action
                                    AND idempotency_key=:idempotency THEN 0 ELSE 1 END
                 LIMIT 2
                 FOR UPDATE
                """, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("subject", subjectPublicId)
                        .addValue("action", action)
                        .addValue("idempotency", idempotencyKey)
                        .addValue("commandType", commandType)
                        .addValue("correlation", correlationId), this::receiptRow);
        return rows.stream().findFirst();
    }

    Optional<ReceiptRow> receiptForReconciliation(
            long tenantId,
            UUID actorPublicId,
            String action,
            String idempotencyKey,
            String commandType,
            UUID targetPublicId,
            UUID correlationId) {
        establishTenant(tenantId);
        List<ReceiptRow> rows = jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant
                   AND ((actor_public_id=:actor AND originating_action=:action
                         AND idempotency_key=:idempotency
                         AND target_public_id=:target)
                        OR (command_type=:commandType AND correlation_id=:correlation))
                 ORDER BY CASE WHEN actor_public_id=:actor
                                    AND originating_action=:action
                                    AND idempotency_key=:idempotency
                                    AND target_public_id=:target THEN 0 ELSE 1 END
                 LIMIT 2
                 FOR UPDATE
                """, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("actor", actorPublicId)
                        .addValue("action", action)
                        .addValue("idempotency", idempotencyKey)
                        .addValue("commandType", commandType)
                        .addValue("target", targetPublicId)
                        .addValue("correlation", correlationId), this::receiptRow);
        if (rows.size() > 1) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The employee-change command identity is ambiguous.");
        }
        return rows.stream().findFirst();
    }

    ReceiptRow completeReceipt(
            long tenantId,
            UUID receiptId,
            String resultCode,
            Map<String, ?> resultReference,
            Instant now) {
        List<ReceiptRow> rows = jdbc.query("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code=:resultCode,
                       result_reference=CAST(:resultReference AS jsonb),
                       completed_at=:now,updated_at=:now
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state='RUNNING'
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("receipt", receiptId)
                .addValue("resultCode", resultCode)
                .addValue("resultReference", json(resultReference))
                .addValue("now", Timestamp.from(now)), this::receiptRow);
        if (rows.size() != 1) throw new IllegalStateException("Command receipt completion failed.");
        return rows.getFirst();
    }

    void appendAudit(
            long tenantId,
            long actorUserId,
            String action,
            UUID target,
            UUID correlationId,
            Map<String, ?> safeSnapshot,
            Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:event,:tenant,'USER',:actor,:action,'EMPLOYEE_CHANGE_REQUEST',
                    :target,'SUCCESS',:correlation,CAST(:snapshot AS jsonb),:occurredAt)
                """, new MapSqlParameterSource()
                .addValue("event", UUID.randomUUID())
                .addValue("tenant", tenantId)
                .addValue("actor", Long.toString(actorUserId))
                .addValue("action", action)
                .addValue("target", target.toString())
                .addValue("correlation", correlationId.toString())
                .addValue("snapshot", json(safeSnapshot))
                .addValue("occurredAt", Timestamp.from(now)));
        if (inserted != 1) throw new IllegalStateException("Employee-change audit insert failed.");
    }

    void appendEvent(
            long tenantId,
            UUID eventId,
            UUID aggregateId,
            String eventType,
            String payload,
            UUID correlationId,
            Instant occurredAt) {
        int inserted = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:event,:tenant,'EMPLOYEE_CHANGE_REQUEST',:aggregate,:eventType,
                    CAST(:payload AS jsonb),:correlation,:occurredAt)
                ON CONFLICT (event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("event", eventId)
                .addValue("tenant", tenantId)
                .addValue("aggregate", aggregateId.toString())
                .addValue("eventType", eventType)
                .addValue("payload", payload)
                .addValue("correlation", correlationId.toString())
                .addValue("occurredAt", Timestamp.from(occurredAt)));
        if (inserted != 1) throw new IllegalStateException("Employee-change event insert failed.");
    }

    List<WorkbenchRow> workbench(
            long tenantId,
            Instant asOf,
            HcmPopulationRepository.PopulationScope scope) {
        establishTenant(tenantId);
        Set<UUID> organizationIds = scope.organizationIds().isEmpty()
                ? Set.of(new UUID(0, 0)) : scope.organizationIds();
        return jdbc.query("""
                SELECT request.public_id,request.lifecycle_state,request.change_type,
                       request.effective_date,request.version,request.field_groups::text,
                       request.created_at,target.display_name,
                       requester.public_id AS requester_public_id,
                       worker.worker_id AS target_worker_id
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                  JOIN ppl_persons target
                    ON target.tenant_id=request.tenant_id
                   AND target.person_id=request.target_person_id
                  JOIN LATERAL (
                       SELECT candidate.worker_id,assignment.manager_assignment_key,
                              organization.public_id AS organization_public_id
                         FROM ppl_workers candidate
                         JOIN ppl_work_relationships relationship
                           ON relationship.tenant_id=candidate.tenant_id
                          AND relationship.worker_id=candidate.worker_id
                          AND relationship.start_date<=CURRENT_DATE
                          AND (relationship.end_date IS NULL
                               OR relationship.end_date>=CURRENT_DATE)
                         JOIN ppl_assignments assignment
                           ON assignment.tenant_id=relationship.tenant_id
                          AND assignment.work_relationship_id=relationship.work_relationship_id
                          AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                          AND assignment.effective_start_date<=CURRENT_DATE
                          AND (assignment.effective_end_date IS NULL
                               OR assignment.effective_end_date>=CURRENT_DATE)
                         LEFT JOIN ppl_organizations organization
                           ON organization.tenant_id=assignment.tenant_id
                          AND organization.organization_id=assignment.organization_id
                        WHERE candidate.tenant_id=target.tenant_id
                          AND candidate.person_id=target.person_id
                        ORDER BY assignment.primary_assignment DESC,
                                 assignment.effective_start_date DESC,
                                 (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                 WHERE request.tenant_id=:tenant AND request.created_at<=:asOf
                   AND worker.worker_id<>:actorWorker
                   AND (:tenantWide
                        OR worker.manager_assignment_key=:managerAssignment
                        OR worker.organization_public_id IN (:organizationIds))
                 ORDER BY request.public_id
                """, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("asOf", Timestamp.from(asOf))
                        .addValue("actorWorker", scope.actorWorkerId())
                        .addValue("tenantWide", scope.tenantWide())
                        .addValue("managerAssignment", scope.managerAssignmentKey())
                        .addValue("organizationIds", organizationIds),
                (result, ignored) -> new WorkbenchRow(
                        result.getObject("public_id", UUID.class),
                        result.getString("lifecycle_state"),
                        result.getString("change_type"),
                        result.getObject("effective_date", LocalDate.class),
                        result.getLong("version"),
                        stringList(result.getString("field_groups")),
                        result.getTimestamp("created_at").toInstant(),
                        result.getString("display_name"),
                        result.getObject("requester_public_id", UUID.class),
                        result.getLong("target_worker_id")));
    }

    private RequestRow requestRow(java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        return new RequestRow(
                result.getLong("employee_change_request_id"),
                result.getObject("public_id", UUID.class),
                result.getLong("requester_person_id"),
                null,
                result.getLong("target_person_id"),
                null,
                0,
                null,
                result.getString("target_aggregate_type"),
                result.getObject("target_aggregate_public_id", UUID.class),
                result.getString("change_type"),
                result.getObject("effective_date", LocalDate.class),
                result.getString("lifecycle_state"),
                result.getLong("expected_target_version"),
                result.getString("patch_schema_key"),
                result.getInt("patch_schema_version"),
                result.getBytes("encrypted_patch"),
                result.getString("patch_hash"),
                result.getString("key_reference"),
                stringList(result.getString("field_groups")),
                result.getObject("approval_case_public_id", UUID.class),
                result.getString("approval_workflow_key"),
                result.getObject("approval_workflow_version", Long.class),
                result.getObject("approval_subject_revision", Long.class),
                result.getObject("approval_policy_revision", Long.class),
                result.getObject("approval_decision_event_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                result.getObject("applied_target_version", Long.class),
                result.getObject("correlation_id", UUID.class),
                result.getLong("version"),
                result.getTimestamp("created_at").toInstant(),
                result.getLong("created_by"));
    }

    private ReceiptRow receiptRow(java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        return new ReceiptRow(
                result.getObject("command_receipt_id", UUID.class),
                result.getLong("tenant_id"),
                result.getString("command_type"),
                result.getString("idempotency_key"),
                result.getString("request_hash"),
                result.getObject("actor_public_id", UUID.class),
                result.getString("originating_action"),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"),
                result.getString("purpose_code"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                result.getObject("target_public_id", UUID.class),
                result.getObject("expected_version", Long.class),
                result.getString("lifecycle_state"),
                result.getString("result_code"),
                result.getString("result_reference"),
                result.getObject("correlation_id", UUID.class),
                result.getTimestamp("created_at").toInstant());
    }

    private Object value(EmployeeChangeDtos.PatchOperation operation, Column column) {
        if ("remove".equals(operation.op())) {
            if (!column.nullable()) throw invalid(operation.path() + " cannot be removed.");
            return null;
        }
        Object raw = operation.value();
        try {
            return switch (column.kind()) {
                case TEXT -> text(raw, 1, 200, null);
                case TITLE -> text(raw, 1, 240, null);
                case KEY -> text(raw, 1, 100, "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
                case CODE -> codeText(raw, 80);
                case LOCALE -> text(raw, 2, 35, "[A-Za-z0-9_-]{2,35}");
                case TIME_ZONE -> java.time.ZoneId.of(text(raw, 1, 80, null)).getId();
                case DATE -> LocalDate.parse(text(raw, 10, 10, "\\d{4}-\\d{2}-\\d{2}"));
                case BOOLEAN -> {
                    if (!(raw instanceof Boolean value)) throw new IllegalArgumentException();
                    yield value;
                }
                case HOURS -> decimal(raw, new BigDecimal("0.01"), new BigDecimal("168"));
                case FTE -> decimal(raw, BigDecimal.ZERO, BigDecimal.ONE);
                case WORKER_TYPE -> enumText(raw,
                        List.of("EMPLOYEE", "CONTINGENT", "NONWORKER", "PENDING"));
                case WORKER_STATUS -> enumText(raw,
                        List.of("ACTIVE", "LEAVE", "TERMINATED", "PENDING"));
                case ASSIGNMENT_STATUS -> enumText(raw,
                        List.of("ACTIVE", "SUSPENDED", "ENDED", "PENDING"));
            };
        } catch (RuntimeException exception) {
            throw invalid("The value for " + operation.path() + " is invalid.");
        }
    }

    private String text(Object raw, int minimum, int maximum, String pattern) {
        if (!(raw instanceof String value) || !value.equals(value.trim())
                || value.length() < minimum || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)
                || (pattern != null && !value.matches(pattern))) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private String enumText(Object raw, List<String> allowed) {
        String value = text(raw, 1, 40, "[A-Za-z][A-Za-z0-9_-]{0,39}")
                .toUpperCase(Locale.ROOT);
        if (!allowed.isEmpty() && !allowed.contains(value)) throw new IllegalArgumentException();
        return value;
    }

    private String codeText(Object raw, int maximum) {
        String value = text(raw, 1, maximum, "[A-Za-z][A-Za-z0-9._-]{0,79}");
        return value.toUpperCase(Locale.ROOT);
    }

    private BigDecimal decimal(Object raw, BigDecimal minimum, BigDecimal maximum) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            throw new IllegalArgumentException();
        }
        BigDecimal value = new BigDecimal(raw.toString());
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException();
        }
        return value.stripTrailingZeros();
    }

    private List<String> stringList(String json) {
        try {
            return List.copyOf(mapper.readValue(json, STRING_LIST));
        } catch (Exception exception) {
            throw new IllegalStateException("Employee-change field groups are invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Employee-change evidence serialization failed.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private record Column(String name, boolean nullable, ValueKind kind) {
    }

    private enum ValueKind {
        TEXT, TITLE, KEY, CODE, LOCALE, TIME_ZONE, DATE, BOOLEAN,
        HOURS, FTE, WORKER_TYPE, WORKER_STATUS, ASSIGNMENT_STATUS
    }

    record TargetRow(
            long personId,
            long workerId,
            UUID personPublicId,
            String aggregateType,
            UUID aggregatePublicId,
            long aggregateVersion,
            String displayName,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    record RequestRow(
            long requestId,
            UUID publicId,
            long requesterPersonId,
            UUID requesterPublicId,
            long targetPersonId,
            UUID targetPersonPublicId,
            long targetWorkerId,
            String targetDisplayName,
            String targetAggregateType,
            UUID targetAggregatePublicId,
            String changeType,
            LocalDate effectiveDate,
            String lifecycleState,
            long expectedTargetVersion,
            String patchSchemaKey,
            int patchSchemaVersion,
            byte[] encryptedPatch,
            String patchHash,
            String keyReference,
            List<String> fieldGroups,
            UUID approvalCasePublicId,
            String approvalWorkflowKey,
            Long approvalWorkflowVersion,
            Long approvalSubjectRevision,
            Long approvalPolicyRevision,
            UUID approvalDecisionEventId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String originatingContextDigest,
            Long appliedTargetVersion,
            UUID correlationId,
            long version,
            Instant createdAt,
            long createdBy) {
        RequestRow {
            encryptedPatch = encryptedPatch.clone();
            fieldGroups = List.copyOf(fieldGroups);
        }

        @Override
        public byte[] encryptedPatch() {
            return encryptedPatch.clone();
        }

        RequestRow withTarget(TargetRow target) {
            return withIdentity(
                    requesterPublicId, target.personPublicId(), target.workerId(),
                    target.displayName());
        }

        RequestRow withIdentity(
                UUID requester,
                UUID targetPerson,
                long workerId,
                String displayName) {
            return new RequestRow(
                    requestId, publicId, requesterPersonId, requester,
                    targetPersonId, targetPerson, workerId, displayName,
                    targetAggregateType, targetAggregatePublicId, changeType,
                    effectiveDate, lifecycleState, expectedTargetVersion,
                    patchSchemaKey, patchSchemaVersion, encryptedPatch, patchHash,
                    keyReference, fieldGroups, approvalCasePublicId,
                    approvalWorkflowKey, approvalWorkflowVersion,
                    approvalSubjectRevision, approvalPolicyRevision,
                    approvalDecisionEventId, populationScopeDigest,
                    fieldPolicyRevision, authorizationRevision,
                    originatingContextDigest, appliedTargetVersion,
                    correlationId, version, createdAt, createdBy);
        }
    }

    record ReceiptRow(
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
            UUID targetPublicId,
            Long expectedVersion,
            String lifecycleState,
            String resultCode,
            String resultReference,
            UUID correlationId,
            Instant createdAt) {
    }

    record WorkbenchRow(
            UUID publicId,
            String lifecycleState,
            String changeType,
            LocalDate effectiveDate,
            long version,
            List<String> fieldGroups,
            Instant createdAt,
            String targetDisplayName,
            UUID requesterPublicId,
            long targetWorkerId) {
        WorkbenchRow {
            fieldGroups = List.copyOf(fieldGroups);
        }
    }
}
