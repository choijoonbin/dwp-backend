package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextReference;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlMutationRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlPreviewRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCreateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScopeEvidence;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionTypeTarget;
import com.dwp.services.notification.domain.NotificationAttentionModels.NotificationAttentionContext;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionRuleServiceTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Settings GOVERNANCE_SETTINGS = new Settings(
            75, 5, 8, List.of("#security-alert"), true, 25, true);
    private static final EffectiveGovernance GOVERNANCE = new EffectiveGovernance(
            GOVERNANCE_SETTINGS,
            true,
            UUID.fromString("30000000-0000-0000-0000-000000000001"),
            3L);

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationAttentionRuleRepository rules =
            mock(NotificationAttentionRuleRepository.class);
    private final NotificationAttentionContextRepository contexts =
            mock(NotificationAttentionContextRepository.class);
    private final NotificationAttentionPolicyGuard policy =
            mock(NotificationAttentionPolicyGuard.class);
    private final NotificationAttentionGovernanceRuntime governanceRuntime =
            mock(NotificationAttentionGovernanceRuntime.class);
    private final NotificationAttentionGovernanceLock governanceLock =
            mock(NotificationAttentionGovernanceLock.class);
    private final NotificationAttentionRuleService service = new NotificationAttentionRuleService(
            databaseScope, rules, contexts, policy, governanceRuntime, governanceLock,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void publishedGovernance() {
        when(governanceRuntime.resolve(42L)).thenReturn(GOVERNANCE);
    }

    @Test
    void createsWithImplicitZeroVersionAfterScopeAndPolicyValidation() {
        AttentionRuleCreateRequest request = request("PRIORITIZE");
        AttentionRule result = rule();
        when(contexts.evidence(eq(ACTOR), any())).thenReturn(evidence());
        when(policy.load(ACTOR)).thenReturn(List.of());
        when(policy.evaluate(any(), any(), eq("PRIORITIZE"), eq(Map.of())))
                .thenReturn(AttentionPolicyDecision.allowed());
        when(rules.create(
                eq(ACTOR), any(), eq(request), eq(GOVERNANCE_SETTINGS), eq("create-1")))
                .thenReturn(result);

        assertThat(service.create(ACTOR, request, "create-1")).isSameAs(result);

        verify(databaseScope).applyUser(ACTOR);
        verify(rules).create(
                eq(ACTOR), any(), eq(request), eq(GOVERNANCE_SETTINGS), eq("create-1"));
        InOrder serialized = inOrder(governanceLock, governanceRuntime, rules);
        serialized.verify(governanceLock).lockTenant(42L);
        serialized.verify(governanceRuntime).resolve(42L);
        serialized.verify(rules).create(
                eq(ACTOR), any(), eq(request), eq(GOVERNANCE_SETTINGS), eq("create-1"));
    }

    @Test
    void mandatoryPolicyBlocksBeforeAnyRuleWrite() {
        AttentionRuleCreateRequest request = request("MUTE");
        when(contexts.evidence(eq(ACTOR), any())).thenReturn(evidence());
        when(policy.load(ACTOR)).thenReturn(List.of());
        when(policy.evaluate(any(), any(), eq("MUTE"), eq(Map.of())))
                .thenReturn(new AttentionPolicyDecision(
                        true, "TENANT_POLICY", "MANDATORY_POLICY", "7"));

        assertThatThrownBy(() -> service.create(ACTOR, request, "create-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.ATTENTION_POLICY_LOCKED));
        verify(rules, never()).create(any(), any(), any(), any(Settings.class), any());
    }

    @Test
    void rejectsATopicRuleOutsideThePublishedAllowlistBeforeWriting() {
        AttentionRuleCreateRequest request = new AttentionRuleCreateRequest(
                "TOPIC_TOKEN", "unapproved-topic", "Unapproved", "FOLLOW",
                Map.of(), null, null, null, true);
        when(contexts.evidence(eq(ACTOR), any())).thenReturn(evidence());

        assertThatThrownBy(() -> service.create(ACTOR, request, "create-topic"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.ATTENTION_POLICY_LOCKED));

        verify(rules, never()).create(any(), any(), any(), any(Settings.class), any());
    }

    @Test
    void acceptsATopicRuleFromThePublishedAllowlist() {
        AttentionRuleCreateRequest request = new AttentionRuleCreateRequest(
                "TOPIC_TOKEN", "security-alert", "Security alerts", "FOLLOW",
                Map.of(), null, null, null, true);
        AttentionRule result = rule();
        when(contexts.evidence(eq(ACTOR), any())).thenReturn(evidence());
        when(policy.load(ACTOR)).thenReturn(List.of());
        when(policy.evaluate(any(), any(), eq("FOLLOW"), eq(Map.of())))
                .thenReturn(AttentionPolicyDecision.allowed());
        when(rules.create(
                eq(ACTOR), any(), eq(request), eq(GOVERNANCE_SETTINGS), eq("create-topic")))
                .thenReturn(result);

        assertThat(service.create(ACTOR, request, "create-topic")).isSameAs(result);
    }

    @Test
    void previewsAControlWithoutWritingAndBindsTheOpaqueFingerprint() {
        UUID notificationId = UUID.randomUUID();
        when(contexts.notification(ACTOR, notificationId)).thenReturn(context(notificationId));
        when(rules.list(ACTOR)).thenReturn(List.of());
        when(policy.load(ACTOR)).thenReturn(List.of());
        when(policy.evaluate(any(), any(), eq("FOLLOW"), eq(Map.of())))
                .thenReturn(AttentionPolicyDecision.allowed());

        var preview = service.previewControl(
                ACTOR,
                notificationId,
                new AttentionControlPreviewRequest(
                        "FOLLOW_CONTEXT", "FOLLOW", null));

        assertThat(preview.allowed()).isTrue();
        assertThat(preview.policyLocked()).isFalse();
        assertThat(preview.effectiveEffect()).isEqualTo("FOLLOW");
        assertThat(preview.previewFingerprint()).matches("[a-f0-9]{64}");
        assertThat(preview.currentRuleVersion()).isNull();
        assertThat(preview.asOf()).isEqualTo(NOW);
        verify(databaseScope).applyUser(ACTOR);
        verify(governanceLock, never()).lockTenant(anyLong());
        verify(rules, never()).applyControl(
                any(), any(), any(), any(), any(), anyLong(),
                any(Settings.class), anyBoolean(), any());
    }

    @Test
    void rejectsAnAllowedButStaleGovernancePreviewWithConflict() {
        UUID notificationId = UUID.randomUUID();
        NotificationAttentionContext context = context(notificationId);
        EffectiveGovernance changedGovernance = new EffectiveGovernance(
                GOVERNANCE_SETTINGS,
                true,
                GOVERNANCE.governanceId(),
                GOVERNANCE.revisionNumber() + 1);
        when(contexts.notification(ACTOR, notificationId)).thenReturn(context);
        when(rules.list(ACTOR)).thenReturn(List.of());
        when(policy.load(ACTOR)).thenReturn(List.of());
        when(policy.evaluate(any(), any(), eq("FOLLOW"), eq(Map.of())))
                .thenReturn(AttentionPolicyDecision.allowed());
        when(governanceRuntime.resolve(42L))
                .thenReturn(GOVERNANCE)
                .thenReturn(changedGovernance);
        var preview = service.previewControl(
                ACTOR,
                notificationId,
                new AttentionControlPreviewRequest("FOLLOW_CONTEXT", "FOLLOW", null));
        AttentionControlMutationRequest mutation = new AttentionControlMutationRequest(
                "FOLLOW_CONTEXT", "FOLLOW", null, null, preview.previewFingerprint());
        when(rules.applyControl(
                eq(ACTOR), eq(notificationId), any(), any(), eq(mutation), eq(0L),
                eq(GOVERNANCE_SETTINGS), eq(false), eq("control-stale")))
                .thenThrow(new NotificationException(
                        NotificationErrorCode.ATTENTION_PREVIEW_STALE));

        assertThatThrownBy(() -> service.mutateControl(
                ACTOR, notificationId, mutation, "control-stale"))
                .isInstanceOfSatisfying(NotificationException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(NotificationErrorCode.ATTENTION_PREVIEW_STALE);
                    assertThat(exception.errorCode().status().value()).isEqualTo(409);
                });

        verify(governanceLock).lockTenant(42L);
    }

    @Test
    void currentPolicyLockTakesPrecedenceOverAFormerlyAllowedPreview() {
        UUID notificationId = UUID.randomUUID();
        when(contexts.notification(ACTOR, notificationId)).thenReturn(context(notificationId));
        when(rules.list(ACTOR)).thenReturn(List.of());
        when(policy.load(ACTOR)).thenReturn(List.of());
        when(policy.evaluate(any(), any(), eq("FOLLOW"), eq(Map.of())))
                .thenReturn(AttentionPolicyDecision.allowed())
                .thenReturn(new AttentionPolicyDecision(
                        true, "TENANT_POLICY", "USER_OVERRIDE_DISABLED", "8"));
        var preview = service.previewControl(
                ACTOR,
                notificationId,
                new AttentionControlPreviewRequest("FOLLOW_CONTEXT", "FOLLOW", null));

        assertThatThrownBy(() -> service.mutateControl(
                ACTOR,
                notificationId,
                new AttentionControlMutationRequest(
                        "FOLLOW_CONTEXT", "FOLLOW", null, null,
                        preview.previewFingerprint()),
                "control-locked"))
                .isInstanceOfSatisfying(NotificationException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(NotificationErrorCode.ATTENTION_POLICY_LOCKED);
                    assertThat(exception.errorCode().status().value()).isEqualTo(403);
                });

        verify(rules, never()).applyControl(
                any(), any(), any(), any(), any(), anyLong(),
                any(Settings.class), anyBoolean(), any());
    }

    private AttentionRuleCreateRequest request(String effect) {
        return new AttentionRuleCreateRequest(
                "ACTOR", "person:42", "Leader", effect,
                Map.of(), null, null, null, true);
    }

    private AttentionScopeEvidence evidence() {
        return new AttentionScopeEvidence(
                List.of(new AttentionTypeTarget("approvals", "APPROVAL.ACTION_REQUIRED")),
                3L);
    }

    private AttentionRule rule() {
        return new AttentionRule(
                UUID.randomUUID(), "ACTOR", "person:42", "Leader", "PRIORITIZE",
                Map.of(), null, null, "USER", false, false, true, "1", NOW, NOW);
    }

    private NotificationAttentionContext context(UUID notificationId) {
        return new NotificationAttentionContext(
                notificationId,
                "approvals",
                "APPROVAL.ACTION_REQUIRED",
                "DIRECT",
                List.of(
                        new AttentionContextReference(
                                "APP_TYPE",
                                "approvals:APPROVAL.ACTION_REQUIRED",
                                "Approval request"),
                        new AttentionContextReference(
                                "ACTOR", "person:42", "Requester"),
                        new AttentionContextReference(
                                "THREAD", "thread:approval-42", "Approval thread")));
    }
}
