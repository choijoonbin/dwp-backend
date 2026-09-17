package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionAdmissionRepository.RuleCandidate;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContextKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionAdmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private NotificationAttentionAdmissionRepository rules;
    private NotificationEffectivePolicyRepository policies;
    private NotificationAttentionGovernanceRuntime governanceRuntime;
    private NotificationAttentionAdmissionService service;

    @BeforeEach
    void setUp() {
        rules = mock(NotificationAttentionAdmissionRepository.class);
        policies = mock(NotificationEffectivePolicyRepository.class);
        governanceRuntime = mock(NotificationAttentionGovernanceRuntime.class);
        when(governanceRuntime.resolve(7L)).thenReturn(governance());
        service = new NotificationAttentionAdmissionService(
                rules, policies, governanceRuntime);
    }

    @Test
    void aSpecificResourceFollowWinsOverABroadAppTypeMute() {
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "APP_TYPE", appTypeHash(), "MUTE", 4),
                        candidate(11, "RESOURCE", resourceHash(), "FOLLOW", 2)));
        when(policies.findForTenant(7L)).thenReturn(List.of());

        NotificationAttentionDecision decision = service.evaluate(
                7, Set.of(11L), request(), contract(), NOW).get(11L);

        assertThat(decision.effect()).isEqualTo("FOLLOW");
        assertThat(decision.scopeKind()).isEqualTo("RESOURCE");
    }

    @Test
    void muteWinsWhenRulesHaveTheSameSpecificity() {
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "RESOURCE", resourceHash(), "FOLLOW", 9),
                        candidate(11, "RESOURCE", targetHash(), "MUTE", 1)));
        when(policies.findForTenant(7L)).thenReturn(List.of());

        NotificationAttentionDecision decision = service.evaluate(
                7, Set.of(11L), request(), contract(), NOW).get(11L);

        assertThat(decision.effect()).isEqualTo("MUTE");
    }

    @Test
    void mandatoryPolicyRejectsMuteAndAllowsTheNextEligibleRule() {
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "RESOURCE", resourceHash(), "MUTE", 3),
                        candidate(11, "ACTOR", actorHash(), "PRIORITIZE", 2)));
        when(policies.findForTenant(7L)).thenReturn(List.of(policy(true, false)));

        NotificationAttentionDecision decision = service.evaluate(
                7, Set.of(11L), request(), contract(), NOW).get(11L);

        assertThat(decision.effect()).isEqualTo("PRIORITIZE");
        assertThat(decision.scopeKind()).isEqualTo("ACTOR");
    }

    @Test
    void nonOverridableInAppPolicyRejectsUserMute() {
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "RESOURCE", resourceHash(), "MUTE", 3)));
        when(policies.findForTenant(7L)).thenReturn(List.of(policy(false, false)));

        assertThat(service.evaluate(7, Set.of(11L), request(), contract(), NOW))
                .doesNotContainKey(11L);
    }

    @Test
    void policyResolutionFailureFailsClosedWhenMuteCouldSuppressDelivery() {
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "RESOURCE", resourceHash(), "MUTE", 3)));
        when(policies.findForTenant(7L))
                .thenThrow(new DataAccessResourceFailureException("offline"));

        assertThatThrownBy(() -> service.evaluate(
                7, Set.of(11L), request(), contract(), NOW))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void governanceResolutionFailureFailsClosedBeforeApplyingAUserRule() {
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "ACTOR", actorHash(), "PRIORITIZE", 2)));
        when(governanceRuntime.resolve(7L)).thenThrow(new NotificationException(
                NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        assertThatThrownBy(() -> service.evaluate(
                7, Set.of(11L), request(), contract(), NOW))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void recipientBatchUsesOneRuleLookup() {
        Set<Long> recipients = Set.of(11L, 12L, 13L);
        when(rules.findActive(eq(7L), eq(recipients), any(), eq(NOW)))
                .thenReturn(List.of(
                        candidate(11, "ACTOR", actorHash(), "PRIORITIZE", 1),
                        candidate(12, "APP_TYPE", appTypeHash(), "FOLLOW", 1)));

        Map<Long, NotificationAttentionDecision> decisions = service.evaluate(
                7, recipients, request(), contract(), NOW);

        assertThat(decisions).containsOnlyKeys(11L, 12L);
        verify(rules).findActive(eq(7L), eq(recipients), any(), eq(NOW));
    }

    @Test
    void firstEventResourceContextParticipatesBeforeProjectionExists() {
        String resource = "project:cloud-migration";
        String hash = hash("RESOURCE", resource);
        DirectMaterializationRequest request = request(List.of(
                new MaterializationContext(
                        MaterializationContextKind.PROJECT,
                        resource,
                        null,
                        true)));
        when(rules.findActive(
                eq(7L),
                eq(Set.of(11L)),
                argThat(hashes -> hashes.contains(hash)),
                eq(NOW)))
                .thenReturn(List.of(candidate(11, "RESOURCE", hash, "MUTE", 2)));
        when(policies.findForTenant(7L)).thenReturn(List.of());

        NotificationAttentionDecision decision = service.evaluate(
                7L, Set.of(11L), request, contract(), NOW).get(11L);

        assertThat(decision.effect()).isEqualTo("MUTE");
        assertThat(decision.scopeKind()).isEqualTo("RESOURCE");
    }

    @Test
    void firstEventTopicRuleRequiresThePublishedGovernanceAllowlist() {
        String topic = "security-alert";
        String hash = hash("TOPIC_TOKEN", topic);
        DirectMaterializationRequest request = request(List.of(
                new MaterializationContext(
                        MaterializationContextKind.TOPIC,
                        topic,
                        null,
                        true)));
        when(rules.findActive(eq(7L), eq(Set.of(11L)), any(), eq(NOW)))
                .thenReturn(List.of(candidate(
                        11, "TOPIC_TOKEN", hash, "FOLLOW", 2)));
        when(governanceRuntime.resolve(7L)).thenReturn(governance(List.of()));

        assertThat(service.evaluate(7L, Set.of(11L), request, contract(), NOW))
                .doesNotContainKey(11L);

        when(governanceRuntime.resolve(7L))
                .thenReturn(governance(List.of("#security-alert")));
        assertThat(service.evaluate(7L, Set.of(11L), request, contract(), NOW)
                .get(11L).effect()).isEqualTo("FOLLOW");
    }

    private RuleCandidate candidate(
            long userId,
            String kind,
            String hash,
            String effect,
            long version) {
        return new RuleCandidate(
                userId, UUID.randomUUID(), kind, hash, effect, version, "USER");
    }

    private EffectivePolicy policy(boolean mandatory, boolean userOverridable) {
        return new EffectivePolicy(
                UUID.randomUUID(), 7L, "TYPE", "MESSAGING.DIRECT_MESSAGE", 4,
                mandatory, false, "NONE",
                Map.of("IN_APP", new EffectivePolicyChannel(
                        "IN_APP", true, "IMMEDIATE", userOverridable, null)));
    }

    private EffectiveGovernance governance() {
        return governance(List.of());
    }

    private EffectiveGovernance governance(List<String> topics) {
        return new EffectiveGovernance(
                new Settings(100, 20, 20, topics, true, 10, true),
                false,
                null,
                0L);
    }

    private DirectMaterializationRequest request() {
        return request(List.of());
    }

    private DirectMaterializationRequest request(List<MaterializationContext> contexts) {
        return new DirectMaterializationRequest(
                UUID.randomUUID(), "messaging.message.sent.v1", 1,
                "MESSAGING.DIRECT_MESSAGE", List.of(11L), "conversation:1", "ko-KR",
                "DIRECT_MESSAGE", "user:10", "work-item:1", "room:1",
                NOW, null, false, contexts, Map.of());
    }

    private TemplateContract contract() {
        return new TemplateContract(
                UUID.randomUUID(), 0, UUID.randomUUID(), 0, null,
                "MESSAGING.DIRECT_MESSAGE", "messaging", "NORMAL", "INFORMATIONAL",
                "ko-KR", "title", "preview", "body", Map.of());
    }

    private String appTypeHash() {
        return hash("APP_TYPE", "messaging:MESSAGING.DIRECT_MESSAGE");
    }

    private String actorHash() {
        return hash("ACTOR", "user:10");
    }

    private String resourceHash() {
        return hash("RESOURCE", "work-item:1");
    }

    private String targetHash() {
        return hash("RESOURCE", "room:1");
    }

    private String hash(String kind, String key) {
        return NotificationAttentionScope.canonical(kind, key).hash();
    }
}
