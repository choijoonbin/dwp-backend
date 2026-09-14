package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumConditionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    @BeforeEach void initialize() { f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES); }

    private ApprovalWorkflowQuorumDefinition.Stage stage(String key, List<String> predecessors, Object threshold) {
        return new ApprovalWorkflowQuorumDefinition.Stage(key, key, "FINANCE_REVIEWER", new Rule(Mode.ANY, null), 15,
                predecessors, threshold == null ? null : Map.of("all", List.of(Map.of("field", "amount", "operator", "GTE", "value", threshold))));
    }

    private ApprovalWorkflowQuorumDefinition branched() {
        return ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("LEFT", List.of(), 10),
                stage("RIGHT", List.of(), 100), stage("JOIN", List.of("LEFT", "RIGHT"), null)));
    }

    @Test void typedDecimalBranchSkipHasNoVotesAndJoinWaitsForActualApproval() {
        var definition = branched(); f.prepare(definition); f.bindTypedForm(definition);
        f.runtime.start(TENANT, f.request, f.pins, definition);
        assertEquals("SKIPPED", f.status("RIGHT"));
        assertEquals("WAITING", f.status("JOIN"));
        assertEquals(3, f.count("apr_quorum_candidates"));
        assertEquals(0, f.count("apr_quorum_votes"));
        f.runtime.vote(f.command("LEFT", 101, 101, Decision.APPROVE));
        assertEquals("IN_PROGRESS", f.status("JOIN"));
        assertEquals("APPROVED", f.runtime.vote(f.command("JOIN", 102, 102, Decision.APPROVE)).requestStatus());
        assertEquals(2, f.count("apr_quorum_votes"));
        assertEquals("SKIPPED", f.status("RIGHT"));
    }

    @Test void readonlySimulationHasSameTypedBranchAndJoinResults() {
        var definition = branched(); f.prepare(definition); f.bindTypedForm(definition);
        f.jdbc.update("UPDATE apr_requests SET status='DRAFT' WHERE request_id=?", f.request);
        var votes = List.of(new ApprovalWorkflowQuorumSimulation.ScenarioVote("LEFT", 101, 101, Decision.APPROVE, ""),
                new ApprovalWorkflowQuorumSimulation.ScenarioVote("JOIN", 102, 102, Decision.APPROVE, ""));
        var result = f.simulation.simulate(TENANT, f.request, new ApprovalWorkflowQuorumSimulation.Input(f.pins,
                REQUESTER, person(REQUESTER), 1, "a".repeat(64), votes));
        assertEquals(ApprovalWorkflowQuorumSimulation.Status.APPROVED, result.status());
        assertEquals(ApprovalWorkflowQuorumSimulation.Status.SKIPPED,
                result.stages().stream().filter(stage -> stage.stageKey().equals("RIGHT")).findFirst().orElseThrow().status());
        assertEquals(0, f.count("apr_steps")); assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(0, f.count("apr_integration_outbox"));
    }

    @Test void allSkippedCannotAutoApproveAndRollsBackStart() {
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("ROOT", List.of(), 100),
                stage("CHILD", List.of("ROOT"), null)));
        f.prepare(definition); f.bindTypedForm(definition);
        assertThrows(BaseException.class, () -> f.runtime.start(TENANT, f.request, f.pins, definition));
        assertEquals(0, f.count("apr_quorum_stage_runtime")); assertEquals(0, f.count("apr_request_events"));
        assertEquals(0, f.count("apr_integration_outbox"));
    }

    @Test void unknownSchemaFieldIsNotFalseBranchOrDefaultAny() {
        var unknown = new ApprovalWorkflowQuorumDefinition.Stage("ROOT", "Root", "FINANCE_REVIEWER", new Rule(Mode.ANY, null),
                15, List.of(), Map.of("all", List.of(Map.of("field", "unknown", "operator", "EQ", "value", "x"))));
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(unknown));
        f.prepare(definition); f.bindTypedForm(definition);
        assertThrows(BaseException.class, () -> f.runtime.start(TENANT, f.request, f.pins, definition));
        assertEquals(0, f.count("apr_steps"));
        f.jdbc.update("UPDATE apr_requests SET status='DRAFT' WHERE request_id=?", f.request);
        var result = f.simulation.simulate(TENANT, f.request, new ApprovalWorkflowQuorumSimulation.Input(f.pins,
                REQUESTER, person(REQUESTER), 1, "a".repeat(64), List.of()));
        assertEquals(ApprovalWorkflowQuorumSimulation.Status.UNKNOWN, result.status());
        assertTrue(result.stages().stream().allMatch(stage -> stage.threshold() == null));
    }

    @Test void canonicalDefinitionBindsConditionsAndCannotAcceptArbitraryNumericTextField() {
        var yes = stage("ROOT", List.of(), 10); var no = stage("ROOT", List.of(), 100);
        assertNotEquals(ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(yes)).sha256(),
                ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(no)).sha256());
        var condition = Map.<String, Object>of("all", List.of(Map.of("field", "summary", "operator", "GTE", "value", 10)));
        var schema = new ApprovalFormSchemaV2Compiler().compile(ApprovalFormSchemaV2CompilerTest.schema(
                ApprovalFormSchemaV2CompilerTest.field("summary", "TEXT")));
        assertThrows(BaseException.class, () -> ApprovalWorkflowStageCondition.matches(schema.canonicalJson(), Map.of("summary", "20"), condition));
    }
}
