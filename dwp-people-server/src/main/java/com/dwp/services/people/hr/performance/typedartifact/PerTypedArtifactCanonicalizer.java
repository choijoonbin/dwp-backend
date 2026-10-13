package com.dwp.services.people.hr.performance.typedartifact;

import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Content;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guideline;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.PopulationLine;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Produces and verifies the closed canonical UTF-8 representation stored by PER. */
@Component
public final class PerTypedArtifactCanonicalizer {

    private final ObjectMapper mapper;

    public PerTypedArtifactCanonicalizer(ObjectMapper objectMapper) {
        mapper = objectMapper.copy()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public CanonicalArtifact canonicalize(PerArtifactType type, Content content) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Map<String, Object> value = switch (type) {
            case COMPENSATION_GUIDELINES -> guidelines(require(content, Guidelines.class));
            case COMPENSATION_POPULATION -> population(require(content, Population.class));
            case COMPENSATION_PROPOSAL -> proposal(require(content, Proposal.class));
        };
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            return new CanonicalArtifact(bytes, sha256(bytes));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("typed artifact cannot be canonicalized", exception);
        }
    }

    public Content decodeAndVerify(
            PerArtifactType type,
            byte[] canonicalBytes,
            String expectedDigest) {
        Objects.requireNonNull(type, "type must not be null");
        byte[] bytes = Objects.requireNonNull(canonicalBytes,
                "canonicalBytes must not be null").clone();
        String actualDigest = sha256(bytes);
        if (expectedDigest == null
                || !MessageDigest.isEqual(
                actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("typed artifact digest mismatch");
        }
        try {
            Class<? extends Content> target = switch (type) {
                case COMPENSATION_GUIDELINES -> Guidelines.class;
                case COMPENSATION_POPULATION -> Population.class;
                case COMPENSATION_PROPOSAL -> Proposal.class;
            };
            Content decoded = mapper.readValue(bytes, target);
            CanonicalArtifact rebuilt = canonicalize(type, decoded);
            if (!MessageDigest.isEqual(rebuilt.bytes(), bytes)
                    || !rebuilt.digest().equals(expectedDigest)) {
                throw new IllegalArgumentException(
                        "typed artifact bytes are not the canonical closed schema");
            }
            return decoded;
        } catch (IOException exception) {
            throw new IllegalArgumentException("typed artifact schema is invalid", exception);
        }
    }

    private Map<String, Object> guidelines(Guidelines content) {
        Map<String, Object> value = new TreeMap<>();
        value.put("budgetMode", content.budgetMode());
        List<Map<String, Object>> components = new ArrayList<>(content.components().size());
        for (Guideline component : content.components()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("componentCode", component.componentCode());
            row.put("currency", component.currency());
            row.put("exceptionPolicyCode", component.exceptionPolicyCode());
            row.put("maximumAmount", component.maximumAmount());
            row.put("minimumAmount", component.minimumAmount());
            row.put("roundingPolicyVersionPublicId",
                    component.roundingPolicyVersionPublicId().toString());
            components.add(row);
        }
        value.put("components", components);
        value.put("effectiveFrom", content.effectiveFrom().toString());
        value.put("effectiveTo",
                content.effectiveTo() == null ? null : content.effectiveTo().toString());
        return value;
    }

    private Map<String, Object> population(Population content) {
        Map<String, Object> value = new TreeMap<>();
        value.put("asOf", content.asOf().toString());
        value.put("hrmOwnerRevision", content.hrmOwnerRevision());
        List<Map<String, Object>> lines = new ArrayList<>(content.lines().size());
        for (PopulationLine line : content.lines()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("assignmentOwnerRevision", line.assignmentOwnerRevision());
            row.put("assignmentPublicId", line.assignmentPublicId().toString());
            row.put("eligibleComponentCodes", line.eligibleComponentCodes());
            row.put("gradePublicId",
                    line.gradePublicId() == null ? null : line.gradePublicId().toString());
            row.put("jobProfilePublicId",
                    line.jobProfilePublicId() == null ? null
                            : line.jobProfilePublicId().toString());
            row.put("organizationPublicId", line.organizationPublicId().toString());
            row.put("workerPublicId", line.workerPublicId().toString());
            lines.add(row);
        }
        value.put("lines", lines);
        return value;
    }

    private Map<String, Object> proposal(Proposal content) {
        Map<String, Object> value = new TreeMap<>();
        value.put("amount", content.amount());
        value.put("assignmentPublicId", content.assignmentPublicId().toString());
        value.put("componentCode", content.componentCode());
        value.put("currency", content.currency());
        value.put("cyclePublicId", content.cyclePublicId().toString());
        value.put("effectiveFrom", content.effectiveFrom().toString());
        value.put("effectiveTo",
                content.effectiveTo() == null ? null : content.effectiveTo().toString());
        value.put("exceptionReasonCode", content.exceptionReasonCode());
        value.put("supersedesProposalPublicId",
                content.supersedesProposalPublicId() == null ? null
                        : content.supersedesProposalPublicId().toString());
        value.put("workerPublicId", content.workerPublicId().toString());
        return value;
    }

    private <T extends Content> T require(Content content, Class<T> target) {
        if (!target.isInstance(content)) {
            throw new IllegalArgumentException(
                    "artifact type does not match " + target.getSimpleName());
        }
        return target.cast(content);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CanonicalArtifact(byte[] bytes, String digest) {
        public CanonicalArtifact {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
