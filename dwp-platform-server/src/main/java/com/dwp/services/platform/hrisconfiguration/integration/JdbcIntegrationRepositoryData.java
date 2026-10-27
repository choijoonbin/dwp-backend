package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Persistence row shapes and pure conversions shared by the JDBC stores. */
final class JdbcIntegrationRepositoryData {

    static final UUID COMPENSATION_CLAIM_PRINCIPAL = UUID.fromString(
            "00000000-0000-4000-8000-000000000009");

    static void requireAttemptBinding(
            AttemptRecord attempt,
            IntegrationRuntimePorts.ProviderItemEvidence evidence) {
        if (evidence == null
                || !java.util.Objects.equals(
                        attempt.providerIdempotencyKey(),
                        evidence.providerIdempotencyKey())
                || !java.util.Objects.equals(
                        attempt.requestDigest(), evidence.requestDigest())) {
            throw conflict("SYS008_PROVIDER_ITEM_ATTEMPT_BINDING_CHANGED");
        }
    }

    static String requiredFailureClass(
            IntegrationRuntimePorts.ProviderItemEvidence evidence,
            String fallback) {
        String value = evidence.failureClass() == null
                ? fallback : evidence.failureClass();
        if (!value.matches("^[A-Z][A-Z0-9_]{2,79}$")) {
            throw conflict("SYS008_PROVIDER_FAILURE_CLASS_INVALID");
        }
        return value;
    }

    static boolean providerDispatchOccurred(
            ResultSet rs,
            ExecutionState state) throws SQLException {
        boolean unresolved = rs.getBoolean("unresolved_external_effect");
        long acceptedCount = rs.getLong("accepted_count");
        if (state == ExecutionState.FAILED
                && rs.getBoolean("provider_failure_conclusive")
                && !unresolved
                && acceptedCount == 0
                && rs.getString("reconciliation_digest") == null) {
            return false;
        }
        boolean effectState = Set.of(
                ExecutionState.DISPATCHED,
                ExecutionState.RESULT_UNKNOWN,
                ExecutionState.ACKNOWLEDGED,
                ExecutionState.RECONCILING,
                ExecutionState.RECONCILED,
                ExecutionState.PARTIAL).contains(state);
        return effectState
                || unresolved
                || acceptedCount > 0
                || rs.getString("provider_receipt_ref") != null
                || rs.getString("provider_evidence_digest") != null;
    }

    static CommandReceipt receipt(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            OffsetDateTime createdAt,
            Long aggregateRevision,
            String resultRef) {
        return new CommandReceipt(receiptId, command.commandType(),
                command.originatingAction(), command.subjectPrincipalPublicId(),
                command.populationScopeDigest(), command.fieldPolicyRevision(),
                command.purposeCode(), command.authorizationRevision(), status,
                command.correlationId(), command.requestDigest(), createdAt,
                aggregateRevision, resultRef);
    }

    static String receiptError(ReceiptStatus status) {
        return switch (status) {
            case SUCCEEDED -> null;
            case REJECTED -> "COMMAND_REJECTED";
            case FAILED -> "COMMAND_FAILED";
            case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
            default -> throw new IllegalArgumentException(
                    "receipt status is not terminal");
        };
    }

    static void appendOwnerFilters(
            StringBuilder sql,
            List<Object> arguments,
            Map<String, String> filters) {
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String value = filter.getValue();
            switch (filter.getKey()) {
                case "connectorMetadata" -> {
                    sql.append(" AND (connector_key || ' ' || connector_type) ILIKE ?");
                    arguments.add("%" + value + "%");
                }
                case "policyVersion" -> {
                    long version;
                    try {
                        version = Long.parseLong(value);
                    } catch (NumberFormatException invalid) {
                        throw conflict("SYS008_OWNER_FILTER_INVALID");
                    }
                    if (version < 1) {
                        throw conflict("SYS008_OWNER_FILTER_INVALID");
                    }
                    sql.append(" AND definition_version = ?");
                    arguments.add(version);
                }
                case "executionPolicy" -> {
                    sql.append(" AND (COALESCE(direction, '') || ' ' || "
                            + "COALESCE(compatibility_mode, '')) ILIKE ?");
                    arguments.add("%" + value + "%");
                }
                case "effectiveStatus" -> {
                    sql.append(" AND (definition_state = ? OR mapping_state = ?)");
                    arguments.add(value.toUpperCase(java.util.Locale.ROOT));
                    arguments.add(value.toUpperCase(java.util.Locale.ROOT));
                }
                case "effectiveOn" -> {
                    try {
                        arguments.add(java.time.LocalDate.parse(value));
                    } catch (java.time.format.DateTimeParseException invalid) {
                        throw conflict("SYS008_OWNER_FILTER_INVALID");
                    }
                    sql.append(" AND effective_from::date = ?");
                }
                default -> throw conflict("SYS008_OWNER_FILTER_INVALID");
            }
        }
    }

    static Set<String> itemActions(
            OwnerRow row, List<String> allowed) {
        Set<String> candidates = new java.util.LinkedHashSet<>();
        switch (row.definitionState()) {
            case "DRAFT" -> candidates.add(IntegrationService.VALIDATE_DEFINITION);
            case "VALIDATED" -> candidates.add(IntegrationService.TEST_CONNECTOR);
            case "TESTED" -> candidates.add(IntegrationService.SUBMIT_DEFINITION);
            case "PENDING_APPROVAL" -> candidates.add(IntegrationService.APPROVE_DEFINITION);
            case "APPROVED" -> candidates.add(IntegrationService.ACTIVATE_DEFINITION);
            case "ACTIVE" -> {
                candidates.add(IntegrationService.CREATE_MAPPING);
                if ("ACTIVE".equals(row.mappingState())) {
                    candidates.add(IntegrationService.CREATE_EXECUTION);
                }
            }
            default -> { }
        }
        if (row.mappingVersion() != null) {
            switch (String.valueOf(row.mappingState())) {
                case "DRAFT" -> candidates.add(IntegrationService.VALIDATE_MAPPING);
                case "VALIDATED" -> {
                    candidates.add(IntegrationService.DRY_RUN_MAPPING);
                    candidates.add(IntegrationService.SUBMIT_MAPPING);
                }
                case "PENDING_APPROVAL" -> candidates.add(IntegrationService.APPROVE_MAPPING);
                case "APPROVED" -> candidates.add(IntegrationService.ACTIVATE_MAPPING);
                default -> { }
            }
        }
        candidates.retainAll(Set.copyOf(allowed));
        return Set.copyOf(candidates);
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static UUID uuidOrNull(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static Long nullableLong(ResultSet rs, String column)
            throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static BaseException notFound(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    record StoredReceipt(
            String requestDigest,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String purposeCode,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            CommandReceipt receipt) {

        boolean matchesCurrentAuthority(CommandSeal command) {
            return java.util.Objects.equals(
                            populationScopeDigest, command.populationScopeDigest())
                    && fieldPolicyRevision == command.fieldPolicyRevision()
                    && authorizationRevision == command.authorizationRevision()
                    && java.util.Objects.equals(purposeCode, command.purposeCode())
                    && java.util.Objects.equals(
                            routeContractKey, command.routeContractKey())
                    && java.util.Objects.equals(contextKey, command.contextKey())
                    && java.util.Objects.equals(
                            contextScopeKey, command.contextScopeKey())
                    && java.util.Objects.equals(
                            decisionRevision, command.decisionRevision());
        }
    }

    record RetryItem(
            UUID executionItemId,
            int attemptNumber,
            long attemptFence,
            String sourceDigest,
            UUID mappingVersionId,
            long mappingVersionNumber) {}

    record InitialItem(
            UUID itemId,
            String sourceDigest,
            String providerIdempotencyKey,
            long attemptFence) {}

    record OwnerRow(
            UUID publicId,
            String connectorKey,
            long revision,
            OffsetDateTime updatedAt,
            String connectorType,
            long definitionVersion,
            String definitionState,
            Long mappingVersion,
            String mappingState,
            String direction,
            String compatibilityMode,
            OffsetDateTime effectiveFrom) {}
}
