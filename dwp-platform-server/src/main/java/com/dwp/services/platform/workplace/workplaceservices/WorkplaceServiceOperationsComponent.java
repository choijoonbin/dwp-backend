package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.OperationsCommandReceipt;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;

abstract class WorkplaceServiceOperationsComponent {
    protected final WorkplaceServiceOperationsRepository repository;
    protected final ObjectMapper objectMapper;
    protected final TransactionTemplate transaction;
    protected final Clock clock;

    WorkplaceServiceOperationsComponent(
            WorkplaceServiceOperationsRepository repository,
            ObjectMapper objectMapper,
            TransactionTemplate transaction,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
        this.clock = clock;
    }

    protected ProviderRow requireProvider(long tenantId, UUID providerId) {
        if (providerId == null) throw invalid("Provider id is required.");
        return repository.provider(tenantId, providerId)
                .orElseThrow(() -> notFound("The provider profile was not found."));
    }

    protected OperationsCommandRow command(
            long tenantId, long actorUserId, String scope, String key, String fingerprint,
            String resourceType, UUID resourceId, CommandState state, String href,
            String correlationId, OffsetDateTime now) {
        return new OperationsCommandRow(UUID.randomUUID(), tenantId, actorUserId, scope,
                key, fingerprint, resourceType, resourceId, state, href,
                normalizeCorrelation(correlationId), now, now);
    }

    protected OperationsCommandReceipt receipt(
            OperationsCommandRow command, boolean replayed) {
        return new OperationsCommandReceipt(
                command.commandId(),
                command.state(),
                command.statusHref(), replayed, command.correlationId(), command.createdAt());
    }

    protected void requireFingerprint(OperationsCommandRow row, String expected) {
        if (!row.fingerprint().equals(expected)) {
            throw conflict("The idempotency key was already used for another command.");
        }
    }

    protected String fingerprint(Object... values) {
        try {
            return sha256(objectMapper.writeValueAsString(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to fingerprint Workplace service command.", exception);
        }
    }

    protected static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    protected void audit(long tenantId, long actorUserId, UUID aggregateId,
                         String aggregateType, String action, String eventType,
                         String correlationId, JsonNode detail, OffsetDateTime now) {
        repository.appendAuditAndOutbox(tenantId, actorUserId, aggregateId,
                aggregateType, action, eventType, normalizeCorrelation(correlationId), detail, now);
    }

    protected ObjectNode detail(String key, Object value, String reason) {
        ObjectNode detail = objectMapper.createObjectNode().put("reason", reason.trim());
        if (value instanceof UUID id) detail.put(key, id.toString());
        else detail.put(key, String.valueOf(value));
        return detail;
    }

    protected OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    protected static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("Tenant id is required.");
    }

    protected static void requireActor(long tenantId, long actorUserId) {
        requireTenant(tenantId);
        if (actorUserId <= 0) throw invalid("Actor user id is required.");
    }

    protected static String requireKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw invalid("A valid Idempotency-Key is required.");
        }
        return value.trim();
    }

    protected static String normalizeCorrelation(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().substring(0, Math.min(160, value.trim().length()));
    }

    protected static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    protected static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    protected static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    protected static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
