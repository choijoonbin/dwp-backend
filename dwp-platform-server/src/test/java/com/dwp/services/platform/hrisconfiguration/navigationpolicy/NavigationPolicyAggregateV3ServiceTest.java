package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV3Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.RuntimeContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NavigationPolicyAggregateV3ServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T00:00:00Z");
    private static final UUID PERSON =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID POLICY =
            UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final UUID REVISION =
            UUID.fromString("00000000-0000-4000-8000-000000000202");
    private static final String CONTEXT_SCOPE = "hcm-scope-" + "a".repeat(40);
    private static final String SELF_SCOPE = ProductSurfaceScopeKey.key(
            42, 101, "hcm", "hcm.personal", "SELF", "SELF");
    private static final String AUTH = "auth-" + "1".repeat(64);
    private static final String POLICY_REVISION = "policy-47-7-"
            + NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM;
    private static final String RELATIONSHIP = "relationship-" + "2".repeat(64);
    private static final String SNAPSHOT = "snapshot-" + "3".repeat(64);
    private static final String DECISION = "decision-" + "4".repeat(64);

    private NavigationPolicyAggregateV2Service v2;
    private NavigationPolicyAuthorityDecisionProvider authority;
    private NavigationPolicyEligibilityProvider eligibility;
    private NavigationPolicyAggregateV3Service service;

    @BeforeEach
    void setUp() {
        v2 = mock(NavigationPolicyAggregateV2Service.class);
        authority = mock(NavigationPolicyAuthorityDecisionProvider.class);
        eligibility = mock(NavigationPolicyEligibilityProvider.class);
        service = service(NOW);
    }

    @Test
    void notEnrolledOffAndShadowReturnStaticV3WithoutAuthorityOrPin() {
        EffectiveNavigation off = navigation(
                EnforcementState.OFF, false, null,
                List.of(node("BASE:my-employment-documents",
                        "/hr/me/employment-documents")), NOW);
        EffectiveNavigation notEnrolled = navigation(
                EnforcementState.NOT_ENROLLED, false, null,
                off.workbenches().getFirst().groups().getFirst().nodes(), NOW);
        EffectiveNavigation shadow = navigation(
                EnforcementState.SHADOW, false, null,
                off.workbenches().getFirst().groups().getFirst().nodes(), NOW);
        when(v2.projection(any())).thenReturn(off, notEnrolled, shadow);

        List<EffectiveNavigationV3> results = List.of(
                service.projection(runtime(), AccessMode.NORMAL),
                service.projection(runtime(), AccessMode.NORMAL),
                service.projection(runtime(), AccessMode.NORMAL));

        assertThat(results).extracting(EffectiveNavigationV3::enforcementState)
                .containsExactly(
                        EnforcementState.OFF,
                        EnforcementState.NOT_ENROLLED,
                        EnforcementState.SHADOW);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.schemaVersion()).isEqualTo("3");
            assertThat(result.policyEnforced()).isFalse();
            assertThat(result.revisionPin()).isNull();
            assertThat(result.navigationContextScopeKey()).isEqualTo(CONTEXT_SCOPE);
            assertThat(result.authoritySelfScopeKey()).isNull();
            assertThat(result.authorityDecisionCount()).isZero();
            assertThat(result.admittedPolicyRoutes()).isEmpty();
            assertThat(result.workbenches()).isNotEmpty();
        });
        verify(authority, never()).evaluate(any());
        verify(v2, never()).projectionWithV47Paths(any(), any(), any());
    }

    @Test
    void enforcedProjectionUsesAtomicSixDecisionSnapshotAndExactEffectiveIntersection() {
        EffectiveNavigation baseline = enforced(List.of(
                node("BASE:my-employment-documents",
                        "/hr/me/employment-documents")), "a", NOW);
        EffectiveNavigation effective = enforced(List.of(
                node("BASE:my-hr-profile", "/hr/me"),
                node("BASE:my-time", "/hr/time"),
                node("BASE:team-leave", "/hr/team/leave", "/hr/team/absence"),
                node("BASE:my-employment-documents",
                        "/hr/me/employment-documents")), "b", NOW);
        when(v2.projection(any())).thenReturn(baseline);
        when(eligibility.v47PageAuthorizedPaths(any())).thenReturn(Map.of(
                "BASE:team-leave", List.of("/hr/team/leave", "/hr/team/absence")));
        when(v2.projectionWithV47Paths(any(), any(), any())).thenReturn(effective);
        when(authority.evaluate(any())).thenReturn(snapshot(Set.of(
                "BASE:my-hr-profile", "BASE:my-time", "BASE:my-leave")));

        EffectiveNavigationV3 result = service.projection(runtime(), AccessMode.NORMAL);

        ArgumentCaptor<AuthorityRequest> request =
                ArgumentCaptor.forClass(AuthorityRequest.class);
        verify(authority).evaluate(request.capture());
        assertThat(request.getValue()).satisfies(value -> {
            assertThat(value.tenantId()).isEqualTo(42);
            assertThat(value.actorId()).isEqualTo(101);
            assertThat(value.expectedPersonPublicId()).isEqualTo(PERSON);
            assertThat(value.expectedScopeKey()).isEqualTo(SELF_SCOPE);
            assertThat(value.expectedRegistryVersion()).isEqualTo(47);
            assertThat(value.expectedRegistryChecksum()).isEqualTo(
                    NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM);
            assertThat(value.expectedDecisionRevision()).isNull();
        });
        assertThat(result.revisionPin()).matches("^nav-v3-[a-f0-9]{64}$");
        assertThat(result.authorityDecisionCount()).isEqualTo(6);
        assertThat(result.runtimeOwnerOverlayRevision())
                .isEqualTo(NavigationPolicyRuntimeOwnerOverlay.REVISION);
        assertThat(result.runtimeOwnerOverlayChecksum())
                .isEqualTo(NavigationPolicyRuntimeOwnerOverlay.CHECKSUM);
        assertThat(result.runtimeOwnerOverlayRecords()).hasSize(4)
                .extracting(RuntimeOwnerOverlayRecord::nodeId)
                .containsExactly(
                        "BASE:team-time", "BASE:team-leave",
                        "BASE:time-leave-operations", "BASE:hr-operations-home");
        assertThat(result.authoritySelfScopeKey()).isEqualTo(SELF_SCOPE);
        assertThat(result.authorityDecisionRevision()).isEqualTo(DECISION);
        assertThat(result.admittedPolicyRoutes())
                .extracting(AdmittedPolicyRoute::nodeId)
                .containsExactly("BASE:my-hr-profile", "BASE:my-time");
        assertThat(result.admittedPolicyRoutes()).allSatisfy(value -> {
            assertThat(value.scopeBinding().key()).isEqualTo(SELF_SCOPE);
            assertThat(value.scopeBinding().personPublicId()).isEqualTo(PERSON);
            assertThat(value.decisionRevision()).isEqualTo(DECISION);
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> paths =
                ArgumentCaptor.forClass(Map.class);
        verify(v2).projectionWithV47Paths(any(), paths.capture(), any());
        assertThat(paths.getValue()).containsExactly(
                org.assertj.core.api.Assertions.entry(
                        "BASE:my-hr-profile", List.of("/hr/me")),
                org.assertj.core.api.Assertions.entry(
                        "BASE:my-time", List.of("/hr/time")),
                org.assertj.core.api.Assertions.entry(
                        "BASE:my-leave", List.of("/hr/absence")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> pagePaths =
                ArgumentCaptor.forClass(Map.class);
        verify(v2).projectionWithV47Paths(any(), any(), pagePaths.capture());
        assertThat(pagePaths.getValue()).containsExactly(
                org.assertj.core.api.Assertions.entry(
                        "BASE:team-leave",
                        List.of("/hr/team/leave", "/hr/team/absence")));
    }

    @Test
    void malformedDuplicateWrongBindingAndStaleSnapshotsFailClosed() {
        stubEnforced();
        AuthoritySnapshot valid = snapshot(Set.of("BASE:my-hr-profile"));
        ArrayList<RouteDecision> duplicate = new ArrayList<>(valid.decisions());
        duplicate.set(5, duplicate.getFirst());
        when(authority.evaluate(any())).thenReturn(new AuthoritySnapshot(
                valid.registryVersion(), valid.registryChecksum(), valid.snapshotRevision(),
                valid.decisionRevision(), valid.authRevision(), valid.policyRevision(),
                valid.relationshipRevision(), valid.freshUntil(), duplicate));
        assertThatThrownBy(() -> service.projection(runtime(), AccessMode.NORMAL))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("DUPLICATE");

        List<RouteDecision> wrongTarget = valid.decisions().stream()
                .map(value -> value.nodeId().equals("BASE:my-hr-profile")
                        ? new RouteDecision(
                        value.nodeId(), value.path(), value.routeContractKey(),
                        value.profileKey(), "TARGET_POPULATION", value.scopeBinding(),
                        value.decision(), value.reasonCode(), value.routeGrantRef(),
                        value.decisionRevision(), value.authRevision(),
                        value.policyRevision(), value.relationshipRevision(),
                        value.freshUntil()) : value)
                .toList();
        when(authority.evaluate(any())).thenReturn(new AuthoritySnapshot(
                valid.registryVersion(), valid.registryChecksum(), valid.snapshotRevision(),
                valid.decisionRevision(), valid.authRevision(), valid.policyRevision(),
                valid.relationshipRevision(), valid.freshUntil(), wrongTarget));
        assertThatThrownBy(() -> service.projection(runtime(), AccessMode.NORMAL))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("DECISION_INVALID");

        when(authority.evaluate(any())).thenReturn(new AuthoritySnapshot(
                valid.registryVersion(), valid.registryChecksum(), valid.snapshotRevision(),
                valid.decisionRevision(), valid.authRevision(), valid.policyRevision(),
                valid.relationshipRevision(), NOW.minusSeconds(1), valid.decisions()));
        assertThatThrownBy(() -> service.projection(runtime(), AccessMode.NORMAL))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SNAPSHOT_INVALID");
    }

    @Test
    void pinExcludesEvaluatedAtButChangesWithExactAuthorityTuple() {
        EffectiveNavigation baseline = enforced(List.of(), "a", NOW);
        EffectiveNavigation effective = enforced(
                List.of(node("BASE:my-hr-profile", "/hr/me")), "b", NOW);
        when(v2.projection(any())).thenReturn(baseline);
        when(v2.projectionWithV47Paths(any(), any(), any())).thenReturn(effective);
        AuthoritySnapshot original = snapshot(Set.of("BASE:my-hr-profile"));
        when(authority.evaluate(any())).thenReturn(original);
        String first = service(NOW).projection(runtime(), AccessMode.NORMAL).revisionPin();
        String laterEvaluation = service(NOW.plusSeconds(1))
                .projection(runtime(), AccessMode.NORMAL).revisionPin();

        List<RouteDecision> changed = original.decisions().stream()
                .map(value -> value.nodeId().equals("BASE:my-hr-profile")
                        ? new RouteDecision(
                        value.nodeId(), value.path(), value.routeContractKey(),
                        value.profileKey(), value.targetBinding(), value.scopeBinding(),
                        value.decision(), value.reasonCode(),
                        "grant-" + "f".repeat(32), value.decisionRevision(),
                        value.authRevision(), value.policyRevision(),
                        value.relationshipRevision(), value.freshUntil()) : value)
                .toList();
        when(authority.evaluate(any())).thenReturn(new AuthoritySnapshot(
                original.registryVersion(), original.registryChecksum(),
                original.snapshotRevision(), original.decisionRevision(),
                original.authRevision(), original.policyRevision(),
                original.relationshipRevision(), original.freshUntil(), changed));
        String changedTuple = service(NOW).projection(
                runtime(), AccessMode.NORMAL).revisionPin();

        OffsetDateTime shiftedFreshness = original.freshUntil().plusSeconds(1);
        List<RouteDecision> freshnessShiftedDecisions = original.decisions().stream()
                .map(value -> new RouteDecision(
                        value.nodeId(), value.path(), value.routeContractKey(),
                        value.profileKey(), value.targetBinding(), value.scopeBinding(),
                        value.decision(), value.reasonCode(), value.routeGrantRef(),
                        value.decisionRevision(), value.authRevision(),
                        value.policyRevision(), value.relationshipRevision(),
                        shiftedFreshness))
                .toList();
        when(authority.evaluate(any())).thenReturn(new AuthoritySnapshot(
                original.registryVersion(), original.registryChecksum(),
                original.snapshotRevision(), original.decisionRevision(),
                original.authRevision(), original.policyRevision(),
                original.relationshipRevision(), shiftedFreshness,
                freshnessShiftedDecisions));
        String changedFreshUntil = service(NOW).projection(
                runtime(), AccessMode.NORMAL).revisionPin();

        assertThat(laterEvaluation).isEqualTo(first);
        assertThat(changedTuple).isNotEqualTo(first);
        assertThat(changedFreshUntil).isNotEqualTo(first);
    }

    @Test
    void deepLinkSeparatesRequestedAdmittedAndIaCanonicalTargets() {
        EffectiveNavigation baseline = enforced(List.of(), "a", NOW);
        EffectiveNavigation effective = enforced(
                List.of(node("BASE:my-hr-profile", "/hr/me")), "b", NOW);
        when(v2.projection(any())).thenReturn(baseline);
        when(v2.projectionWithV47Paths(any(), any(), any())).thenReturn(effective);
        when(authority.evaluate(any())).thenReturn(
                snapshot(Set.of("BASE:my-hr-profile")));
        String pin = service.projection(runtime(), AccessMode.NORMAL).revisionPin();

        DeepLinkDecisionV3 result = service.deepLinkDecision(
                runtime(), AccessMode.NORMAL, "BASE:my-hr-profile", "/hr/me", pin);

        assertThat(result.allowed()).isTrue();
        assertThat(result.requestedPath()).isEqualTo("/hr/me");
        assertThat(result.admittedTarget()).isEqualTo("/hr/me");
        assertThat(result.iaCanonicalRoute()).isEqualTo("/hr/me/profile");
        assertThat(service.deepLinkDecision(
                runtime(), AccessMode.NORMAL, "BASE:my-hr-profile", "/hr/me/profile", pin)
                .denialReason()).isEqualTo("ROUTE_NOT_ADMITTED");
    }

    @Test
    void ownerOverlayCompatibilityDeepLinkRequiresExactV47PageAuthority() {
        EffectiveNavigation baseline = enforced(List.of(), "a", NOW);
        EffectiveNavigation effective = enforced(List.of(node(
                "BASE:team-leave", "/hr/team/leave", "/hr/team/absence")), "b", NOW);
        when(v2.projection(any())).thenReturn(baseline);
        when(v2.projectionWithV47Paths(any(), any(), any())).thenReturn(effective);
        when(authority.evaluate(any())).thenReturn(snapshot(Set.of()));
        when(eligibility.v47PageAuthorizedPath(
                "BASE:team-leave", "/hr/team/absence", Set.of("APP.HCM:VIEW")))
                .thenReturn(true);
        String pin = service.projection(runtime(), AccessMode.NORMAL).revisionPin();

        DeepLinkDecisionV3 admitted = service.deepLinkDecision(
                runtime(), AccessMode.NORMAL, "BASE:team-leave", "/hr/team/absence", pin);

        assertThat(admitted.allowed()).isTrue();
        assertThat(admitted.requestedPath()).isEqualTo("/hr/team/absence");
        assertThat(admitted.admittedTarget()).isEqualTo("/hr/team/absence");
        assertThat(admitted.iaCanonicalRoute()).isEqualTo("/hr/team/leave");

        when(eligibility.v47PageAuthorizedPath(
                "BASE:team-leave", "/hr/team/absence", Set.of("APP.HCM:VIEW")))
                .thenReturn(false);
        assertThat(service.deepLinkDecision(
                runtime(), AccessMode.NORMAL, "BASE:team-leave", "/hr/team/absence", pin)
                .denialReason()).isEqualTo("EXACT_PAGE_ROUTE_DENIED");
    }

    @Test
    void controlledExportDeepLinkUsesDedicatedSuccessorOwnerAndExactPageAuthority() {
        EffectiveNavigation baseline = enforced(List.of(), "a", NOW);
        EffectiveNavigation effective = enforced(List.of(node(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_ROUTE)), "b", NOW);
        when(v2.projection(any())).thenReturn(baseline);
        when(v2.projectionWithV47Paths(any(), any(), any())).thenReturn(effective);
        when(authority.evaluate(any())).thenReturn(snapshot(Set.of()));
        when(eligibility.v47PageAuthorizedPath(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_ROUTE,
                Set.of("APP.HCM:VIEW"))).thenReturn(true);
        String pin = service.projection(runtime(), AccessMode.NORMAL).revisionPin();

        DeepLinkDecisionV3 admitted = service.deepLinkDecision(
                runtime(), AccessMode.NORMAL,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_ROUTE, pin);

        assertThat(admitted.allowed()).isTrue();
        assertThat(admitted.iaCanonicalRoute()).isEqualTo("/hr/data/exports");
        when(eligibility.v47PageAuthorizedPath(
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_ROUTE,
                Set.of("APP.HCM:VIEW"))).thenReturn(false);
        assertThat(service.deepLinkDecision(
                runtime(), AccessMode.NORMAL,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_NODE_ID,
                NavigationPolicyTopologySuccessor.CONTROLLED_EXPORT_ROUTE, pin)
                .denialReason()).isEqualTo("EXACT_PAGE_ROUTE_DENIED");
    }

    private void stubEnforced() {
        when(v2.projection(any())).thenReturn(enforced(List.of(), "a", NOW));
        when(v2.projectionWithV47Paths(any(), any(), any()))
                .thenReturn(enforced(List.of(), "b", NOW));
    }

    private NavigationPolicyAggregateV3Service service(OffsetDateTime now) {
        return new NavigationPolicyAggregateV3Service(
                v2, authority, eligibility,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC), false);
    }

    private RuntimeContext runtime() {
        return new RuntimeContext(
                42, 101, PERSON, "APP.HCM:VIEW", "HRIS_EMPLOYEE",
                CONTEXT_SCOPE, null, NOW.plusMinutes(10));
    }

    private EffectiveNavigation enforced(
            List<EffectiveNode> nodes,
            String pinCharacter,
            OffsetDateTime evaluatedAt) {
        return navigation(
                EnforcementState.ENFORCED, true,
                "nav-v2-" + pinCharacter.repeat(64), nodes, evaluatedAt);
    }

    private EffectiveNavigation navigation(
            EnforcementState state,
            boolean enforced,
            String pin,
            List<EffectiveNode> nodes,
            OffsetDateTime evaluatedAt) {
        List<EffectiveWorkbench> workbenches = nodes.isEmpty()
                ? List.of() : List.of(new EffectiveWorkbench(
                "HRIS-WB-MY-HR", 1, nodes.getFirst().nodeId(),
                List.of(new EffectiveGroup(
                        "MY-TEST", 0, nodes.getFirst().nodeId(), nodes))));
        return new EffectiveNavigation(
                NavigationPolicyAggregateV2Dtos.SCHEMA_VERSION,
                NavigationPolicyTopologySuccessor.TOPOLOGY_REVISION,
                NavigationPolicyTopologySuccessor.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                state, enforced,
                enforced ? POLICY : null,
                enforced ? REVISION : null,
                enforced ? 7L : null,
                null, null, pin, workbenches, null, evaluatedAt);
    }

    private EffectiveNode node(String nodeId, String... paths) {
        return new EffectiveNode(nodeId, 0, List.of(paths));
    }

    private AuthoritySnapshot snapshot(Set<String> allowedNodeIds) {
        List<RouteDecision> decisions = NavigationPolicyAggregateV3Catalog.ROUTES.stream()
                .map(spec -> {
                    boolean allowed = allowedNodeIds.contains(spec.nodeId());
                    return new RouteDecision(
                            spec.nodeId(), spec.path(), spec.routeContractKey(),
                            spec.profileKey(), spec.targetBinding(),
                            allowed ? new ScopeBinding("SELF", SELF_SCOPE, PERSON) : null,
                            allowed ? "ALLOWED" : "ROUTE_DENIED",
                            allowed ? "ALLOWED" : "ROUTE_DENIED",
                            allowed ? "grant-" + Integer.toHexString(
                                    spec.nodeId().hashCode()).replace("-", "0")
                                    .repeat(32).substring(0, 32) : null,
                            DECISION, AUTH, POLICY_REVISION, RELATIONSHIP,
                            NOW.plusMinutes(5));
                }).toList();
        return new AuthoritySnapshot(
                47, NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                SNAPSHOT, DECISION, AUTH, POLICY_REVISION, RELATIONSHIP,
                NOW.plusMinutes(5), decisions);
    }
}
