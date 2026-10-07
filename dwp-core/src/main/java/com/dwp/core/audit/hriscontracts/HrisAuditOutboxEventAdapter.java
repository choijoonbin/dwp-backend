package com.dwp.core.audit.hriscontracts;

import com.dwp.audit.AuditEvent;
import com.dwp.platform.contracts.hris.generated.HrisAuditOutboxEventV1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Converts the bounded HRIS evidence projection without accepting raw state or free-form data. */
public final class HrisAuditOutboxEventAdapter {

    private HrisAuditOutboxEventAdapter() {
    }

    public static AuditEvent toAuditEvent(HrisAuditOutboxEventV1 source) {
        Objects.requireNonNull(source, "source must not be null");
        Map<String, Object> evidence = new LinkedHashMap<>();
        put(evidence, "aggregateRef", source.aggregateRef());
        put(evidence, "aggregateRevision", source.aggregateRevision());
        put(evidence, "effectiveCapability", source.effectiveCapability());
        put(evidence, "purposeCode", source.purposeCode());
        put(evidence, "scopeRevision", source.scopeRevision());
        put(evidence, "fieldPolicyRevision", source.fieldPolicyRevision());
        put(evidence, "delegationRef", source.delegationRef());
        put(evidence, "supportSessionRef", source.supportSessionRef());
        put(evidence, "stepUpReceiptRef", source.stepUpReceiptRef());
        put(evidence, "beforeDigest", source.beforeDigest());
        put(evidence, "afterDigest", source.afterDigest());
        put(evidence, "evidenceDigest", source.evidenceDigest());
        put(evidence, "classification", source.classification());
        put(evidence, "causationId", source.causationId().toString());

        return AuditEvent.builder()
                .eventId(source.eventId())
                .occurredAt(source.occurredAt())
                .tenantId(source.tenantId())
                .category("DENIED".equals(source.outcome())
                        ? "POLICY_DENIED"
                        : "ADMIN_CHANGE")
                .action(source.action())
                .outcome(source.outcome())
                .severity("SUCCESS".equals(source.outcome()) ? "INFO" : "HIGH")
                .actorType(source.actorType())
                .actorId(source.actorRef())
                .sourceService(source.sourceService())
                .sourceModule(source.sourceModule())
                .targetType(source.aggregateType())
                .targetId(source.targetRef())
                .reason(source.errorCode() != null ? source.errorCode() : source.reasonCode())
                .correlationId(source.correlationId().toString())
                .policyId(source.authorizationDecisionRef())
                .policyDecision(source.outcome())
                .approvalId(source.approvalReceiptId() == null
                        ? null
                        : source.approvalReceiptId().toString())
                .beforeState(Map.of())
                .afterState(Map.of())
                .metadata(evidence)
                .retentionClass(source.retentionClass())
                .build();
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
