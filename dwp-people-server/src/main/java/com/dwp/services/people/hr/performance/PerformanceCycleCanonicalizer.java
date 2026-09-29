package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class PerformanceCycleCanonicalizer {

    private final ObjectMapper objectMapper;

    PerformanceCycleCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    List<PerformanceCycleDtos.StageInput> validatedStages(
            Instant effectiveFrom,
            Instant effectiveTo,
            List<PerformanceCycleDtos.StageInput> stages) {
        if (effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
            throw invalid("effectiveFrom must precede effectiveTo when effectiveTo is supplied.");
        }
        Set<String> keys = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        List<PerformanceCycleDtos.StageInput> ordered = new ArrayList<>(stages);
        ordered.sort(Comparator.comparingInt(PerformanceCycleDtos.StageInput::sequenceNo)
                .thenComparing(PerformanceCycleDtos.StageInput::stageKey));
        int expectedSequence = 1;
        for (PerformanceCycleDtos.StageInput stage : ordered) {
            if (stage.stageKey() == null || stage.stageKey().isBlank()
                    || stage.stageType() == null || stage.stageType().isBlank()) {
                throw invalid("Stage key and type must be nonblank.");
            }
            if (!keys.add(stage.stageKey()) || !sequences.add(stage.sequenceNo())) {
                throw invalid("Stage keys and sequence numbers must be unique.");
            }
            if (stage.sequenceNo() != expectedSequence++) {
                throw invalid("Stage sequence numbers must be contiguous from one.");
            }
            if (!stage.opensAt().isBefore(stage.closesAt())) {
                throw invalid("Every stage must open before it closes.");
            }
            if (stage.opensAt().isBefore(effectiveFrom)
                    || (effectiveTo != null && stage.closesAt().isAfter(effectiveTo))) {
                throw invalid("Stage windows must be contained in the cycle effective period.");
            }
        }
        return List.copyOf(ordered);
    }

    String contentHash(
            String displayName,
            Instant effectiveFrom,
            Instant effectiveTo,
            String timezoneId,
            Object policyVersionId,
            Object populationRuleVersionId,
            List<PerformanceCycleDtos.StageInput> stages) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("displayName", displayName.trim());
        root.put("effectiveFrom", effectiveFrom.toString());
        if (effectiveTo == null) root.putNull("effectiveTo");
        else root.put("effectiveTo", effectiveTo.toString());
        root.put("timezoneId", timezoneId.trim());
        root.put("policyVersionId", policyVersionId.toString());
        if (populationRuleVersionId == null) root.putNull("populationRuleVersionId");
        else root.put("populationRuleVersionId", populationRuleVersionId.toString());
        ArrayNode stageArray = root.putArray("stages");
        for (PerformanceCycleDtos.StageInput stage : stages) {
            ObjectNode node = stageArray.addObject();
            node.put("stageKey", stage.stageKey().trim());
            node.put("stageType", stage.stageType().trim());
            node.put("sequenceNo", stage.sequenceNo());
            node.put("opensAt", stage.opensAt().toString());
            node.put("closesAt", stage.closesAt().toString());
            node.put("required", stage.required());
            node.set("stageConfig", sorted(objectMapper.valueToTree(stage.stageConfig())));
        }
        return hashJson(root);
    }

    String requestHash(Object request) {
        return hashJson(sorted(objectMapper.valueToTree(request)));
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
            throw new IllegalStateException("Performance command canonicalization failed.", exception);
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

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
