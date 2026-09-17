package com.dwp.services.approval.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ApprovalOperationsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApprovalOperationsRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    ApprovalOperationsDtos.OperationReceipt begin(
            ApprovalOperationsAuthority.Current current,
            ApprovalOperationsProtocol.Route route,
            UUID requestedOperationId,
            String idempotencyKey,
            String fingerprint) {
        String lockKey = current.actor().tenantId() + "|"
                + current.scope().resourceSetKey() + "|"
                + current.actor().userId() + "|" + idempotencyKey;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource("lockKey", lockKey), result -> null);
        MapSqlParameterSource params = base(current)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("operationId", requestedOperationId, Types.OTHER);
        List<StoredOperation> stored = jdbc.query("""
                SELECT operation_id, tenant_id, management_resource_set_key,
                       actor_user_id, route_contract_key, operation_type,
                       command_mode, idempotency_key, request_fingerprint,
                       result_receipt::text
                  FROM apr_operation_batches
                 WHERE (tenant_id = :tenantId
                        AND management_resource_set_key = :managementScope
                        AND actor_user_id = :actorUserId
                        AND idempotency_key = :idempotencyKey)
                    OR (CAST(:operationId AS uuid) IS NOT NULL
                        AND operation_id = CAST(:operationId AS uuid))
                 FOR SHARE
                """, params, (result, row) -> new StoredOperation(
                result.getObject("operation_id", UUID.class),
                result.getLong("tenant_id"),
                result.getString("management_resource_set_key"),
                result.getLong("actor_user_id"),
                result.getString("route_contract_key"),
                result.getString("operation_type"),
                result.getString("command_mode"),
                result.getString("idempotency_key"),
                result.getString("request_fingerprint"),
                result.getString("result_receipt")));
        if (stored.isEmpty()) return null;
        if (stored.size() != 1) throw idempotencyConflict();
        StoredOperation value = stored.getFirst();
        if (value.tenantId() != current.actor().tenantId()
                || value.actorUserId() != current.actor().userId()
                || !value.managementScope().equals(current.scope().resourceSetKey())
                || !value.routeContractKey().equals(route.contractKey())
                || !value.operationType().equals(route.operation().name())
                || !value.commandMode().equals(route.mode())
                || !value.idempotencyKey().equals(idempotencyKey)
                || !value.requestFingerprint().equals(fingerprint)
                || (requestedOperationId != null
                && !requestedOperationId.equals(value.operationId()))) {
            throw idempotencyConflict();
        }
        return receipt(value.resultReceipt());
    }

    List<DeliveryRow> deliveries(
            ApprovalOperationsAuthority.Current current,
            List<UUID> targetIds,
            boolean lock) {
        String sql = ApprovalOperationsSql.DELIVERY_SELECT
                + (lock ? " FOR UPDATE OF delivery" : "");
        List<DeliveryRow> rows = jdbc.query(sql, targets(current, targetIds),
                (result, row) -> new DeliveryRow(
                        result.getObject("outbox_id", UUID.class),
                        result.getObject("event_id", UUID.class),
                        result.getObject("request_id", UUID.class),
                        result.getString("status"),
                        result.getLong("version"),
                        (Long) result.getObject("event_originator_user_id"),
                        (Long) result.getObject("assigned_auditor_user_id"),
                        result.getString("management_resource_set_key"),
                        result.getString("recovery_auditor_assignment_state"),
                        result.getString("recovery_auditor_resource_set_key"),
                        result.getString("recovery_auditor_assignment_revision"),
                        instant(result.getTimestamp("recovery_auditor_assigned_at")),
                        instant(result.getTimestamp("locked_until")),
                        result.getString("request_status")));
        requireCardinality(rows.size(), targetIds.size());
        return rows;
    }

    List<TaskRow> tasks(
            ApprovalOperationsAuthority.Current current,
            List<UUID> targetIds,
            boolean lock) {
        String sql = ApprovalOperationsSql.TASK_SELECT
                + (lock ? " FOR UPDATE OF request, step, task" : "");
        List<TaskRow> rows = jdbc.query(sql, targets(current, targetIds),
                (result, row) -> new TaskRow(
                        result.getObject("task_id", UUID.class),
                        result.getObject("request_id", UUID.class),
                        result.getObject("step_id", UUID.class),
                        result.getString("status"),
                        result.getLong("version"),
                        (Long) result.getObject("assignee_user_id"),
                        result.getObject("assignee_person_public_id", UUID.class),
                        result.getString("candidate_role"),
                        (Long) result.getObject("delegated_from_user_id"),
                        result.getString("delegated_authority_role_code"),
                        result.getLong("requester_user_id"),
                        result.getString("request_status"),
                        result.getString("management_resource_set_key"),
                        result.getString("step_status"),
                        result.getString("step_candidate_role")));
        requireCardinality(rows.size(), targetIds.size());
        return rows;
    }

    void lockLiveRequests(
            ApprovalOperationsAuthority.Current current,
            List<UUID> requestIds) {
        new ApprovalRetentionLiveGuard(jdbc).writeRequests(
                current.actor().tenantId(), requestIds);
    }

    void updateDeliveries(
            ApprovalOperationsAuthority.Current current,
            ApprovalOperationsProtocol.Operation operation,
            List<DeliveryCommit> commits) {
        boolean manualRecovery = operation == ApprovalOperationsProtocol.Operation.DELIVERY_RETRY
                || operation == ApprovalOperationsProtocol.Operation.DELIVERY_REPLAY;
        SqlParameterSource[] batch = commits.stream().map(commit -> base(current)
                .addValue("targetId", commit.row().targetId())
                .addValue("expectedVersion", commit.row().version())
                .addValue("statusBefore", commit.row().status())
                .addValue("statusAfter", commit.statusAfter())
                .addValue("resetAttempts", manualRecovery)
                .addValue("availableNow", manualRecovery)
                .addValue("manualRecovery", manualRecovery)
                .addValue("expiredLease", operation
                        == ApprovalOperationsProtocol.Operation.DELIVERY_RECONCILE
                        && "SENDING".equals(commit.row().status())))
                .toArray(SqlParameterSource[]::new);
        requireBatchUpdated(jdbc.batchUpdate(ApprovalOperationsSql.DELIVERY_UPDATE, batch));
    }

    void updateTasks(
            ApprovalOperationsAuthority.Current current,
            List<TaskCommit> commits) {
        SqlParameterSource[] batch = commits.stream().map(commit -> base(current)
                .addValue("targetId", commit.row().targetId())
                .addValue("requestId", commit.row().requestId())
                .addValue("stepId", commit.row().stepId())
                .addValue("expectedVersion", commit.row().version())
                .addValue("statusBefore", commit.row().status())
                .addValue("requestStatus", commit.row().requestStatus())
                .addValue("stepStatus", commit.row().stepStatus())
                .addValue("assigneeUserId", commit.observation().authorityUserId())
                .addValue("assigneePersonPublicId", commit.observation().authorityPersonPublicId())
                .addValue("candidateRole", commit.row().stepCandidateRole()))
                .toArray(SqlParameterSource[]::new);
        requireBatchUpdated(jdbc.batchUpdate(ApprovalOperationsSql.TASK_UPDATE, batch));
    }

    void save(
            ApprovalOperationsAuthority.Current current,
            ApprovalOperationsProtocol.Route route,
            String idempotencyKey,
            String reason,
            String requestFingerprint,
            ApprovalOperationsDtos.OperationReceipt receipt,
            UUID auditEventId,
            List<ItemCommit> items) {
        Instant observedAt = items.stream().map(ItemCommit::brokerObservedAt)
                .max(Instant::compareTo).orElse(receipt.committedAt());
        MapSqlParameterSource batch = base(current)
                .addValue("operationId", receipt.operationId())
                .addValue("actorPersonPublicId", current.actor().personPublicId())
                .addValue("routeContractKey", route.contractKey())
                .addValue("operationType", route.operation().name())
                .addValue("commandMode", route.mode())
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("requestFingerprint", requestFingerprint)
                .addValue("decisionRevision", current.decision().revision())
                .addValue("reason", reason)
                .addValue("itemCount", items.size())
                .addValue("resultReceipt", json(receipt))
                .addValue("auditEventId", auditEventId)
                .addValue("brokerObservedAt", Timestamp.from(observedAt))
                .addValue("committedAt", Timestamp.from(receipt.committedAt()));
        try {
            jdbc.update(ApprovalOperationsSql.OPERATION_INSERT, batch);
            List<SqlParameterSource> itemParams = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                ItemCommit item = items.get(index);
                itemParams.add(base(current)
                        .addValue("operationId", receipt.operationId())
                        .addValue("sequence", index + 1)
                        .addValue("targetType", item.targetType())
                        .addValue("targetId", item.targetId())
                        .addValue("requestId", item.requestId())
                        .addValue("expectedVersion", item.expectedVersion())
                        .addValue("committedVersion", item.expectedVersion() + 1)
                        .addValue("statusBefore", item.statusBefore())
                        .addValue("statusAfter", item.statusAfter())
                        .addValue("authorityUserId", item.authorityUserId())
                        .addValue("authorityPersonPublicId", item.authorityPersonPublicId())
                        .addValue("authorityRole", item.authorityRole())
                        .addValue("brokerRevision", item.brokerRevision())
                        .addValue("brokerObservedAt", Timestamp.from(item.brokerObservedAt()))
                        .addValue("targetFingerprint", item.targetFingerprint())
                        .addValue("committedAt", Timestamp.from(receipt.committedAt())));
            }
            requireBatchUpdated(jdbc.batchUpdate(
                    ApprovalOperationsSql.ITEM_INSERT,
                    itemParams.toArray(SqlParameterSource[]::new)));
        } catch (DuplicateKeyException exception) {
            throw idempotencyConflict();
        }
    }

    void recordTaskEvents(
            ApprovalOperationsAuthority.Current current,
            String idempotencyKey,
            String reason,
            Instant committedAt,
            List<TaskCommit> commits) {
        SqlParameterSource[] batch = commits.stream().map(commit -> base(current)
                .addValue("eventId", UUID.randomUUID())
                .addValue("requestId", commit.row().requestId())
                .addValue("actorId", current.actor().userId().toString())
                .addValue("message", reason)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("eventData", json(new TaskEvent(
                        commit.row().targetId(), commit.row().assigneeUserId(),
                        commit.observation().authorityUserId(),
                        commit.row().version(), commit.row().version() + 1)))
                .addValue("committedAt", Timestamp.from(committedAt)))
                .toArray(SqlParameterSource[]::new);
        requireBatchUpdated(jdbc.batchUpdate(ApprovalOperationsSql.TASK_EVENT_INSERT, batch));
    }

    private MapSqlParameterSource base(ApprovalOperationsAuthority.Current current) {
        return new MapSqlParameterSource()
                .addValue("tenantId", current.actor().tenantId())
                .addValue("managementScope", current.scope().resourceSetKey())
                .addValue("actorUserId", current.actor().userId());
    }

    private MapSqlParameterSource targets(
            ApprovalOperationsAuthority.Current current,
            List<UUID> targetIds) {
        return base(current).addValue("targetIds", targetIds);
    }

    private void requireCardinality(int actual, int expected) {
        if (actual != expected) {
            throw ApprovalOperationsProtocol.forbidden(
                    "One or more operation targets are outside the current tenant or management scope.");
        }
    }

    private void requireBatchUpdated(int[] counts) {
        for (int count : counts) {
            if (count != 1) throw ApprovalOperationsProtocol.conflict(
                    "An operation target changed while the command was committing.");
        }
    }

    private ApprovalOperationsDtos.OperationReceipt receipt(String json) {
        try {
            return objectMapper.readValue(json, ApprovalOperationsDtos.OperationReceipt.class);
        } catch (JsonProcessingException exception) {
            throw ApprovalOperationsProtocol.unavailable(
                    "The durable Approval operation receipt is invalid.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Approval operation evidence could not be serialized.", exception);
        }
    }

    private Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private BaseException idempotencyConflict() {
        return new BaseException(
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                "The operation idempotency key is bound to different command material.");
    }

    record DeliveryRow(
            UUID targetId,
            UUID eventId,
            UUID requestId,
            String status,
            long version,
            Long originatorUserId,
            Long auditorUserId,
            String managementScope,
            String assignmentState,
            String recoveryScope,
            String assignmentRevision,
            Instant assignedAt,
            Instant lockedUntil,
            String requestStatus) implements ApprovalOperationsAuthority.DeliverySnapshot {
    }

    record TaskRow(
            UUID targetId,
            UUID requestId,
            UUID stepId,
            String status,
            long version,
            Long assigneeUserId,
            UUID assigneePersonPublicId,
            String candidateRole,
            Long delegatedFromUserId,
            String delegatedAuthorityRole,
            long requesterUserId,
            String requestStatus,
            String managementScope,
            String stepStatus,
            String stepCandidateRole) implements ApprovalOperationsAuthority.TaskSnapshot {
    }

    record DeliveryCommit(
            DeliveryRow row,
            ApprovalOperationsAuthority.DeliveryObservation observation,
            String statusAfter,
            String targetFingerprint) {
    }

    record TaskCommit(
            TaskRow row,
            ApprovalOperationsAuthority.TaskObservation observation,
            String targetFingerprint) {
    }

    record ItemCommit(
            String targetType,
            UUID targetId,
            UUID requestId,
            long expectedVersion,
            String statusBefore,
            String statusAfter,
            long authorityUserId,
            UUID authorityPersonPublicId,
            String authorityRole,
            String brokerRevision,
            Instant brokerObservedAt,
            String targetFingerprint) {
    }

    private record StoredOperation(
            UUID operationId,
            long tenantId,
            String managementScope,
            long actorUserId,
            String routeContractKey,
            String operationType,
            String commandMode,
            String idempotencyKey,
            String requestFingerprint,
            String resultReceipt) {
    }

    private record TaskEvent(
            UUID taskId,
            Long previousAssigneeUserId,
            long assigneeUserId,
            long previousVersion,
            long committedVersion) {
    }
}
