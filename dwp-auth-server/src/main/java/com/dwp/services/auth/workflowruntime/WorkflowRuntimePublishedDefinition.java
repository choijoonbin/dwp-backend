package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.denied;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Auth's closed published-source verifier mirrors the tagged compiler, not the ROLE planning parser. */
public final class WorkflowRuntimePublishedDefinition {
    private WorkflowRuntimePublishedDefinition() { }
    public static String role(WorkflowRuntimeJson json, JsonNode owner, JsonNode sealedStage) {
        String raw = text(owner, "publishedDefinition", 131072);
        if (raw.getBytes(StandardCharsets.UTF_8).length > 131072 || !sha(raw).equals(hash(owner, "workflowDefinitionSha256"))) throw denied();
        JsonNode definition = json.read(raw);
        exact(definition, Set.of("schemaContract", "schemaVersion", "slaMinutes", "stages"));
        if (!text(definition, "schemaContract").equals("DWP_APPROVAL_WORKFLOW_QUORUM_V2")
                || number(definition, "schemaVersion", 2, 2) != 2 || !json.canonical(definition).equals(raw)) throw denied();
        long workflowSla = number(definition, "slaMinutes", 15, 525600);
        var stages = definition.get("stages");
        if (!stages.isArray() || stages.isEmpty() || stages.size() > 64) throw denied();
        var byKey = new HashMap<String, JsonNode>(); var roles = new TreeSet<String>();
        for (JsonNode stage : stages) {
            exact(stage, stage.has("routeCondition") ? Set.of("key", "name", "candidateRole", "quorum", "slaMinutes", "predecessors", "routeCondition")
                    : Set.of("key", "name", "candidateRole", "quorum", "slaMinutes", "predecessors"));
            String key = text(stage, "key"), role = text(stage, "candidateRole");
            if (!key.matches("[A-Z][A-Z0-9_]{1,49}") || !role.matches("[A-Z][A-Z0-9_]{1,49}") || byKey.putIfAbsent(key, stage) != null) throw denied();
            text(stage, "name", 120); number(stage, "slaMinutes", 15, 525600); roles.add(role);
            var quorum = stage.get("quorum");
            exact(quorum, quorum != null && quorum.has("value") ? Set.of("mode", "value") : Set.of("mode"));
            switch (text(quorum, "mode")) {
                case "ANY", "ALL" -> { if (quorum.has("value")) throw denied(); }
                case "COUNT" -> number(quorum, "value", 1, 1000);
                case "PERCENT" -> number(quorum, "value", 1, 100);
                default -> throw denied();
            }
            var predecessors = stage.get("predecessors");
            if (!predecessors.isArray() || predecessors.size() > 64) throw denied();
            var seen = new HashSet<String>();
            for (var predecessor : predecessors) {
                if (!predecessor.isTextual() || !predecessor.textValue().matches("[A-Z][A-Z0-9_]{1,49}")
                        || predecessor.textValue().equals(key) || !seen.add(predecessor.textValue())) throw denied();
            }
            if (stage.has("routeCondition")) condition(stage.get("routeCondition"));
        }
        var complete = new HashMap<String, Long>();
        while (complete.size() < byKey.size()) {
            int before = complete.size();
            for (String key : new TreeSet<>(byKey.keySet())) {
                if (complete.containsKey(key)) continue;
                var stage = byKey.get(key); long path = 0; boolean ready = true;
                for (var predecessor : stage.get("predecessors")) {
                    if (!byKey.containsKey(predecessor.textValue())) throw denied();
                    Long previous = complete.get(predecessor.textValue());
                    if (previous == null) { ready = false; break; } path = Math.max(path, previous);
                }
                if (ready) { path += number(stage, "slaMinutes", 15, 525600); if (path > workflowSla) throw denied(); complete.put(key, path); }
            }
            if (before == complete.size()) throw denied();
        }
        var supplied = owner.get("roleCodes");
        if (supplied == null || !supplied.isArray() || supplied.size() != roles.size()) throw denied();
        int index = 0; for (String role : roles) if (!supplied.get(index++).isTextual() || !supplied.get(index - 1).textValue().equals(role)) throw denied();
        JsonNode selected = byKey.get(text(sealedStage, "stepKey"));
        if (selected == null || !text(selected, "candidateRole").equals(text(sealedStage, "candidateRole"))) throw denied();
        return text(selected, "candidateRole");
    }
    private static void condition(JsonNode condition) {
        exact(condition, Set.of("all")); var clauses = condition.get("all");
        if (!clauses.isArray() || clauses.isEmpty() || clauses.size() > 50) throw denied();
        for (var clause : clauses) {
            exact(clause, Set.of("field", "operator", "value"));
            if (!text(clause, "field").matches("[a-zA-Z][a-zA-Z0-9_]{0,79}")) throw denied();
            String operator = text(clause, "operator");
            if (!Set.of("EQ", "IN", "GT", "GTE", "LT", "LTE").contains(operator)) throw denied();
            var value = clause.get("value");
            if (operator.equals("IN")) { if (!value.isArray() || value.isEmpty() || value.size() > 50) throw denied(); value.forEach(WorkflowRuntimePublishedDefinition::scalar); }
            else scalar(value);
        }
    }
    private static void scalar(JsonNode value) {
        if (value.isBoolean() || value.isNumber() && !value.isFloatingPointNumber()) return;
        if (value.isFloatingPointNumber() && Double.isFinite(value.doubleValue())) return;
        if (value.isTextual() && value.textValue().length() <= 2000 && value.textValue().codePoints().noneMatch(Character::isISOControl)) return;
        throw denied();
    }
}
