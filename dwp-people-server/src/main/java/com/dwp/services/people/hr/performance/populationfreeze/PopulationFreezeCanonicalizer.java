package com.dwp.services.people.hr.performance.populationfreeze;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class PopulationFreezeCanonicalizer {

    private final ObjectMapper objectMapper;

    PopulationFreezeCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    String commandHash(
            PopulationFreezeDtos.FreezePopulationRequest request,
            long expectedCycleRevision,
            long tenantId,
            long actorId,
            UUID subjectId,
            UUID cycleId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("request", sorted(objectMapper.valueToTree(request)));
        root.put("expectedCycleRevision", expectedCycleRevision);
        root.put("tenantId", tenantId);
        root.put("actorId", actorId);
        root.put("subjectPrincipalPublicId", subjectId.toString());
        root.put("cycleId", cycleId.toString());
        root.put("originatingAction", PopulationFreezeService.ORIGINATING_ACTION);
        return hashJson(root);
    }

    String previewContentHash(List<PopulationFreezeRepository.PreviewMember> source) {
        List<PopulationFreezeRepository.PreviewMember> members = new ArrayList<>(source);
        members.sort(Comparator
                .comparing((PopulationFreezeRepository.PreviewMember member) ->
                        member.primaryAssignmentRef().toString())
                .thenComparing(member -> member.participantRef().toString()));
        String canonical = members.stream()
                .map(member -> member.participantRef()
                        + "|" + member.primaryAssignmentRef()
                        + "|" + member.workforceStatus()
                        + "|" + member.organizationRef()
                        + "|" + nullable(member.reviewerAssignmentRef())
                        + "|" + nullable(member.jobProfileRef())
                        + "|" + nullable(member.gradeRef())
                        + "|" + member.eligibilityCode())
                .reduce("", (left, right) -> left + right + "\n");
        return sha256(canonical);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String hashJson(JsonNode value) {
        try {
            return sha256(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Population-freeze command canonicalization failed.", exception);
        }
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, sorted(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    private String nullable(Object value) {
        return value == null ? "" : value.toString();
    }
}
