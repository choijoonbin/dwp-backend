package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration audit operations shared by the owner facade. */
final class JdbcIntegrationAudit {

    private final JdbcIntegrationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcIntegrationAudit(
            JdbcIntegrationRepositoryOperations owner,
            JdbcTemplate jdbc,
            ObjectMapper json) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.json = json;
    }


    public void appendExecutionEventAndAudit(
            CommandSeal command,
            ExecutionRecord execution,
            String transition,
            OffsetDateTime now) {
        Sys008EventContract.Payload payload = new Sys008EventContract.Payload(
                execution.executionId(), execution.state().name(),
                Math.toIntExact(execution.sourceCount()),
                Math.toIntExact(execution.acceptedCount()),
                Math.toIntExact(execution.rejectedCount()),
                Math.toIntExact(execution.quarantinedCount()),
                execution.reconciliationDigest());
        JsonNode payloadNode = json.valueToTree(payload);
        String payloadText = owner.stringify(payloadNode);
        String payloadDigest = IntegrationDomainPolicy.sha256(
                payloadText.getBytes(StandardCharsets.UTF_8));
        UUID eventId = UUID.nameUUIDFromBytes((command.tenantId() + ":"
                + execution.executionId() + ":" + execution.aggregateRevision())
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_domain_event_outbox (
                    outbox_id, event_id, spec_version, event_source, event_type,
                    schema_version, subject, tenant_id, aggregate_type,
                    aggregate_id, aggregate_sequence, correlation_id, payload,
                    payload_sha256, status, available_at, created_at, updated_at)
                VALUES (?, ?, '1.0', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), eventId, Sys008EventContract.SOURCE,
                Sys008EventContract.EVENT_TYPE,
                command.subjectPrincipalPublicId().toString(),
                command.tenantId(),
                Sys008EventContract.AGGREGATE_TYPE,
                execution.executionId().toString(), execution.aggregateRevision(),
                command.correlationId().toString(), payloadText, payloadDigest,
                now, now, now);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("controlId", "SYS-008");
        audit.put("transition", transition);
        audit.put("executionPublicId", execution.executionId());
        audit.put("executionRevision", execution.aggregateRevision());
        audit.put("state", execution.state().name());
        audit.put("correlationId", command.correlationId());
        audit.put("eventPayloadDigest", payloadDigest);
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), command.tenantId(),
                owner.stringify(json.valueToTree(audit)), now, now, now);
    }

    public void appendControlAudit(
            CommandSeal command,
            String aggregateType,
            UUID aggregatePublicId,
            long aggregateRevision,
            String transition,
            Object afterState,
            OffsetDateTime now) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("controlId", "SYS-008");
        audit.put("aggregateType", aggregateType);
        audit.put("aggregatePublicId", aggregatePublicId);
        audit.put("aggregateRevision", aggregateRevision);
        audit.put("transition", transition);
        audit.put("originatingAction", command.originatingAction());
        audit.put("subjectPrincipalPublicId",
                command.subjectPrincipalPublicId());
        audit.put("populationScopeDigest", command.populationScopeDigest());
        audit.put("fieldPolicyRevision", command.fieldPolicyRevision());
        audit.put("authorizationRevision", command.authorizationRevision());
        audit.put("correlationId", command.correlationId());
        audit.put("afterState", afterState);
        String payload = owner.stringify(json.valueToTree(audit));
        UUID eventId = UUID.nameUUIDFromBytes(("SYS008:AUDIT:"
                + command.tenantId() + ":" + aggregateType + ":"
                + aggregatePublicId + ":" + aggregateRevision + ":"
                + transition + ":" + command.idempotencyKey())
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), eventId, command.tenantId(), payload,
                now, now, now);
    }
}
