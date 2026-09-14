package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.policyimpact.ApprovalPolicyImpactEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalPolicyImpactRuntimeParityTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr15-policyimpact-parity");
    @Test void strictAdapterClosesActualLegacyFractionAndOverflowTruncationDefect() {
        var named = mock(NamedParameterJdbcTemplate.class); var mapper = new ObjectMapper();
        var legacy = new ApprovalCommandRepository(named, mapper);
        var evaluator = new ApprovalPolicyImpactEvaluator(new ApprovalPolicyImpactRuntimeBridge(named, mapper));
        for (Object unsafe : List.of(12.5, 12.0, 4294967308L)) {
            assertThatThrownBy(() -> legacy.validatePolicyRule("REQUIRE_REJECT_REASON", Map.of("minimumLength", unsafe)))
                    .isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> evaluator.validate("REQUIRE_REJECT_REASON", rules(Map.of("minimumLength", unsafe))))
                    .isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(named);
    }
    @Test void adapterUsesActualLegacyBlockModesAndCanonicalRuntimeCodec() {
        var bridge = new ApprovalPolicyImpactRuntimeBridge(mock(NamedParameterJdbcTemplate.class), new ObjectMapper());
        for (String mode : List.of("BLOCK", "WARN", "MONITOR")) {
            Rules value = new Rules(mode, "HIGH", "ACTIVE", Map.of("minimumLength", 12));
            bridge.validate("REQUIRE_REJECT_REASON", value);
            assertThat(bridge.legacyRejectMinimum(value)).isEqualTo(mode.equals("BLOCK") ? 12 : 0);
        }
        Map<String, Object> value = Map.of("references", List.of(Map.of("rowVersion", 0, "rule", Map.of("minimumLength", 12))));
        assertThat(bridge.digest(value)).isEqualTo(ApprovalFormSchemaV2Canonical.sha256(
                ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(value))));
    }
    @Test void pendingSaveAndPublicationPinEffectsDoNotReplayVotesOrRescheduleTimers() {
        var f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(PG);
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(new ApprovalWorkflowQuorumDefinition.Stage(
                "REVIEW", "Review", "FINANCE_REVIEWER", new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ALL, null), 60, List.of())));
        f.start(definition);
        f.runtime.vote(f.command("REVIEW", 101, 101, ApprovalWorkflowQuorum.Decision.APPROVE));
        var named = new NamedParameterJdbcTemplate(f.jdbc); var bridge = new ApprovalPolicyImpactRuntimeBridge(named, new ObjectMapper());
        UUID policy = f.jdbc.queryForObject("SELECT policy_id FROM apr_policy_rules WHERE tenant_id=42 AND policy_key='REQUIRE_REJECT_REASON'", UUID.class);
        var store = new ApprovalWorkflowQuorumRuntimeStore(named, new ObjectMapper());
        var before = store.policy(42, f.request, false);
        f.jdbc.update("UPDATE apr_policy_rules SET pending_rule_payload='{\"minimumLength\":20}'::jsonb,"
                + "pending_by=99,pending_at=now(),pending_enforcement_mode='BLOCK',pending_severity='HIGH',pending_lifecycle_state='ACTIVE',version=version+1 WHERE policy_id=?", policy);
        long cas = f.jdbc.queryForObject("SELECT version FROM apr_policy_rules WHERE policy_id=?", Long.class, policy);
        var head = new Head(policy, "REQUIRE_REJECT_REASON", cas, UUID.randomUUID(), 1,
                rules(Map.of("minimumLength", before.rejectLength())), rules(Map.of("minimumLength", 20)), 99L, Instant.now());
        String votes = state(f, "apr_quorum_votes"), timers = state(f, "apr_quorum_sla_timers"), audit = state(f, "sys_audit_outbox");
        var result = bridge.quorum(42, f.request, head);
        assertThat(result.currentFrozenPinConflict()).isTrue(); assertThat(result.publishInvalidatesPins()).isTrue();
        assertThat(result.constraintChanged()).isTrue(); assertThat(result.immutableStages()).isEqualTo(1); assertThat(result.immutableTimers()).isEqualTo(2);
        assertThat(result.currentPolicySha256()).isNotEqualTo(before.sha256()); assertThat(result.proposedPolicySha256()).isNotEqualTo(result.currentPolicySha256());
        assertThat(state(f, "apr_quorum_votes")).isEqualTo(votes); assertThat(state(f, "apr_quorum_sla_timers")).isEqualTo(timers);
        assertThat(state(f, "sys_audit_outbox")).isEqualTo(audit);
    }
    private static Rules rules(Map<String, Object> value) { return new Rules("BLOCK", "HIGH", "ACTIVE", value); }
    private String state(ApprovalWorkflowQuorumPostgresFixture f, String table) {
        return f.jdbc.queryForObject("SELECT md5(coalesce(string_agg(to_jsonb(x)::text,'' ORDER BY to_jsonb(x)::text),'')) FROM " + table + " x", String.class);
    }
}
