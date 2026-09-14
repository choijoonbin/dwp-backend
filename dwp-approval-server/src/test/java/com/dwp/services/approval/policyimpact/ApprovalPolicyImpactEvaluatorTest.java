package com.dwp.services.approval.policyimpact;

import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.exception.BaseException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalPolicyImpactEvaluatorTest {
    private final ApprovalPolicyImpactRuntimePort runtime = mock(ApprovalPolicyImpactRuntimePort.class);
    private final ApprovalPolicyImpactEvaluator evaluator = new ApprovalPolicyImpactEvaluator(runtime);
    static Rules rules(Map<String, Object> rule) { return new Rules("BLOCK", "HIGH", "ACTIVE", rule); }
    static Head head(String key, Rules current, Rules pending) {
        return new Head(UUID.randomUUID(), key, 7, UUID.randomUUID(), 2, current, pending,
                pending == null ? null : 99L, pending == null ? null : Instant.parse("2026-09-14T00:00:00Z"));
    }
    @Test void strictIntegersRejectFractionOverflowNanAndStringsBeforeLegacyValidator() {
        for (Object value : List.of(12.5, 12.0, new BigDecimal("12.01"), 4294967308L, Double.NaN, "12")) {
            assertThatThrownBy(() -> evaluator.validate("REQUIRE_REJECT_REASON", rules(Map.of("minimumLength", value))))
                    .isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(runtime);
    }
    @Test void validClosedRulesDelegateToActualRuntimePort() {
        for (var entry : Map.of("BLOCK_SELF_APPROVAL", Map.<String, Object>of("requesterCannotDecide", true),
                "REQUIRE_REJECT_REASON", Map.<String, Object>of("minimumLength", 12),
                "CAPTURE_DECISION_EVIDENCE", Map.<String, Object>of("retentionClass", "STANDARD"),
                "SLA_ESCALATION", Map.<String, Object>of("warningPercent", 80, "breachPercent", 100)).entrySet()) {
            Rules valid = rules(entry.getValue()); evaluator.validate(entry.getKey(), valid);
            verify(runtime).validate(entry.getKey(), valid);
        }
    }
    @Test void closedShapesAndReversedSlaAreRejectedBeforeDelegation() {
        assertThatThrownBy(() -> evaluator.validate("SLA_ESCALATION", rules(Map.of("warningPercent", 90, "breachPercent", 80))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> evaluator.validate("REQUIRE_REJECT_REASON", rules(Map.of("minimumLength", 12, "script", "allow"))))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(runtime);
    }
    @Test void semanticDiffPreservesDeletedKeysInsteadOfFallingBackToCurrent() {
        Head source = head("REQUIRE_REJECT_REASON", rules(Map.of("minimumLength", 12, "old", true)), rules(Map.of("minimumLength", 20, "new", true)));
        assertThat(evaluator.diff(source)).contains(new Diff("rule.old", "REMOVE", true, null),
                new Diff("rule.new", "ADD", null, true), new Diff("rule.minimumLength", "CHANGE", 12, 20));
    }
    @Test void selfRuleNeverClaimsRequesterRightsRestoredAndPinEffectsRemainSeparate() {
        var pending = new Rules("MONITOR", "LOW", "DISABLED", Map.of("requesterCannotDecide", true));
        Effect effect = evaluator.effect(head("BLOCK_SELF_APPROVAL", rules(pending.rule()), pending), true,
                new RuntimeDifference(true, true, false, true, 1, 2, "a".repeat(64), "b".repeat(64)));
        assertThat(effect.reasons()).contains("OWNER_SOD_CANNOT_BE_OVERRIDDEN",
                "FROZEN_PIN_ALREADY_DIFFERS_FROM_LIVE_PENDING_SAVE_VERSION", "PUBLICATION_CAS_INVALIDATES_QUORUM_PINS");
        assertThat(effect.pinConflict()).isTrue();
    }
    @Test void metadataOnlyChangeDoesNotBecomeAChangedDecisionConstraint() {
        Rules original = rules(Map.of("minimumLength", 12));
        Rules pending = new Rules("BLOCK", "LOW", "ACTIVE", original.rule());
        when(runtime.legacyRejectMinimum(original)).thenReturn(12); when(runtime.legacyRejectMinimum(pending)).thenReturn(12);
        assertThat(evaluator.effect(head("REQUIRE_REJECT_REASON", original, pending), false, null).constraintChanged()).isFalse();
        assertThat(evaluator.effect(head("REQUIRE_REJECT_REASON", original, pending), false, null).reasons()).contains("SEVERITY_METADATA_CHANGED");
    }
    @Test void retentionAndLegacySlaTruthfullyIdentifyMissingRuntimeConsumers() {
        var retention = head("CAPTURE_DECISION_EVIDENCE", rules(Map.of("retentionClass", "STANDARD")), rules(Map.of("retentionClass", "EXTENDED")));
        assertThat(evaluator.effect(retention, true, null).reasons()).contains("AUDIT_RETENTION_CONFIGURATION_NOT_CONSUMED");
        var sla = head("SLA_ESCALATION", rules(Map.of("warningPercent", 80, "breachPercent", 100)), rules(Map.of("warningPercent", 50, "breachPercent", 90)));
        assertThat(evaluator.effect(sla, false, null).constraintChanged()).isFalse();
        assertThat(evaluator.effect(sla, true, null).reasons()).contains("EXISTING_TIMERS_REMAIN_FROZEN", "FUTURE_SLA_THRESHOLDS_CHANGED");
    }
    @Test void absentPendingIsNotAnInventedProposal() {
        var source = head("BLOCK_SELF_APPROVAL", rules(Map.of("requesterCannotDecide", true)), null);
        assertThat(evaluator.diff(source)).isEmpty();
        assertThat(evaluator.effect(source, false, null).reasons()).containsExactly("NO_PROPOSAL");
    }
    @Test void invalidMetadataIsRejectedBeforeSetLookupOrDelegation() {
        for (Rules value : List.of(new Rules(null, "HIGH", "ACTIVE", Map.of("minimumLength", 12)),
                new Rules("BLOCK", null, "ACTIVE", Map.of("minimumLength", 12)),
                new Rules("BLOCK", "HIGH", null, Map.of("minimumLength", 12))))
            assertThatThrownBy(() -> evaluator.validate("REQUIRE_REJECT_REASON", value)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> evaluator.validate(null, rules(Map.of("minimumLength", 12)))).isInstanceOf(BaseException.class);
        verifyNoInteractions(runtime);
    }
    @Test void failedQuorumEvaluationsConsumeBudgetAndAreNotRetriedForTasks() {
        var repository = mock(ApprovalPolicyImpactRepository.class);
        var selected = head("REQUIRE_REJECT_REASON", rules(Map.of("minimumLength", 12)), rules(Map.of("minimumLength", 20)));
        var rows = java.util.stream.IntStream.range(0, 101).mapToObj(index ->
                new Row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "typed")).toList();
        when(repository.head(any(), eq(selected.policyId()))).thenReturn(selected);
        when(repository.now()).thenReturn(ApprovalPolicyImpactAuthorityTest.NOW);
        when(repository.scan(any(), eq(ApprovalPolicyImpactRepository.Kind.WORKFLOW))).thenReturn(new Page(List.of(), false));
        when(repository.scan(any(), eq(ApprovalPolicyImpactRepository.Kind.REQUEST))).thenReturn(new Page(rows, false));
        when(repository.scan(any(), eq(ApprovalPolicyImpactRepository.Kind.TASK))).thenReturn(new Page(List.of(rows.getFirst()), false));
        when(runtime.digest(any())).thenReturn("a".repeat(64));
        when(runtime.typed("typed")).thenReturn(true);
        when(runtime.quorum(eq(42L), any(), eq(selected))).thenThrow(new IllegalStateException("Unavailable stored runtime"));
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new org.springframework.transaction.support.SimpleTransactionStatus());
        var scope = ApprovalPolicyImpactAuthorityTest.window("RS_TEAM_A", ApprovalPolicyImpactAuthorityTest.grants("RS_TEAM_A"));
        var guard = new ApprovalPolicyImpactAuthority(() -> scope,
                java.time.Clock.fixed(ApprovalPolicyImpactAuthorityTest.NOW, java.time.ZoneOffset.UTC));
        Result result = new ApprovalPolicyImpactFacade(repository, guard, runtime,
                new org.springframework.transaction.support.TransactionTemplate(manager)).preview(selected.policyId(), selected.rowVersion());
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.requests().counts().unknown()).isEqualTo(101);
        assertThat(result.tasks().counts().unknown()).isEqualTo(1);
        assertThat(result.requests().counts().countKind()).isEqualTo("OBSERVED_LOWER_BOUND");
        verify(runtime, times(100)).quorum(eq(42L), any(), eq(selected));
        verify(runtime, times(1)).quorum(42, rows.getFirst().requestId(), selected);
    }
}
