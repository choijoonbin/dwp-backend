package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.Set;

import static com.dwp.services.approval.security.ApprovalPepProjectionJson.readProjection;
import static com.dwp.services.approval.security.ApprovalPepProjectionJson.require;

/** Loads and validates the complete lineage required by an Approval PEP projection. */
final class ApprovalPepProjectionLoader {

    static final String RESOURCE =
            "product-authorization/approval-pilot-pep-v2.generated.json";
    static final String V7_RESOURCE =
            "product-authorization/approval-pilot-pep-v7.generated.json";
    static final String V8_RESOURCE =
            "product-authorization/approval-pilot-pep-v8.generated.json";
    static final String V9_RESOURCE =
            "product-authorization/approval-pilot-pep-v9.generated.json";
    static final String V10_RESOURCE =
            "product-authorization/approval-pilot-pep-v10.generated.json";
    static final String V11_RESOURCE =
            "product-authorization/approval-pilot-pep-v11.generated.json";
    static final String V12_RESOURCE =
            "product-authorization/approval-pilot-pep-v12.generated.json";
    static final String V13_RESOURCE =
            "product-authorization/approval-pilot-pep-v13.generated.json";
    static final String V14_RESOURCE =
            "product-authorization/approval-pilot-pep-v14.generated.json";
    static final String V15_RESOURCE =
            "product-authorization/approval-pilot-pep-v15.generated.json";
    static final String V19_RESOURCE =
            "product-authorization/approval-pilot-pep-v19.generated.json";
    static final String V31_RESOURCE =
            "product-authorization/approval-pilot-pep-v31.generated.json";
    static final String V32_RESOURCE =
            "product-authorization/approval-pilot-pep-v32.generated.json";

    private static final Set<Integer> SUPPORTED = Set.of(
            2, 7, 8, 9, 10, 11, 12, 13, 14, 15, 19, 31, 32);

    private ApprovalPepProjectionLoader() { }

    static ObjectNode load(ObjectMapper objectMapper, Clock clock, int version) {
        require(SUPPORTED.contains(version), "Unsupported Approval PEP version");
        if (version != 2) load(objectMapper, clock, 2);
        ObjectNode projection = readProjection(objectMapper, resource(version));
        ApprovalPepProjectionLineage.validateEnvelope(objectMapper, projection, version);
        if (version != 2) {
            ApprovalPepProjectionLineage.validateSuperset(
                    readProjection(objectMapper, RESOURCE), projection);
            ApprovalWorkProjectionSchemaContract.validateResource(objectMapper);
        }
        switch (version) {
            case 8 -> validatePrior(objectMapper, clock, projection, 7,
                    V7_RESOURCE, true, false, false);
            case 9 -> validatePrior(objectMapper, clock, projection, 8,
                    V8_RESOURCE, false, true, false);
            case 10 -> validatePrior(objectMapper, clock, projection, 9,
                    V9_RESOURCE, false, false, true);
            case 11 -> validatePrior(objectMapper, clock, projection, 10,
                    V10_RESOURCE, false, false, true);
            case 12 -> validatePrior(objectMapper, clock, projection, 11,
                    V11_RESOURCE, false, false, true);
            case 13 -> validatePrior(objectMapper, clock, projection, 12,
                    V12_RESOURCE, false, false, true);
            case 14 -> validatePrior(objectMapper, clock, projection, 13,
                    V13_RESOURCE, false, false, true);
            case 15 -> validatePrior(objectMapper, clock, projection, 14,
                    V14_RESOURCE, false, false, true);
            case 19 -> validatePrior(objectMapper, clock, projection, 15,
                    V15_RESOURCE, false, false, true);
            case 31 -> validatePrior(objectMapper, clock, projection, 19,
                    V19_RESOURCE, false, false, true);
            case 32 -> validatePrior(objectMapper, clock, projection, 31,
                    V31_RESOURCE, false, false, true);
            default -> { }
        }
        return projection;
    }

    private static void validatePrior(
            ObjectMapper objectMapper,
            Clock clock,
            ObjectNode projection,
            int priorVersion,
            String priorResource,
            boolean documentSchema,
            boolean extensionSchema,
            boolean releaseSchemas) {
        load(objectMapper, clock, priorVersion);
        ApprovalPepProjectionLineage.validateSuperset(
                readProjection(objectMapper, priorResource), projection);
        if (documentSchema) ApprovalDocumentProjectionSchemaContract.validateResource(objectMapper);
        if (extensionSchema) ApprovalExtensionProjectionSchemaContract.validateResource(objectMapper);
        if (releaseSchemas) {
            ApprovalRelease10ProjectionSchemaContract.validateResource(objectMapper);
            if (priorVersion >= 10) {
                ApprovalRecovery11ProjectionSchemaContract.validateResource(objectMapper);
            }
        }
    }

    private static String resource(int version) {
        return switch (version) {
            case 2 -> RESOURCE;
            case 7 -> V7_RESOURCE;
            case 8 -> V8_RESOURCE;
            case 9 -> V9_RESOURCE;
            case 10 -> V10_RESOURCE;
            case 11 -> V11_RESOURCE;
            case 12 -> V12_RESOURCE;
            case 13 -> V13_RESOURCE;
            case 14 -> V14_RESOURCE;
            case 15 -> V15_RESOURCE;
            case 19 -> V19_RESOURCE;
            case 31 -> V31_RESOURCE;
            case 32 -> V32_RESOURCE;
            default -> throw new IllegalStateException("Unsupported Approval PEP version");
        };
    }
}
