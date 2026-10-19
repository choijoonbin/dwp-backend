package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** RFC-8785-compatible canonicalizer for the closed HRM-011 value domain. */
@Component
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class EmploymentContractCanonicalizer {

    private final ObjectMapper mapper;

    public EmploymentContractCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String generationDigest(EmploymentContractDtos.GenerationSnapshot snapshot) {
        EmploymentContractDtos.RelationshipSnapshot relationship =
                snapshot.relationshipSnapshot();
        EmploymentContractDtos.AssignmentSnapshot assignment = snapshot.assignmentSnapshot();
        EmploymentContractDtos.CompensationBasisSnapshot basis =
                snapshot.compensationBasisSnapshot();
        EmploymentContractTemplateSnapshotV1 template = snapshot.templateSnapshot();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("relationshipPublicId", relationship.relationshipPublicId());
        fields.put("relationshipVersion", relationship.resourceVersion());
        fields.put("relationshipEffectiveFrom", relationship.effectiveFrom());
        fields.put("relationshipEffectiveTo", relationship.effectiveTo());
        fields.put("assignmentPublicId", assignment.assignmentPublicId());
        fields.put("assignmentVersion", assignment.assignmentVersion());
        fields.put("assignmentEffectiveFrom", assignment.effectiveFrom());
        fields.put("assignmentEffectiveTo", assignment.effectiveTo());
        fields.put("compensationBasisPublicId", basis.compensationBasisPublicId());
        fields.put("compensationBasisVersion", basis.compensationBasisVersion());
        fields.put("basisType", basis.basisType());
        fields.put("amountCanonicalDecimal", basis.amountCanonicalDecimal());
        fields.put("currency", basis.currency());
        fields.put("frequency", basis.frequency());
        fields.put("basisEffectiveFrom", basis.effectiveFrom());
        fields.put("basisEffectiveTo", basis.effectiveTo());
        fields.put("templatePublicId", template.templatePublicId());
        fields.put("templateVersion", template.templateVersion());
        fields.put("templateTenantId", template.tenantId());
        fields.put("templateContentSchemaVersion", template.contentSchemaVersion());
        fields.put("templateSourceRevision", template.sourceRevision());
        fields.put("templatePublishedAt", template.publishedAt());
        fields.put("templatePayloadDigest", template.payloadDigest());
        fields.put("templateContentObjectPublicId",
                template.templateContentReference().objectPublicId());
        fields.put("templateContentObjectVersion",
                template.templateContentReference().objectVersion());
        fields.put("templateContentSha256",
                template.templateContentReference().contentSha256());
        fields.put("templateScopeType", template.scopeType());
        fields.put("templateScopeId", template.scopeId());
        fields.put("templateEffectiveFrom", template.effectiveFrom());
        fields.put("templateEffectiveTo", template.effectiveTo());
        fields.put("locale", snapshot.locale());
        return digest(fields);
    }

    public String digest(Object value) {
        try {
            String json = mapper.writeValueAsString(canonical(mapper.valueToTree(value)));
            return sha256(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRM-011 canonical JSON failed.", exception);
        }
    }

    public long positiveRevision(String value) {
        byte[] bytes = hash(value.getBytes(StandardCharsets.UTF_8));
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result = (result << 8) | (bytes[index] & 0xffL);
        }
        return result == Long.MIN_VALUE ? 1 : Math.max(1, Math.abs(result));
    }

    public UUID deterministicUuid(Object... values) {
        StringBuilder source = new StringBuilder("HRM-011");
        for (Object value : values) source.append('\n').append(value);
        return UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return mapper.nullNode();
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            ArrayList<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(hash(bytes));
    }

    private byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
