package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** RFC-8785-compatible subset used by the closed TIM-003 typed value domain. */
final class EmploymentAbsenceCanonicalJson {

    private final ObjectMapper mapper;

    EmploymentAbsenceCanonicalJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String digest(Object value) {
        try {
            JsonNode canonical = canonical(mapper.valueToTree(value));
            return sha256(mapper.writeValueAsBytes(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("canonical JSON failed", exception);
        }
    }

    String scopeDigest(
            EmploymentAbsenceContract.OwnerRequestProof proof) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", proof.tenantId());
        fields.put("callerSpiffeId", EmploymentAbsenceContract.CALLER_SPIFFE);
        fields.put("audience", EmploymentAbsenceContract.OWNER_SERVICE);
        fields.put("tenantGrantRevision", proof.tenantGrantRevision());
        fields.put("businessTimeZone", proof.businessTimeZone());
        fields.put("relationshipPublicId", proof.relationshipPublicId());
        fields.put("minimumRelationshipRevision", proof.minimumRelationshipRevision());
        fields.put("effectiveOn", proof.effectiveOn());
        fields.put("purposeCode", proof.purpose());
        fields.put("projectionId", proof.projection());
        fields.put("authorizationRevision", proof.authorizationRevision());
        fields.put("fieldPolicyRevision", proof.fieldPolicyRevision());
        return digest(fields);
    }

    String storedScopeDigest(
            EmploymentAbsenceProjection projection,
            EmploymentAbsenceContract.OwnerGrant grant) {
        Xcon027EmploymentAbsenceSnapshotV1 snapshot = projection.ownerSnapshot();
        if (snapshot == null) throw new IllegalArgumentException("owner snapshot absent");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", projection.tenantId());
        fields.put("callerSpiffeId", EmploymentAbsenceContract.CALLER_SPIFFE);
        fields.put("audience", EmploymentAbsenceContract.OWNER_SERVICE);
        fields.put("tenantGrantRevision", grant.tenantGrantRevision());
        fields.put("businessTimeZone", projection.ownerBusinessTimeZone());
        fields.put("relationshipPublicId", projection.relationshipPublicId());
        fields.put("minimumRelationshipRevision",
                projection.ownerMinimumRelationshipRevision());
        fields.put("effectiveOn", snapshot.effectiveOn());
        fields.put("purposeCode", EmploymentAbsenceContract.PURPOSE);
        fields.put("projectionId", EmploymentAbsenceContract.PROJECTION);
        fields.put("authorizationRevision", grant.authorizationRevision());
        fields.put("fieldPolicyRevision", grant.fieldPolicyRevision());
        return digest(fields);
    }

    String contentDigest(Xcon027EmploymentAbsenceSnapshotV1 value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("relationshipPublicId", value.relationshipPublicId());
        fields.put("relationshipRevision", value.relationshipRevision());
        fields.put("workerPublicId", value.workerPublicId());
        fields.put("workerStatus", value.workerStatus());
        fields.put("relationshipType", value.relationshipType());
        fields.put("relationshipStatus", value.relationshipStatus());
        fields.put("legalEntityPublicId", value.legalEntityPublicId());
        fields.put("relationshipEffectiveFrom", value.relationshipEffectiveFrom());
        fields.put("relationshipEffectiveTo", value.relationshipEffectiveTo());
        fields.put("effectiveOn", value.effectiveOn());
        fields.put("assignments", new ArrayList<>(value.assignments()));
        fields.put("ownerRevision", value.ownerRevision());
        fields.put("asOf", value.asOf());
        fields.put("validUntil", value.validUntil());
        fields.put("ownerContractId", value.ownerContractId());
        fields.put("ownerService", value.ownerService());
        fields.put("purposeCode", value.purposeCode());
        fields.put("scopeDigest", value.scopeDigest());
        return digest(fields);
    }

    String evidenceDigest(EmploymentAbsenceContract.ResponseEvidence value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", value.tenantId());
        fields.put("callerSpiffeId", value.callerSpiffeId());
        fields.put("audience", value.audience());
        fields.put("tenantGrantRevision", value.tenantGrantRevision());
        fields.put("businessTimeZone", value.businessTimeZone());
        fields.put("purposeCode", value.purposeCode());
        fields.put("projectionId", value.projectionId());
        fields.put("authorizationRevision", value.authorizationRevision());
        fields.put("fieldPolicyRevision", value.fieldPolicyRevision());
        fields.put("correlationId", value.correlationId());
        fields.put("issuedAt", value.issuedAt());
        fields.put("asOf", value.asOf());
        fields.put("validUntil", value.validUntil());
        fields.put("scopeDigest", value.scopeDigest());
        fields.put("contentSha256", value.contentSha256());
        fields.put("keyId", value.keyId());
        return digest(fields);
    }

    String problemDigest(EmploymentAbsenceContract.OwnerProblem value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", value.httpStatus());
        fields.put("code", value.code());
        fields.put("retryable", value.retryable());
        fields.put("correlationId", value.correlationId());
        fields.put("issuedAt", value.issuedAt());
        fields.put("keyId", value.keyId());
        return digest(fields);
    }

    void validateGeneratedSnapshot(Xcon027EmploymentAbsenceSnapshotV1 value) {
        // Reconstructing the generated artifact re-runs its complete closed validation.
        Xcon027EmploymentAbsenceSnapshotV1 reconstructed =
                mapper.convertValue(mapper.valueToTree(value),
                        Xcon027EmploymentAbsenceSnapshotV1.class);
        if (!reconstructed.equals(value)) {
            throw new IllegalArgumentException("snapshot does not round-trip canonically");
        }
        for (var assignment : value.assignments()) {
            String zone = assignment.timeZone();
            if (zone != null && !"UTC".equals(zone)
                    && (!ZoneRulesProvider.getAvailableZoneIds().contains(zone)
                    || ZoneId.of(zone).getRules().isFixedOffset())) {
                throw new IllegalArgumentException("assignment zone is not canonical");
            }
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, child) -> result.set(key, canonical(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(child -> result.add(canonical(child)));
            return result;
        }
        if (value.isFloatingPointNumber()) {
            throw new IllegalArgumentException("floating-point values are not canonical here");
        }
        return value.deepCopy();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
