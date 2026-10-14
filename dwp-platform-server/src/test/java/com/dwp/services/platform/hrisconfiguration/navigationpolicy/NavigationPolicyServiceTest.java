package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

class NavigationPolicyServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T04:00:00Z");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-000000000042");
    private NavigationPolicyRepository repository;
    private NavigationPolicyEligibilityProvider eligibility;
    private NavigationPolicyRolloutProvider rollout;
    private StepUpEvidenceVerifier stepUpVerifier;
    private NavigationPolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(NavigationPolicyRepository.class);
        eligibility = mock(NavigationPolicyEligibilityProvider.class);
        rollout = mock(NavigationPolicyRolloutProvider.class);
        stepUpVerifier = mock(StepUpEvidenceVerifier.class);
        when(rollout.enrollment(42)).thenReturn(
                new NavigationPolicyRolloutProvider.RuntimeEnrollment(true, RolloutMode.ENFORCED));
        when(repository.findReceipt(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(stepUpVerifier.verify(any())).thenReturn(true);
        service = new NavigationPolicyService(
                repository, request -> request.requestedLegalEmployerPublicId(),
                eligibility, rollout,
                stepUpVerifier,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-10-14T04:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void enforcedProjectionAppliesFiveWayIntersectionAndOneRevisionPin() {
        PolicyDocument tenantPolicy = NavigationPolicyCanonicalTest.policy(
                ScopeKind.TENANT, null, false);
        PolicyDocument overlayPolicy = disable(
                NavigationPolicyCanonicalTest.policy(
                        ScopeKind.LEGAL_ENTITY, LEGAL_ENTITY, false),
                "BASE:hr-appointments");
        RevisionView tenant = revision(
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                1, tenantPolicy, 101);
        RevisionView overlay = revision(
                UUID.fromString("00000000-0000-0000-0000-000000000200"),
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                3, overlayPolicy, 102);
        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.of(tenant));
        when(repository.findPublished(42, ScopeKind.LEGAL_ENTITY, LEGAL_ENTITY))
                .thenReturn(Optional.of(overlay));
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of(
                "BASE:hr-operations-home", "BASE:hr-people",
                "BASE:hr-employment-assignments", "BASE:hr-appointments"));
        when(eligibility.pageAuthorizedNodeIds(Set.of("APP.HCM:VIEW")))
                .thenReturn(Set.of("BASE:hr-employment-assignments", "BASE:hr-appointments"));

        EffectiveProjection result = service.projection(
                new RuntimeContext(
                        42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                        "hcm-scope-" + "a".repeat(40), LEGAL_ENTITY,
                        NOW.plusMinutes(5)),
                NavigationPolicyCanonical.WORKBENCH_ID);

        assertThat(result.enforcementState()).isEqualTo(EnforcementState.ENFORCED);
        assertThat(result.tenantRevisionId()).isEqualTo(1);
        assertThat(result.legalEntityRevisionId()).isEqualTo(3);
        assertThat(result.revisionPin()).startsWith("nav-");
        assertThat(result.groups()).flatExtracting(EffectiveGroup::nodes)
                .extracting(EffectiveNode::nodeId)
                .containsExactly("BASE:hr-employment-assignments");
    }

    @Test
    void enrolledEnforcedTenantFailsClosedForStaleOrMissingPolicy() {
        RuntimeContext stale = new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                "hcm-scope-" + "a".repeat(40), null, NOW.minusSeconds(1));
        EffectiveProjection staleResult = service.projection(
                stale, NavigationPolicyCanonical.WORKBENCH_ID);
        assertThat(staleResult.enforcementState())
                .isEqualTo(EnforcementState.DENIED_STALE_CONTEXT);
        assertThat(staleResult.groups()).isEmpty();

        RuntimeContext current = new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                "hcm-scope-" + "a".repeat(40), null, NOW.plusSeconds(30));
        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.empty());
        EffectiveProjection missing = service.projection(
                current, NavigationPolicyCanonical.WORKBENCH_ID);
        assertThat(missing.enforcementState())
                .isEqualTo(EnforcementState.DENIED_MISSING_POLICY);
        assertThat(missing.groups()).isEmpty();
    }

    @Test
    void makerCannotApproveEvenWhenHoldingPublisherGrantAndStepUp() {
        UUID revisionId = UUID.randomUUID();
        RevisionView revision = revision(
                UUID.randomUUID(), revisionId, 1,
                NavigationPolicyCanonicalTest.policy(ScopeKind.TENANT, null, true), 101);
        revision = new RevisionView(
                revision.policyId(), revision.revisionId(), revision.revisionNumber(),
                LifecycleState.PENDING_APPROVAL, revision.rolloutMode(), revision.policy(),
                revision.payloadDigest(), 2, revision.authoredBy(), null,
                null, null, revision.createdAt(), revision.updatedAt());
        when(repository.findByRevisionId(42, revisionId)).thenReturn(Optional.of(revision));
        AccessContext maker = new AccessContext(
                42, 101, UUID.randomUUID(),
                "APP.HCM:VIEW,ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH",
                "TENANT_ADMIN", "hcm-context-7", "hcm-scope-" + "a".repeat(40),
                "decision-7", NOW.plusMinutes(5), PURPOSE);

        assertThatThrownBy(() -> service.approve(
                maker,
                new CommandContext(
                        "approve-own-policy", UUID.randomUUID(),
                        "decision-7", "fresh-step-up", "POST",
                        "/v1/admin/hris/navigation-policies/revisions/" + revisionId
                                + "/approve", routeAuthority(
                        "route.hcm.configuration.navigation-policy-revision-approve.action"),
                        "route.hcm.configuration.navigation-policy-revision-approve.action",
                        "hcm-context-7", "hcm-scope-" + "a".repeat(40),
                        "decision-7", "b".repeat(64), 7, 11),
                revisionId, new LifecycleCommand(
                        2L, "a".repeat(64), "APPROVE_POLICY")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("MAKER_CANNOT_APPROVE");
        verify(repository, never()).transition(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void committedHighRiskRetryReturnsReceiptWithoutConsumingStepUpAgain() {
        UUID policyId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        RevisionView approved = new RevisionView(
                policyId, revisionId, 1, LifecycleState.APPROVED,
                RolloutMode.ENFORCED,
                NavigationPolicyCanonicalTest.policy(ScopeKind.TENANT, null, true),
                "b".repeat(64), 2, 202, 101L,
                null, null, NOW.minusDays(1), NOW);
        RevisionView published = new RevisionView(
                policyId, revisionId, 1, LifecycleState.PUBLISHED,
                approved.rolloutMode(), approved.policy(), approved.payloadDigest(),
                3, approved.authoredBy(), approved.approvedBy(),
                null, null, approved.createdAt(), NOW);
        when(repository.findByRevisionId(42, revisionId))
                .thenReturn(Optional.of(approved), Optional.of(published));
        when(repository.publish(
                anyLong(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(true);
        AccessContext publisher = new AccessContext(
                42, 101, UUID.randomUUID(),
                "APP.HCM:VIEW,ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH",
                "TENANT_ADMIN", "hcm-context-7", "hcm-scope-" + "a".repeat(40),
                "decision-7", NOW.plusMinutes(5), PURPOSE);
        LifecycleCommand body = new LifecycleCommand(
                2L, "a".repeat(64), "PUBLISH_POLICY");
        CommandContext first = new CommandContext(
                "publish-policy-retry", UUID.randomUUID(),
                "decision-7", "fresh-step-up", "POST",
                "/v1/admin/hris/navigation-policies/revisions/" + revisionId
                        + "/publish", routeAuthority(
                "route.hcm.configuration.navigation-policy-revision-publish.action"),
                "route.hcm.configuration.navigation-policy-revision-publish.action",
                "hcm-context-7", "hcm-scope-" + "a".repeat(40),
                "decision-7", "b".repeat(64), 7, 11);

        CommandReceipt committed = service.publish(publisher, first, revisionId, body);

        var digestCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).saveReceipt(
                anyLong(), anyLong(), anyString(), anyString(),
                digestCaptor.capture(), any());
        when(repository.findReceipt(42, 101, "PUBLISH", "publish-policy-retry"))
                .thenReturn(Optional.of(new NavigationPolicyRepository.StoredReceipt(
                        digestCaptor.getValue(), committed)));
        CommandContext retryWithoutReusableProof = new CommandContext(
                first.idempotencyKey(), UUID.randomUUID(),
                first.expectedDecisionRevision(), null, first.commandMethod(),
                first.commandPath(), first.requestAuthority(),
                null, null, null, null, null,
                first.fieldPolicyRevision(), first.authorizationRevision());

        CommandReceipt replayed = service.publish(
                publisher, retryWithoutReusableProof, revisionId, body);

        assertThat(replayed).isEqualTo(committed);
        verify(stepUpVerifier, times(1)).verify(any());
        verify(repository, times(1)).publish(
                anyLong(), any(), anyLong(), anyLong(), any(), any());
    }

    private static RevisionView revision(
            UUID policyId, UUID revisionId, long revisionNumber,
            PolicyDocument policy, long author) {
        return new RevisionView(
                policyId, revisionId, revisionNumber, LifecycleState.PUBLISHED,
                policy.rolloutMode(), policy, "b".repeat(64), 4,
                author, 202L, null, null, NOW.minusDays(1), NOW);
    }

    private static PolicyDocument disable(PolicyDocument policy, String nodeId) {
        List<GroupPolicy> groups = new ArrayList<>();
        for (GroupPolicy group : policy.groups()) {
            List<NodePolicy> nodes = group.nodes().stream()
                    .map(node -> node.nodeId().equals(nodeId)
                            ? new NodePolicy(node.nodeId(), node.displayOrder(), false)
                            : node)
                    .toList();
            String defaultNode = group.defaultNodeId().equals(nodeId)
                    ? nodes.stream().filter(NodePolicy::enabled).findFirst().orElseThrow().nodeId()
                    : group.defaultNodeId();
            groups.add(new GroupPolicy(
                    group.groupId(), group.displayOrder(), group.enabled(), defaultNode, nodes));
        }
        return new PolicyDocument(
                policy.scopeKind(), policy.legalEntityPublicId(), policy.rolloutMode(),
                policy.workbenchId(), groups);
    }

    private static com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority
            routeAuthority(String routeKey) {
        return new com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority(
                routeKey, "ACTION", "POST", "/test", "test-profile", true,
                "test-capability", "ENABLED", null);
    }
}
