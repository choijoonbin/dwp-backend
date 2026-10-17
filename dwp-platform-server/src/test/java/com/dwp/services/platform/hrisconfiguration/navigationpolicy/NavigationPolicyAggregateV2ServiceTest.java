package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.RuntimeContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NavigationPolicyAggregateV2ServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T00:00:00Z");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-000000000042");
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private NavigationPolicyAggregateV2Repository repository;
    private NavigationPolicyEligibilityProvider eligibility;
    private NavigationPolicyRolloutProvider rollout;
    private LegalEntityContextResolver legalResolver;
    private NavigationPolicyAggregateV2Service service;

    @BeforeEach
    void setUp() {
        repository = mock(NavigationPolicyAggregateV2Repository.class);
        eligibility = mock(NavigationPolicyEligibilityProvider.class);
        rollout = mock(NavigationPolicyRolloutProvider.class);
        legalResolver = mock(LegalEntityContextResolver.class);
        when(legalResolver.resolve(any())).thenReturn(LEGAL_ENTITY);
        when(rollout.enrollment(42)).thenReturn(
                new NavigationPolicyRolloutProvider.RuntimeEnrollment(
                        true, RolloutMode.ENFORCED));
        service = service(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void enforcedProjectionUsesExactSixWayIntersectionAndOneAggregatePin() {
        AggregatePolicyDocument tenantPolicy =
                NavigationPolicyAggregateV2CanonicalTest.policy(ScopeKind.TENANT, null);
        AggregatePolicyDocument overlayPolicy =
                NavigationPolicyAggregateV2CanonicalTest.disableNode(
                        NavigationPolicyAggregateV2CanonicalTest.policy(
                                ScopeKind.LEGAL_ENTITY, LEGAL_ENTITY),
                        "BASE:hr-people");
        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.of(published(tenantPolicy, 2, "100")));
        when(repository.findPublished(42, ScopeKind.LEGAL_ENTITY, LEGAL_ENTITY))
                .thenReturn(Optional.of(published(overlayPolicy, 7, "200")));
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of(
                "BASE:my-employment-documents", "BASE:hr-people",
                "BASE:hr-employment-assignments"));
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of(
                "BASE:hr-people", "BASE:hr-employment-assignments"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:hr-people", List.of("/hr/operations/people"),
                "BASE:hr-employment-assignments",
                List.of("/hr/operations/people/employment-assignments")));

        EffectiveNavigation result = service.projection(runtime(LEGAL_ENTITY));

        assertThat(result.enforcementState()).isEqualTo(EnforcementState.ENFORCED);
        assertThat(result.tenantRevisionNumber()).isEqualTo(2);
        assertThat(result.legalEntityRevisionNumber()).isEqualTo(7);
        assertThat(result.generatedIaSha256()).isEqualTo(
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256);
        assertThat(result.revisionPin()).matches("^nav-v2-[a-f0-9]{64}$");
        assertThat(effectiveNodes(result))
                .containsExactly("BASE:hr-employment-assignments");
        assertThat(findNode(result, "BASE:hr-employment-assignments").routeAliases())
                .containsExactly("/hr/operations/people/employment-assignments");
        ArgumentCaptor<LegalEntityContextResolver.OwnerRequest> owner =
                ArgumentCaptor.forClass(LegalEntityContextResolver.OwnerRequest.class);
        verify(legalResolver).resolve(owner.capture());
        assertThat(owner.getValue().contextSurface())
                .isEqualTo(LegalEntityContextResolver.ContextSurface.OPERATIONS);
        assertThat(owner.getValue().contextScopeKey()).isEqualTo(scopeKey());
    }

    @Test
    void preservesCanonicalOrdersGapsAndFallsBackToFirstEffectiveDescendant() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42))
                .thenReturn(Set.of("BASE:my-employment-documents"));
        when(eligibility.deployedNodeIds(42))
                .thenReturn(Set.of("BASE:my-employment-documents"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:my-employment-documents",
                List.of("/hr/me/employment-documents")));

        EffectiveNavigation result = service.projection(runtime(null));

        assertThat(result.workbenches()).singleElement().satisfies(workbench -> {
            assertThat(workbench.workbenchId()).isEqualTo("HRIS-WB-MY-HR");
            assertThat(workbench.displayOrder()).isEqualTo(1);
            assertThat(workbench.landingNodeId())
                    .isEqualTo("BASE:my-employment-documents");
            assertThat(workbench.groups()).singleElement().satisfies(group -> {
                assertThat(group.groupId()).isEqualTo("MY-02");
                assertThat(group.displayOrder()).isEqualTo(1);
                assertThat(group.defaultNodeId())
                        .isEqualTo("BASE:my-employment-documents");
                assertThat(group.nodes().getFirst().displayOrder()).isZero();
                assertThat(group.nodes().getFirst().routeAliases())
                        .containsExactly("/hr/me/employment-documents");
            });
        });
    }

    @Test
    void clientListReorderingCannotChangeServerOwnedProjectionOrRouteOrder() {
        AggregatePolicyDocument canonical =
                NavigationPolicyAggregateV2CanonicalTest.policy(ScopeKind.TENANT, null);
        List<WorkbenchPolicy> reordered = canonical.workbenches().stream()
                .map(workbench -> {
                    List<String> groups = new ArrayList<>(workbench.enabledGroupIds());
                    List<String> nodes = new ArrayList<>(workbench.enabledNodeIds());
                    Collections.reverse(groups);
                    Collections.reverse(nodes);
                    return new WorkbenchPolicy(
                            workbench.workbenchId(), workbench.enabled(), groups, nodes);
                }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(reordered);
        AggregatePolicyDocument input = new AggregatePolicyDocument(
                canonical.schemaVersion(), canonical.scopeKind(),
                canonical.legalEntityPublicId(), canonical.rolloutMode(), reordered);
        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.of(published(input, 1, "100")));
        when(eligibility.installedNodeIds(42)).thenReturn(implementationNodes());
        when(eligibility.deployedNodeIds(42)).thenReturn(implementationNodes());
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(auditedPaths());

        EffectiveNavigation result = service.projection(runtime(null));

        assertThat(result.workbenches())
                .extracting(EffectiveWorkbench::workbenchId)
                .containsExactly(
                        "HRIS-WB-MY-HR", "HRIS-WB-HR-OPERATIONS", "HRIS-WB-TIME",
                        "HRIS-WB-PERFORMANCE", "HRIS-WB-SETTINGS");
        assertThat(result.workbenches().get(2).groups())
                .extracting(EffectiveGroup::groupId)
                .containsExactly("TIME-03", "TIME-04");
        assertThat(result.workbenches().get(2).groups().getFirst().nodes())
                .extracting(EffectiveNode::nodeId)
                .containsExactly("BASE:time-clock-ingestion", "BASE:time-interpretation");
        assertThat(findNode(result, "BASE:hr-employment-assignments").routeAliases())
                .containsExactly(
                        "/hr/operations/people/employment-assignments",
                        "/hr/operations/assignments");
    }

    @Test
    void notEnrolledStillUsesExactImplementationIntersectionWithoutPolicyLookup() {
        when(rollout.enrollment(42)).thenReturn(
                new NavigationPolicyRolloutProvider.RuntimeEnrollment(
                        false, RolloutMode.OFF));
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of(
                "BASE:my-employment-documents", "BASE:my-time"));
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of("BASE:my-time"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:my-employment-documents",
                List.of("/hr/me/employment-documents"),
                "BASE:my-time", List.of("/hr/time")));

        EffectiveNavigation result = service.projection(runtime(LEGAL_ENTITY));

        assertThat(result.enforcementState()).isEqualTo(EnforcementState.NOT_ENROLLED);
        assertThat(result.workbenches()).isEmpty();
        verify(repository, never()).findPublished(anyLong(), any(), any());
        verify(legalResolver, never()).resolve(any());
    }

    @Test
    void configuredPolicyOrOwnerMismatchNodesCannotEscapeImplementationAllowlist() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of(
                "BASE:my-time", "BASE:team-time", "BASE:pay-command-center"));
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of(
                "BASE:my-time", "BASE:team-time", "BASE:pay-command-center"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:my-time", List.of("/hr/time"),
                "BASE:team-time", List.of("/hr/team/time"),
                "BASE:pay-command-center", List.of("/hr/operations/pay")));

        EffectiveNavigation result = service.projection(runtime(null));

        assertThat(result.workbenches()).isEmpty();
    }

    @Test
    void v3PolicyBridgeIntersectsInstallationDeploymentAndTenantWithoutChangingV2Bytes()
            throws Exception {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of(
                "BASE:my-employment-documents", "BASE:my-hr-profile", "BASE:my-time"));
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of(
                "BASE:my-employment-documents", "BASE:my-hr-profile"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:my-employment-documents", List.of("/hr/me/employment-documents"),
                // A POLICY alias from a forged PAGE provider remains excluded from public V2.
                "BASE:my-hr-profile", List.of("/hr/me")));

        EffectiveNavigation before = service.projection(runtime(null));
        EffectiveNavigation bridged = service.projectionWithPolicyPaths(
                runtime(null), Map.of(
                        "BASE:my-hr-profile", List.of("/hr/me"),
                        "BASE:my-time", List.of("/hr/time")));
        EffectiveNavigation after = service.projection(runtime(null));

        assertThat(effectiveNodes(before))
                .containsExactly("BASE:my-employment-documents");
        assertThat(effectiveNodes(bridged))
                .containsExactly("BASE:my-hr-profile", "BASE:my-employment-documents");
        assertThat(effectiveNodes(bridged)).doesNotContain("BASE:my-time");
        assertThat(mapper.writeValueAsBytes(after))
                .containsExactly(mapper.writeValueAsBytes(before));
    }

    @Test
    void enforcedStaleContextDeniesBeforeOwnerOrRepositoryAccess() {
        EffectiveNavigation result = service.projection(new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                scopeKey(), LEGAL_ENTITY, NOW.minusSeconds(1)));

        assertThat(result.enforcementState())
                .isEqualTo(EnforcementState.DENIED_STALE_CONTEXT);
        assertThat(result.workbenches()).isEmpty();
        verify(repository, never()).findPublished(anyLong(), any(), any());
        verify(legalResolver, never()).resolve(any());
    }

    @Test
    void enforcedCompletionTimeRevalidationClosesToctouWindow() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42)).thenReturn(implementationNodes());
        when(eligibility.deployedNodeIds(42)).thenReturn(implementationNodes());
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(auditedPaths());
        service = service(new AdvancingClock(
                NOW.toInstant(), NOW.toInstant(), NOW.plusMinutes(2).toInstant()));

        EffectiveNavigation result = service.projection(new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                scopeKey(), null, NOW.plusMinutes(1)));

        assertThat(result.enforcementState())
                .isEqualTo(EnforcementState.DENIED_STALE_CONTEXT);
        assertThat(result.denialReason())
                .isEqualTo("TRUSTED_CONTEXT_STALE_AT_COMPLETION");
        assertThat(result.workbenches()).isEmpty();
    }

    @Test
    void invalidDigestAndMissingEnforcedBaselineFailClosed() {
        AggregatePolicyDocument policy =
                NavigationPolicyAggregateV2CanonicalTest.policy(ScopeKind.TENANT, null);
        PublishedAggregate corrupt = new PublishedAggregate(
                UUID.randomUUID(), UUID.randomUUID(), 1, policy, "f".repeat(64),
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                NOW.minusDays(1));
        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.of(corrupt));
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of());
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of());
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of());

        EffectiveNavigation corruptResult = service.projection(runtime(null));
        assertThat(corruptResult.enforcementState())
                .isEqualTo(EnforcementState.DENIED_MISSING_POLICY);
        assertThat(corruptResult.workbenches()).isEmpty();

        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.empty());
        EffectiveNavigation missing = service.projection(runtime(null));
        assertThat(missing.enforcementState())
                .isEqualTo(EnforcementState.DENIED_MISSING_POLICY);
        assertThat(missing.workbenches()).isEmpty();
    }

    @Test
    void selectedLegalEntityMissingOverlayFailsClosedWhenEnforced() {
        stubTenantPolicy();
        when(repository.findPublished(42, ScopeKind.LEGAL_ENTITY, LEGAL_ENTITY))
                .thenReturn(Optional.empty());
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of());
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of());
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of());

        EffectiveNavigation result = service.projection(runtime(LEGAL_ENTITY));

        assertThat(result.enforcementState())
                .isEqualTo(EnforcementState.DENIED_MISSING_POLICY);
        assertThat(result.denialReason())
                .isEqualTo("LEGAL_ENTITY_AGGREGATE_OVERLAY_MISSING");
        assertThat(result.workbenches()).isEmpty();
    }

    @Test
    void forgedOrMismatchedLegalEntityScopeFailsBeforePolicyLookup() {
        when(legalResolver.resolve(any())).thenThrow(new BaseException(
                ErrorCode.FORBIDDEN, "HRIS_NAV_LEGAL_ENTITY_SELECTED_SCOPE_STALE"));
        when(eligibility.installedNodeIds(42)).thenReturn(Set.of());
        when(eligibility.deployedNodeIds(42)).thenReturn(Set.of());
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.projection(runtime(LEGAL_ENTITY)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SELECTED_SCOPE_STALE");
        verify(repository, never()).findPublished(anyLong(), any(), any());

        org.mockito.Mockito.doReturn(UUID.fromString(
                        "00000000-0000-0000-0000-000000000999"))
                .when(legalResolver).resolve(any());
        assertThatThrownBy(() -> service.projection(runtime(LEGAL_ENTITY)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("LEGAL_ENTITY_SCOPE_MISMATCH");
        verify(repository, never()).findPublished(anyLong(), any(), any());
    }

    @Test
    void deepLinkUsesPinnedExactPathsAndPreservesCanonicalRouteForLegacyAlias() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-employment-assignments"));
        when(eligibility.deployedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-employment-assignments"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:hr-employment-assignments", List.of(
                        "/hr/operations/people/employment-assignments",
                        "/hr/operations/assignments")));
        when(eligibility.pageAuthorizedPath(any(), any(), any())).thenReturn(true);
        String pin = service.projection(runtime(null)).revisionPin();

        DeepLinkDecision canonical = service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/operations/people/employment-assignments", pin);
        DeepLinkDecision legacy = service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/operations/assignments", pin);
        assertThat(canonical.allowed()).isTrue();
        assertThat(legacy.allowed()).isTrue();
        assertThat(legacy.canonicalRoute())
                .isEqualTo("/hr/operations/people/employment-assignments");
        assertThat(service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/operations/people/employment-assignments", "wrong").allowed())
                .isFalse();
        assertThat(service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/operations", pin).denialReason())
                .isEqualTo("ROUTE_NOT_ADMITTED");
        assertThat(service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/explore", pin).denialReason()).isEqualTo("PATH_EXCLUDED");

        when(eligibility.pageAuthorizedPath(
                org.mockito.ArgumentMatchers.eq("BASE:hr-employment-assignments"),
                org.mockito.ArgumentMatchers.eq("/hr/operations/assignments"), any()))
                .thenReturn(false);
        assertThat(service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/operations/assignments", pin).denialReason())
                .isEqualTo("EXACT_PAGE_ROUTE_DENIED");
    }

    @Test
    void revisionPinChangesWhenExactAdmittedPathSetChanges() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-employment-assignments"));
        when(eligibility.deployedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-employment-assignments"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(
                Map.of("BASE:hr-employment-assignments",
                        List.of("/hr/operations/people/employment-assignments")),
                Map.of("BASE:hr-employment-assignments", List.of(
                        "/hr/operations/people/employment-assignments",
                        "/hr/operations/assignments")));

        String canonicalOnly = service.projection(runtime(null)).revisionPin();
        String canonicalAndLegacy = service.projection(runtime(null)).revisionPin();

        assertThat(canonicalOnly).matches("^nav-v2-[a-f0-9]{64}$");
        assertThat(canonicalAndLegacy).matches("^nav-v2-[a-f0-9]{64}$");
        assertThat(canonicalAndLegacy).isNotEqualTo(canonicalOnly);
    }

    @Test
    void compatibilityOnlyAssignmentReturnsItsPinnedLegacyTarget() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-employment-assignments"));
        when(eligibility.deployedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-employment-assignments"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:hr-employment-assignments",
                List.of("/hr/operations/assignments")));
        when(eligibility.pageAuthorizedPath(any(), any(), any())).thenReturn(true);

        EffectiveNavigation result = service.projection(runtime(null));
        String pin = result.revisionPin();
        DeepLinkDecision decision = service.deepLinkDecision(
                runtime(null), "BASE:hr-employment-assignments",
                "/hr/operations/assignments", pin);

        assertThat(findNode(result, "BASE:hr-employment-assignments").routeAliases())
                .containsExactly("/hr/operations/assignments");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.canonicalRoute())
                .isEqualTo("/hr/operations/assignments");
    }

    @Test
    void organizationDesignReturnsItsOnlyPinnedAdmittedTarget() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-organization-positions"));
        when(eligibility.deployedNodeIds(42))
                .thenReturn(Set.of("BASE:hr-organization-positions"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:hr-organization-positions",
                List.of("/hr/design/organization")));
        when(eligibility.pageAuthorizedPath(any(), any(), any())).thenReturn(true);
        EffectiveNavigation result = service.projection(runtime(null));

        DeepLinkDecision decision = service.deepLinkDecision(
                runtime(null), "BASE:hr-organization-positions",
                "/hr/design/organization", result.revisionPin());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.canonicalRoute()).isEqualTo("/hr/design/organization");
        assertThat(findNode(result, "BASE:hr-organization-positions").routeAliases())
                .contains(decision.canonicalRoute());
    }

    @Test
    void deepLinkRevalidatesAgainAfterPinnedProjectionCompletes() {
        stubTenantPolicy();
        when(eligibility.installedNodeIds(42))
                .thenReturn(Set.of("BASE:my-employment-documents"));
        when(eligibility.deployedNodeIds(42))
                .thenReturn(Set.of("BASE:my-employment-documents"));
        when(eligibility.pageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:my-employment-documents",
                List.of("/hr/me/employment-documents")));
        when(eligibility.pageAuthorizedPath(any(), any(), any())).thenReturn(true);
        service = service(new AdvancingClock(
                NOW.toInstant(), NOW.toInstant(), NOW.toInstant(),
                NOW.toInstant(), NOW.toInstant(), NOW.toInstant(),
                NOW.plusMinutes(2).toInstant()));
        RuntimeContext context = new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                scopeKey(), null, NOW.plusMinutes(1));
        String pin = service.projection(context).revisionPin();

        DeepLinkDecision result = service.deepLinkDecision(
                context, "BASE:my-employment-documents",
                "/hr/me/employment-documents", pin);

        assertThat(result.allowed()).isFalse();
        assertThat(result.denialReason())
                .isEqualTo("TRUSTED_CONTEXT_STALE_AT_COMPLETION");
    }

    private void stubTenantPolicy() {
        AggregatePolicyDocument policy =
                NavigationPolicyAggregateV2CanonicalTest.policy(ScopeKind.TENANT, null);
        when(repository.findPublished(42, ScopeKind.TENANT, null))
                .thenReturn(Optional.of(published(policy, 1, "100")));
    }

    private static Set<String> implementationNodes() {
        return ServerNavigationPolicyEligibilityProvider.IMPLEMENTATION_NODE_IDS;
    }

    private static Map<String, List<String>> auditedPaths() {
        return new ServerNavigationPolicyEligibilityProvider("", "")
                .pageAuthorizedPaths(Set.of(
                        "DATA.HRIS_SELF_PROFILE:VIEW", "DATA.WORKFORCE:VIEW",
                        "DATA.HRIS_WORKFORCE:VIEW", "ACTION.WORKFORCE_ORG_DESIGN:VIEW",
                        "DATA.HR_TIME:VIEW", "DATA.HR_TALENT:VIEW",
                        "DATA.HRIS_COMPENSATION_PLAN:VIEW",
                        "CONFIG.HRIS_WORKBENCH:VIEW"));
    }

    private static List<String> effectiveNodes(EffectiveNavigation navigation) {
        return navigation.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .map(EffectiveNode::nodeId)
                .toList();
    }

    private static EffectiveNode findNode(
            EffectiveNavigation navigation, String nodeId) {
        return navigation.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .filter(node -> node.nodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private NavigationPolicyAggregateV2Service service(Clock clock) {
        return new NavigationPolicyAggregateV2Service(
                repository, legalResolver, eligibility, rollout, mapper, clock);
    }

    private PublishedAggregate published(
            AggregatePolicyDocument policy, long revision, String suffix) {
        return new PublishedAggregate(
                UUID.fromString("00000000-0000-0000-0000-000000000" + suffix),
                UUID.nameUUIDFromBytes(("revision-" + suffix)
                        .getBytes(StandardCharsets.UTF_8)),
                revision, policy, digest(policy),
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                NOW.minusDays(1));
    }

    private String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private RuntimeContext runtime(UUID legalEntity) {
        return new RuntimeContext(
                42, 101, UUID.randomUUID(), "APP.HCM:VIEW", "TENANT_ADMIN",
                scopeKey(), legalEntity, NOW.plusMinutes(10));
    }

    private static String scopeKey() {
        return "hcm-scope-" + "a".repeat(40);
    }

    private static final class AdvancingClock extends Clock {
        private final List<Instant> instants;
        private int index;

        private AdvancingClock(Instant... instants) {
            this.instants = List.of(instants);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant result = instants.get(Math.min(index, instants.size() - 1));
            index++;
            return result;
        }
    }
}
