package com.dwp.core.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executes a provider tenant command under a transaction-scoped tenant/domain
 * lock and persists its receipt. Callers must invoke {@link #execute} from the
 * same transaction that applies the downstream tenant mutation.
 */
public final class ProviderTenantCommandReceiptStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    public ProviderTenantCommandReceiptStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String serviceName) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
    }

    public ProviderTenantCommand.Receipt execute(
            UUID providerTenantId,
            ProviderTenantCommand.Request command,
            Supplier<JsonNode> applyMutation) {
        validate(providerTenantId, command);
        ProviderTenantCommand.Receipt existing = receipt(command.commandId(), true);
        if (existing != null) {
            requireReplayMatch(providerTenantId, command, existing);
            return existing;
        }

        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, providerTenantId + ":" + command.commandType());

        existing = receipt(command.commandId(), true);
        if (existing != null) {
            requireReplayMatch(providerTenantId, command, existing);
            return existing;
        }

        Long currentRevision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(target_revision), 0)
                  FROM sys_provider_tenant_command_receipts
                 WHERE provider_tenant_id = ? AND command_type = ?
                """, Long.class, providerTenantId, command.commandType());
        long current = currentRevision == null ? 0L : currentRevision;
        if (command.expectedRevision() != current
                || command.targetRevision() != command.expectedRevision() + 1L) {
            throw conflict("Provider command is out of order. Current " + serviceName
                    + " revision is " + current + ".");
        }

        JsonNode result = applyMutation.get();
        if (result == null || !result.isObject()) {
            throw new IllegalStateException(serviceName + " command result must be a JSON object.");
        }
        jdbc.update("""
                INSERT INTO sys_provider_tenant_command_receipts (
                    command_id, provider_tenant_id, command_type,
                    expected_revision, target_revision, payload_sha256, result_payload)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, command.commandId(), providerTenantId, command.commandType(),
                command.expectedRevision(), command.targetRevision(), command.payloadSha256(), json(result));
        return Objects.requireNonNull(receipt(command.commandId(), false));
    }

    private void validate(UUID providerTenantId, ProviderTenantCommand.Request command) {
        if (providerTenantId == null || command == null || command.commandId() == null
                || command.commandType() == null || command.payloadSha256() == null
                || command.payload() == null || !command.payload().isObject()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Provider command envelope is incomplete.");
        }
        if (!List.of("LIFECYCLE", "ENTITLEMENTS").contains(command.commandType())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported provider command type.");
        }
        String actual = ProviderTenantCommand.payloadSha256(objectMapper, command.payload());
        if (!MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                command.payloadSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw conflict("Provider command payload hash does not match its envelope.");
        }
    }

    private void requireReplayMatch(
            UUID providerTenantId,
            ProviderTenantCommand.Request command,
            ProviderTenantCommand.Receipt receipt) {
        if (!providerTenantId.equals(receipt.providerTenantId())
                || !command.commandType().equals(receipt.commandType())
                || command.expectedRevision() != receipt.expectedRevision()
                || command.targetRevision() != receipt.targetRevision()
                || !MessageDigest.isEqual(
                        command.payloadSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        receipt.payloadSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw conflict("Provider command identifier was already used for a different mutation.");
        }
    }

    private ProviderTenantCommand.Receipt receipt(UUID commandId, boolean replayed) {
        return jdbc.query("""
                SELECT command_id, provider_tenant_id, command_type,
                       expected_revision, target_revision, payload_sha256,
                       result_payload, applied_at
                  FROM sys_provider_tenant_command_receipts
                 WHERE command_id = ?
                """, (result, ignored) -> map(result, replayed), commandId)
                .stream().findFirst().orElse(null);
    }

    private ProviderTenantCommand.Receipt map(ResultSet result, boolean replayed) throws SQLException {
        try {
            return new ProviderTenantCommand.Receipt(
                    result.getObject("command_id", UUID.class),
                    result.getObject("provider_tenant_id", UUID.class),
                    result.getString("command_type"),
                    result.getLong("expected_revision"),
                    result.getLong("target_revision"),
                    result.getString("payload_sha256"),
                    objectMapper.readTree(result.getString("result_payload")),
                    result.getTimestamp("applied_at").toInstant(),
                    replayed);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Could not read provider command receipt.", exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize provider command receipt.", exception);
        }
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
