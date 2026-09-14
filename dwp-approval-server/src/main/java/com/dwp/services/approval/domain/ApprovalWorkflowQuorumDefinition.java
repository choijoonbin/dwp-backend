package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** New tagged definitions only. Legacy untagged sequential ANY definitions are not dispatched here. */
public final class ApprovalWorkflowQuorumDefinition {

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final int slaMinutes;
    private final List<Stage> stages;
    private final List<Stage> topologicalStages;
    private final String canonicalJson;
    private final String sha256;

    public record Stage(String key, String name, String candidateRole, Rule quorum, int slaMinutes, List<String> predecessors,
            Map<String, Object> routeCondition) {
        public Stage(String key, String name, String candidateRole, Rule quorum, int slaMinutes, List<String> predecessors) {
            this(key, name, candidateRole, quorum, slaMinutes, predecessors, null);
        }
        public Stage {
            if (!role(key) || name == null || name.isBlank() || name.length() > 120 || !name.equals(name.strip())
                    || !role(candidateRole) || quorum == null || slaMinutes < 15 || slaMinutes > 525600
                    || predecessors == null || predecessors.size() > MAX_STAGES
                    || predecessors.stream().anyMatch(value -> !role(value) || value.equals(key))
                    || new HashSet<>(predecessors).size() != predecessors.size()) {
                throw invalid("The stage definition has invalid identifiers, predecessors or SLA bounds.");
            }
            predecessors = List.copyOf(predecessors);
            routeCondition = ApprovalWorkflowStageCondition.compile(routeCondition);
        }
    }

    private ApprovalWorkflowQuorumDefinition(int slaMinutes, List<Stage> stages) {
        if (slaMinutes < 15 || slaMinutes > 525600 || stages == null || stages.isEmpty() || stages.size() > MAX_STAGES) {
            throw invalid("A bounded workflow must define one or more stages and a valid SLA.");
        }
        this.slaMinutes = slaMinutes;
        this.stages = List.copyOf(stages);
        this.topologicalStages = validateGraph(this.stages, slaMinutes);
        this.canonicalJson = serialize(document(slaMinutes, this.stages));
        this.sha256 = digest(canonicalJson);
    }

    public static ApprovalWorkflowQuorumDefinition fromStages(int slaMinutes, List<Stage> stages) {
        return new ApprovalWorkflowQuorumDefinition(slaMinutes, stages);
    }

    public static ApprovalWorkflowQuorumDefinition compile(String json) {
        if (json == null || json.length() > 131072) throw invalid("The workflow definition exceeds the input limit.");
        try {
            Object value = JSON.readValue(json, Object.class);
            if (!(value instanceof Map<?, ?> map)) throw invalid("A workflow definition must be an object.");
            return compile(map);
        } catch (java.io.IOException exception) {
            throw invalid("A workflow definition must contain valid JSON without duplicate keys.");
        }
    }

    public static ApprovalWorkflowQuorumDefinition compile(Map<?, ?> definition) {
        exactKeys(definition, Set.of("schemaContract", "schemaVersion", "slaMinutes", "stages"));
        if (!CONTRACT.equals(definition.get("schemaContract")) || integer(definition.get("schemaVersion"), 2, 2) != 2) {
            throw invalid("Only the exact new tagged workflow contract can use this engine.");
        }
        int sla = integer(definition.get("slaMinutes"), 15, 525600);
        Object rawStages = definition.get("stages");
        if (!(rawStages instanceof List<?> values) || values.isEmpty() || values.size() > MAX_STAGES) {
            throw invalid("The workflow stages must be a bounded non-empty list.");
        }
        List<Stage> stages = new ArrayList<>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> stage)) throw invalid("Each workflow stage must be an object.");
            exactKeys(stage, stage.containsKey("routeCondition")
                    ? Set.of("key", "name", "candidateRole", "quorum", "slaMinutes", "predecessors", "routeCondition")
                    : Set.of("key", "name", "candidateRole", "quorum", "slaMinutes", "predecessors"));
            Map<String, Object> condition = null;
            if (stage.containsKey("routeCondition")) {
                if (!(stage.get("routeCondition") instanceof Map<?, ?> rawCondition)) throw invalid("A route condition must be an object.");
                condition = new java.util.LinkedHashMap<>();
                for (var entry : rawCondition.entrySet()) condition.put(string(entry.getKey()), entry.getValue());
            }
            if (!(stage.get("quorum") instanceof Map<?, ?> quorum)) throw invalid("A stage requires a typed quorum object.");
            if (quorum.containsKey("value")) exactKeys(quorum, Set.of("mode", "value"));
            else exactKeys(quorum, Set.of("mode"));
            Mode mode;
            try { mode = Mode.valueOf(string(quorum.get("mode"))); }
            catch (IllegalArgumentException exception) { throw invalid("The quorum mode is unsupported."); }
            Rule rule = new Rule(mode, quorum.containsKey("value") ? integer(quorum.get("value"), 1, MAX_CANDIDATES) : null);
            if (!(stage.get("predecessors") instanceof List<?> predecessors)) {
                throw invalid("A stage requires an explicit predecessor list.");
            }
            stages.add(new Stage(string(stage.get("key")), string(stage.get("name")),
                    string(stage.get("candidateRole")), rule, integer(stage.get("slaMinutes"), 15, 525600),
                    predecessors.stream().map(ApprovalWorkflowQuorumDefinition::string).toList(), condition));
        }
        return fromStages(sla, stages);
    }

    public int slaMinutes() { return slaMinutes; }
    public List<Stage> stages() { return stages; }
    public List<Stage> topologicalStages() { return topologicalStages; }
    public String canonicalJson() { return canonicalJson; }
    public String sha256() { return sha256; }

    private static List<Stage> validateGraph(List<Stage> stages, int workflowSla) {
        Map<String, Stage> byKey = new LinkedHashMap<>();
        for (Stage stage : stages) {
            if (byKey.put(stage.key(), stage) != null) throw invalid("Stage keys must be unique.");
        }
        for (Stage stage : stages) {
            if (!byKey.keySet().containsAll(stage.predecessors())) throw invalid("A predecessor refers to an unknown stage.");
        }
        List<Stage> ordered = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        Map<String, Integer> longestPath = new HashMap<>();
        while (ordered.size() < stages.size()) {
            List<Stage> ready = stages.stream().filter(stage -> !complete.contains(stage.key())
                    && complete.containsAll(stage.predecessors())).sorted(java.util.Comparator.comparing(Stage::key)).toList();
            if (ready.isEmpty()) throw invalid("Workflow predecessor cycles are not allowed.");
            for (Stage stage : ready) {
                int path = stage.slaMinutes() + stage.predecessors().stream().mapToInt(longestPath::get).max().orElse(0);
                if (path > workflowSla) throw invalid("The longest sequential SLA path exceeds the workflow SLA.");
                longestPath.put(stage.key(), path);
                ordered.add(stage);
                complete.add(stage.key());
            }
        }
        return List.copyOf(ordered);
    }

    private static Map<String, Object> document(int sla, List<Stage> stages) {
        Map<String, Object> root = new TreeMap<>();
        root.put("schemaContract", CONTRACT);
        root.put("schemaVersion", 2);
        root.put("slaMinutes", sla);
        root.put("stages", stages.stream().map(stage -> {
            Map<String, Object> value = new TreeMap<>();
            value.put("key", stage.key());
            value.put("name", stage.name());
            value.put("candidateRole", stage.candidateRole());
            Map<String, Object> quorum = new TreeMap<>();
            quorum.put("mode", stage.quorum().mode().name());
            if (stage.quorum().value() != null) quorum.put("value", stage.quorum().value());
            value.put("quorum", quorum);
            value.put("slaMinutes", stage.slaMinutes());
            value.put("predecessors", stage.predecessors());
            if (stage.routeCondition() != null) value.put("routeCondition", stage.routeCondition());
            return value;
        }).toList());
        return root;
    }

    private static void exactKeys(Map<?, ?> value, Set<String> keys) {
        if (value == null || !value.keySet().equals(keys)) throw invalid("The workflow definition has missing or unknown properties.");
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw invalid("A workflow string property cannot be coerced from another type.");
        return text;
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number number)) throw invalid("A workflow integer property must be numeric.");
        try {
            int parsed = new BigDecimal(number.toString()).intValueExact();
            if (parsed < min || parsed > max) throw invalid("A workflow integer exceeds its allowed bounds.");
            return parsed;
        } catch (NumberFormatException | ArithmeticException exception) { throw invalid("A workflow integer must be finite and exact."); }
    }

    private static String serialize(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw invalid("The workflow definition could not be canonicalized."); }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is required.", exception); }
    }
}
