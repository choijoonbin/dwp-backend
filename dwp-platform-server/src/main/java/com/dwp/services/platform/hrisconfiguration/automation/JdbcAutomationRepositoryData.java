package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;

/** Persistence row shapes and pure conversions shared by the JDBC stores. */
final class JdbcAutomationRepositoryData {

    static final String EVENT_TYPE =
            "dwp.hris.automation.run.state-changed.v1";
    static final String EVENT_SOURCE =
            "/dwp-platform-server/hris/automation";

    static boolean complete(Map<String, Object> row, String... fields) {
        return Arrays.stream(fields).allMatch(field -> row.get(field) != null);
    }

    static boolean revision(Object candidate, long expected) {
        return candidate instanceof Number number && number.longValue() == expected;
    }

    static AutomationInvocationV1 invocation(RuntimeRun run) {
        AutomationHandlerManifestV1 manifest = run.manifest().manifest();
        return new AutomationInvocationV1(
                run.run().publicId(), run.run().definitionPublicId(),
                run.run().versionNumber(), manifest.handlerKey(),
                manifest.contractVersion(), run.scopeType(), run.scopeId(),
                run.requestedAt().toInstant(), run.requestedByActorRef(),
                run.idempotencyKey(), run.inputReference(), run.inputDigest(),
                run.expectedItemCount(), run.correlationId());
    }

    static void requireWorkerFence(
            RuntimeRun run, LeaseGrant worker, OffsetDateTime now) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                run.workerLease(), run.reconcilerLease(), worker.token(),
                worker.authorityFenceEpoch(), worker.controlFenceEpoch(), now,
                false, "ORIGINAL");
    }

    static void requireReconcilerFence(
            RuntimeRun run, LeaseGrant reconciler, OffsetDateTime now) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.RECONCILER,
                run.workerLease(), run.reconcilerLease(), reconciler.token(),
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(), now,
                false, "COMPENSATION");
    }

    static String selectionDigest(List<UUID> itemIds) {
        String material = itemIds.stream().map(UUID::toString).sorted()
                .collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
        return AutomationDomainPolicy.sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    static String mapReceiptState(AutomationRunItemReceiptV1 receipt) {
        if ("FAILED".equals(receipt.state())) {
            return receipt.retryable() ? "FAILED_RETRYABLE" : "FAILED_FINAL";
        }
        return switch (receipt.state()) {
            case "CANCELED" -> "CANCELLED";
            case "DEAD_LETTERED" -> "DEAD_LETTER";
            default -> receipt.state();
        };
    }

    static String canonicalReceiptState(String receiptState) {
        return switch (receiptState) {
            case "FAILED_RETRYABLE", "FAILED_FINAL" -> "FAILED";
            case "CANCELLED" -> "CANCELED";
            case "DEAD_LETTER" -> "DEAD_LETTERED";
            default -> receiptState;
        };
    }

    static final RowMapper<DefinitionRecord> DEFINITION_MAPPER =
            (rs, row) -> new DefinitionRecord(
                    uuid(rs, "definition_id"), uuid(rs, "public_id"),
                    rs.getLong("tenant_id"), rs.getString("automation_key"),
                    OwnerDomain.valueOf(rs.getString("owner_domain")),
                    rs.getString("lifecycle_state"),
                    nullableLong(rs, "active_version_number"),
                    rs.getLong("object_revision"), uuid(rs, "author_actor_ref"));

    static final RowMapper<ScheduleRecord> SCHEDULE_MAPPER =
            (rs, row) -> new ScheduleRecord(
                    uuid(rs, "schedule_id"), uuid(rs, "public_id"),
                    rs.getLong("tenant_id"), uuid(rs, "definition_id"),
                    uuid(rs, "definition_public_id"), uuid(rs, "version_id"),
                    rs.getLong("pinned_version_number"),
                    ScheduleState.valueOf(rs.getString("lifecycle_state")),
                    rs.getString("schedule_expression"), rs.getString("timezone"),
                    rs.getString("timezone_database_revision"),
                    rs.getString("schedule_policy_digest"),
                    rs.getLong("subject_revision"), rs.getLong("object_revision"),
                    rs.getLong("fire_cursor_revision"),
                    rs.getObject("next_fire_at", OffsetDateTime.class),
                    uuid(rs, "author_actor_ref"), uuidNullable(rs, "approved_by_actor_ref"));

    static final RowMapper<RunRecord> RUN_MAPPER =
            (rs, row) -> new RunRecord(
                    uuid(rs, "run_id"), uuid(rs, "public_id"),
                    rs.getLong("tenant_id"), uuid(rs, "definition_id"),
                    uuid(rs, "definition_public_id"), uuid(rs, "version_id"),
                    rs.getLong("version_number"),
                    RunState.valueOf(rs.getString("lifecycle_state")),
                    SelectionMode.valueOf(rs.getString("selection_mode")),
                    rs.getString("concurrency_key_digest"),
                    rs.getInt("current_attempt"), rs.getInt("max_attempts"),
                    RetryMode.valueOf(rs.getString("retry_mode")),
                    rs.getLong("item_total"), rs.getLong("item_succeeded"),
                    rs.getLong("item_failed"), rs.getString("output_digest"),
                    rs.getLong("object_revision"),
                    rs.getString("reconciliation_required_reason"));

    static List<String> textArray(ResultSet rs, String name)
            throws SQLException {
        Array array = rs.getArray(name);
        if (array == null) return List.of();
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    static UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    static UUID uuidNullable(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name) == null ? null : rs.getObject(name, UUID.class);
    }

    static Long nullableLong(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name) == null ? null : rs.getLong(name);
    }

    static String emptySelectionDigest(UUID runPublicId) {
        return AutomationDomainPolicy.sha256(("{\"items\":[],\"runPublicId\":\""
                + runPublicId + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    record StoredReceipt(String requestDigest, CommandReceipt receipt) {
    }

    record CatalogRow(String sortTuple, CatalogItem item) {
    }

    record RunActionEligibility(
            long retryEligibleItemCount,
            CancelEligibility cancelEligibility) {
    }

    record RetryItem(
            UUID runItemId,
            UUID publicId,
            String state,
            String resultDigest,
            String errorCode,
            UUID priorAttemptId,
            String effectState,
            String effectReceiptDigest,
            UUID correlationId) {
    }

    record ReceiptDigestItem(
            UUID itemPublicId,
            long itemSequence,
            String receiptState,
            int attemptNumber,
            String resultDigest,
            String errorCode,
            String compensationReceiptDigest) {
    }

    record ReceiptSet(String digest, long itemCount, long terminalCount) {
    }

    record RunEventPayload(
            UUID runPublicId,
            RunState state,
            long itemTotal,
            long itemSucceeded,
            long itemFailed,
            String outputDigest) {
    }

    record AuditPayload(
            String slice,
            String transition,
            UUID runPublicId,
            long runRevision,
            RunState state,
            UUID correlationId,
            String eventPayloadDigest) {
    }
}
