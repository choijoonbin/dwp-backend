package com.dwp.services.people.hris.employeeservice.employeechange;

import static com.dwp.services.people.hris.employeeservice.employeechange.EmployeeChangePatchValues.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
            case "PERSON" -> EmployeeChangeSql.TARGET_FOR_MUTATION_1;
            case "WORKER" -> EmployeeChangeSql.TARGET_FOR_MUTATION_2;
            case "EMPLOYMENT_RELATIONSHIP" -> EmployeeChangeSql.TARGET_FOR_MUTATION_3;
            case "ASSIGNMENT" -> EmployeeChangeSql.TARGET_FOR_MUTATION_4;
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
        List<Long> rows = jdbc.query(EmployeeChangeSql.REQUESTER_PERSON_ID_5, new MapSqlParameterSource()
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
        List<RequestRow> rows = jdbc.query(EmployeeChangeSql.CREATE_6, new MapSqlParameterSource()
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
            int inserted = jdbc.update(EmployeeChangeSql.CREATE_7, new MapSqlParameterSource()
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
        return jdbc.query(EmployeeChangeSql.REQUEST_FOR_MUTATION_8, new MapSqlParameterSource()
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
        List<RequestRow> rows = jdbc.query(EmployeeChangeSql.SUBMIT_9, new MapSqlParameterSource()
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
        List<RequestRow> rows = jdbc.query(EmployeeChangeSql.BEGIN_APPLY_10, new MapSqlParameterSource()
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
        List<RequestRow> rows = jdbc.query(EmployeeChangeSql.MARK_APPLIED_11, new MapSqlParameterSource()
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
        List<ReceiptRow> rows = jdbc.query(EmployeeChangeSql.INSERT_RECEIPT_12, new MapSqlParameterSource()
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
        List<ReceiptRow> rows = jdbc.query(EmployeeChangeSql.RECEIPT_13, new MapSqlParameterSource()
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
        List<ReceiptRow> rows = jdbc.query(EmployeeChangeSql.RECEIPT_FOR_RECONCILIATION_14, new MapSqlParameterSource()
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
        List<ReceiptRow> rows = jdbc.query(EmployeeChangeSql.COMPLETE_RECEIPT_15, new MapSqlParameterSource()
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
        int inserted = jdbc.update(EmployeeChangeSql.APPEND_AUDIT_16, new MapSqlParameterSource()
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
        int inserted = jdbc.update(EmployeeChangeSql.APPEND_EVENT_17, new MapSqlParameterSource()
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
        return jdbc.query(EmployeeChangeSql.WORKBENCH_18, new MapSqlParameterSource()
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

    private BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
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
