package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.platform.contract.AuditEventEnvelope;
import com.dwp.platform.contract.DataClassification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Persists the exact minimized workload envelope through the shared audit outbox. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011WorkloadAuditOutboxAdapter implements Hrm011WorkloadAuditPort {
    private static final URI EXPECTED_SOURCE = URI.create(SOURCE);
    private static final Set<String> OUTCOMES = Set.of("SUCCESS");

    private final AuditOutboxRecorder outbox;

    public Hrm011WorkloadAuditOutboxAdapter(AuditOutboxRecorder outbox) {
        this.outbox = Objects.requireNonNull(outbox);
    }

    @Override
    public void record(AuditEventEnvelope envelope) {
        Evidence evidence = requireExact(envelope);
        outbox.record(AuditEvent.builder()
                .eventId(evidence.eventId())
                .occurredAt(envelope.time())
                .tenantId(evidence.tenantId())
                .category("DATA_ACCESS")
                .action(evidence.operationId())
                .outcome(evidence.outcome())
                .severity("HIGH")
                .riskScore(75)
                .actorType("SERVICE")
                .actorId(evidence.callerWorkloadId())
                .sourceService("dwp-people-server")
                .sourceModule("hris-contracts-hrm011")
                .targetType("HRIS_WORKLOAD_SNAPSHOT_PAGE")
                .targetId(evidence.projectionId())
                .correlationId(evidence.correlationId().toString())
                .policyId(policyId(evidence.operationId()))
                .policyDecision("ALLOW")
                .metadata(envelope.data())
                .retentionClass("EXTENDED")
                .build());
    }

    private Evidence requireExact(AuditEventEnvelope envelope) {
        if (envelope == null || !EXPECTED_SOURCE.equals(envelope.source())
                || !TYPE.equals(envelope.type())
                || !SPEC_VERSION.equals(envelope.specVersion())
                || !SCHEMA_VERSION.equals(envelope.schemaVersion())
                || envelope.classification() != DataClassification.RESTRICTED
                || envelope.causationId() != null
                || envelope.time() == null
                || envelope.data() == null
                || !DATA_FIELDS.equals(envelope.data().keySet())) {
            throw invalid();
        }
        Map<String, Object> data = envelope.data();
        String caller = string(data, "callerWorkloadId");
        long tenantId = positiveLong(data, "tenantId");
        String operation = string(data, "operationId");
        String purpose = string(data, "purposeCode");
        String projection = string(data, "projectionId");
        long policyRevision = positiveLong(data, "policyRevision");
        UUID correlation = canonicalUuid(string(data, "correlationId"));
        String outcome = string(data, "outcome");
        int rowCount = nonnegativeInteger(data, "rowCount");
        String payloadDigest = string(data, "payloadDigest");
        UUID eventId = canonicalUuid(envelope.id());

        if (!Hrm011WorkloadIngress.PAYROLL_CALLER.equals(caller)
                || !OUTCOMES.contains(outcome)
                || !payloadDigest.matches("[0-9a-f]{64}")
                || !Long.toString(tenantId).equals(envelope.tenantId())
                || !correlation.toString().equals(envelope.correlationId())
                || !operation.equals(envelope.subject())
                || !eventId.equals(Hrm011WorkloadAuditPort.deterministicEventId(data))
                || !operationBinding(operation, purpose, projection)) {
            throw invalid();
        }
        return new Evidence(eventId, tenantId, caller, operation, projection,
                policyRevision, correlation, outcome, rowCount, payloadDigest);
    }

    private boolean operationBinding(String operation, String purpose, String projection) {
        return switch (operation) {
            case Hrm011WorkloadIngress.SVC003_OPERATION ->
                    Hrm011WorkloadIngress.SVC003_PURPOSE.equals(purpose)
                            && Hrm011WorkloadIngress.COMPENSATION.equals(projection);
            case Hrm011WorkloadIngress.SVC005_OPERATION ->
                    Hrm011WorkloadIngress.SVC005_PURPOSE.equals(purpose)
                            && Hrm011WorkloadIngress.PAYROLL_PROJECTIONS.contains(projection);
            default -> false;
        };
    }

    private String policyId(String operation) {
        return Hrm011WorkloadIngress.SVC003_OPERATION.equals(operation)
                ? "SVC-PEP-HRM-003-R2" : "SVC-PEP-HRM-005";
    }

    private String string(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String result) || result.isBlank()
                || !result.equals(result.trim())) {
            throw invalid();
        }
        return result;
    }

    private long positiveLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Long result) || result < 1) throw invalid();
        return result;
    }

    private int nonnegativeInteger(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Integer result) || result < 0) throw invalid();
        return result;
    }

    private UUID canonicalUuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) throw invalid();
            return result;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("The HRM-011 workload audit envelope is invalid.");
    }

    private record Evidence(
            UUID eventId,
            long tenantId,
            String callerWorkloadId,
            String operationId,
            String projectionId,
            long policyRevision,
            UUID correlationId,
            String outcome,
            int rowCount,
            String payloadDigest) {
    }
}
