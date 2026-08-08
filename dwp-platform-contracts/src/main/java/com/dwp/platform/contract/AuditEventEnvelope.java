package com.dwp.platform.contract;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record AuditEventEnvelope(
        String id,
        URI source,
        String type,
        String specVersion,
        Instant time,
        String subject,
        String tenantId,
        String correlationId,
        String causationId,
        String schemaVersion,
        DataClassification classification,
        Map<String, Object> data) {

    public AuditEventEnvelope {
        id = ContractChecks.required(id, "id");
        type = ContractChecks.required(type, "type");
        specVersion = ContractChecks.required(specVersion, "specVersion");
        subject = ContractChecks.required(subject, "subject");
        tenantId = ContractChecks.required(tenantId, "tenantId");
        correlationId = ContractChecks.required(correlationId, "correlationId");
        schemaVersion = ContractChecks.required(schemaVersion, "schemaVersion");
        if (source == null || time == null || classification == null) {
            throw new IllegalArgumentException("source, time, and classification are required");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
