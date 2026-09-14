package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition.Stage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalWorkflowQuorumDefinitionTest {

    @Test
    void newContractIsTaggedCanonicalImmutableAndIndependentOfLegacyAny() {
        List<String> predecessors = new ArrayList<>();
        List<Stage> stages = new ArrayList<>(List.of(new Stage("FIRST_REVIEW", "First review", "APPROVAL_OPERATOR",
                new Rule(Mode.ANY, null), 15, predecessors)));
        ApprovalWorkflowQuorumDefinition definition = ApprovalWorkflowQuorumDefinition.fromStages(15, stages);
        predecessors.add("UNKNOWN_REVIEW");
        stages.clear();
        assertThat(definition.stages()).hasSize(1);
        assertThat(definition.canonicalJson()).contains(CONTRACT).doesNotContain("UNKNOWN_REVIEW");
        assertThat(ApprovalWorkflowQuorumDefinition.compile(definition.canonicalJson()).sha256()).isEqualTo(definition.sha256());
        assertThatThrownBy(() -> definition.stages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.stages().get(0).predecessors().clear()).isInstanceOf(UnsupportedOperationException.class);
        reject(Map.of("schemaVersion", 1, "steps", List.of(Map.of("mode", "ANY"))));
        reject(Map.of("schemaVersion", 2, "steps", List.of(Map.of("mode", "ANY"))));
    }

    @Test
    void canonicalHashBindsQuorumRoleSlaAndEdgesNotObjectKeyOrder() {
        Map<String, Object> map = definition(stage("FIRST_REVIEW", "ANY", null));
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("stages", map.get("stages"));
        reversed.put("slaMinutes", 30L);
        reversed.put("schemaVersion", 2L);
        reversed.put("schemaContract", CONTRACT);
        String hash = ApprovalWorkflowQuorumDefinition.compile(map).sha256();
        assertThat(ApprovalWorkflowQuorumDefinition.compile(reversed).sha256()).isEqualTo(hash);
        assertThat(ApprovalWorkflowQuorumDefinition.compile(definition(stage("FIRST_REVIEW", "ALL", null))).sha256()).isNotEqualTo(hash);
        Map<String, Object> renamed = stage("FIRST_REVIEW", "ANY", null);
        renamed.put("candidateRole", "FINANCE_REVIEWER");
        assertThat(ApprovalWorkflowQuorumDefinition.compile(definition(renamed)).sha256()).isNotEqualTo(hash);
        renamed.put("candidateRole", "APPROVAL_OPERATOR");
        renamed.put("slaMinutes", 30);
        assertThat(ApprovalWorkflowQuorumDefinition.compile(definition(renamed)).sha256()).isNotEqualTo(hash);
    }

    @Test
    void parallelStagesConsumeTheLongestDependencyPathNotTheSumOfParallelSlas() {
        List<Stage> stages = List.of(
                new Stage("FINANCE_REVIEW", "Finance", "FINANCE_REVIEWER", new Rule(Mode.COUNT, 2), 30, List.of()),
                new Stage("SECURITY_REVIEW", "Security", "SECURITY_REVIEWER", new Rule(Mode.ALL, null), 45, List.of()),
                new Stage("FINAL_REVIEW", "Final", "APPROVAL_OPERATOR", new Rule(Mode.ANY, null), 15, List.of("FINANCE_REVIEW", "SECURITY_REVIEW")));
        ApprovalWorkflowQuorumDefinition definition = ApprovalWorkflowQuorumDefinition.fromStages(60, stages);
        assertThat(definition.topologicalStages()).extracting(Stage::key).containsExactly("FINANCE_REVIEW", "SECURITY_REVIEW", "FINAL_REVIEW");
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.fromStages(59, stages)).isInstanceOf(BaseException.class);
    }

    @Test
    void unknownDuplicateSelfAndCyclicPredecessorsAreRejected() {
        Map<String, Object> first = stage("FIRST_REVIEW", "ANY", null);
        Map<String, Object> second = stage("SECOND_REVIEW", "ANY", null);
        first.put("predecessors", List.of("SECOND_REVIEW"));
        second.put("predecessors", List.of("FIRST_REVIEW"));
        reject(definition(first, second));
        first.put("predecessors", List.of("UNKNOWN_REVIEW"));
        reject(definition(first, second));
        first.put("predecessors", List.of("FIRST_REVIEW"));
        reject(definition(first));
        first.put("predecessors", List.of("SECOND_REVIEW", "SECOND_REVIEW"));
        reject(definition(first, second));
        reject(definition(stage("FIRST_REVIEW", "ANY", null), stage("FIRST_REVIEW", "ALL", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"any", "SEQUENTIAL", "MAJORITY", "SCRIPT", "<script>"})
    void modeVocabularyIsExactAndScriptsAreNeverEvaluated(String mode) { reject(definition(stage("FIRST_REVIEW", mode, null))); }

    @Test
    void strictPropertiesRejectLooseStringsUnknownArgumentsAndExtraneousOperations() {
        Map<String, Object> first = stage("FIRST_REVIEW", "ANY", null);
        first.put("expression", "approve()");
        reject(definition(first));
        first = stage("FIRST_REVIEW", "COUNT", 2);
        first.put("quorum", Map.of("mode", "COUNT", "value", "2"));
        reject(definition(first));
        first.put("quorum", Map.of("mode", "ALL", "value", 2));
        reject(definition(first));
        first.put("quorum", Map.of("mode", "PERCENT", "value", 2.5));
        reject(definition(first));
        first.put("quorum", Map.of("mode", "COUNT", "value", Double.NaN));
        reject(definition(first));
        first.put("quorum", Map.of("mode", "ANY", "url", "http://localhost/internal"));
        reject(definition(first));
        Map<String, Object> map = definition(stage("FIRST_REVIEW", "ANY", null));
        map.put("schemaContract", "UNKNOWN");
        reject(map);
    }

    @Test
    void duplicateJsonPropertiesAndTrailingTokensCannotChangeBoundDefinitionMeaning() {
        String json = ApprovalWorkflowQuorumDefinition.compile(definition(stage("FIRST_REVIEW", "ANY", null))).canonicalJson();
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.compile(json + " true")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.compile(json.replace("\"mode\":\"ANY\"", "\"mode\":\"ANY\",\"mode\":\"ALL\"")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.compile(" ".repeat(131073))).isInstanceOf(BaseException.class);
    }

    @Test
    void graphAndSlaBoundsAreValidatedWithoutRaisingSourceSizeBaselines() {
        List<Stage> many = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> new Stage("REVIEW_" + index, "Review", "APPROVAL_OPERATOR", new Rule(Mode.ANY, null), 15, List.of())).toList();
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.fromStages(30, many)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.fromStages(0, many.subList(0, 1))).isInstanceOf(BaseException.class);
        Map<String, Object> first = stage("FIRST_REVIEW", "ANY", null);
        first.put("slaMinutes", 0);
        reject(definition(first));
    }

    @SafeVarargs
    static Map<String, Object> definition(Map<String, Object>... stages) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Map<String, Object> stage : stages) values.add(stage);
        return new LinkedHashMap<>(Map.of("schemaContract", CONTRACT, "schemaVersion", 2, "slaMinutes", 30, "stages", values));
    }
    static Map<String, Object> stage(String key, String mode, Integer count) {
        return new LinkedHashMap<>(Map.of("key", key, "name", "Review", "candidateRole", "APPROVAL_OPERATOR",
                "quorum", count == null ? Map.of("mode", mode) : Map.of("mode", mode, "value", count),
                "slaMinutes", 15, "predecessors", List.of()));
    }
    private void reject(Map<String, Object> definition) {
        assertThatThrownBy(() -> ApprovalWorkflowQuorumDefinition.compile(definition)).isInstanceOf(BaseException.class);
    }
}
