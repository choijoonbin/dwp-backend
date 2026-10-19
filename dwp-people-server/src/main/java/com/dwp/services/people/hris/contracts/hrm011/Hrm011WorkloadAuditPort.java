package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.platform.contract.AuditEventEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact owner port for the SVC-PEP-HRM-003/005 workload audit envelope. */
@FunctionalInterface
public interface Hrm011WorkloadAuditPort {
    String SOURCE = "urn:dwp:service:dwp-people-server:hrm011";
    String TYPE = "dwp.hris.people.workload.snapshot.access.v1";
    String SPEC_VERSION = "1.0";
    String SCHEMA_VERSION = "1";
    Set<String> DATA_FIELDS = Set.of(
            "callerWorkloadId", "tenantId", "operationId", "purposeCode",
            "projectionId", "policyRevision", "correlationId", "outcome",
            "rowCount", "payloadDigest");

    void record(AuditEventEnvelope envelope);

    /**
     * Audit identity is retry-stable and is derived only from the sealed,
     * non-sensitive evidence values. No workload token or snapshot value enters it.
     */
    static UUID deterministicEventId(Map<String, Object> data) {
        String material = String.join("\n",
                "dwp.hris.hrm011.workload-audit.v1",
                value(data, "callerWorkloadId"),
                value(data, "tenantId"),
                value(data, "operationId"),
                value(data, "purposeCode"),
                value(data, "projectionId"),
                value(data, "policyRevision"),
                value(data, "correlationId"),
                value(data, "outcome"),
                value(data, "rowCount"),
                value(data, "payloadDigest"));
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing HRM-011 workload audit field: " + key);
        }
        return value.toString();
    }
}
