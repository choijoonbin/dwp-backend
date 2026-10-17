package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationPolicyAggregateV3CatalogTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T00:00:00Z");
    private static final UUID PERSON =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final String SELF_SCOPE = ProductSurfaceScopeKey.key(
            42, 101, "hcm", "hcm.personal", "SELF", "SELF");
    private static final String SNAPSHOT = "snapshot-" + "1".repeat(64);
    private static final String DECISION = "decision-" + "2".repeat(64);
    private static final String AUTH = "auth-" + "3".repeat(64);
    private static final String POLICY = "policy-47-9-" + "4".repeat(64);
    private static final String RELATIONSHIP = "relationship-" + "5".repeat(64);
    private static final OffsetDateTime FRESH_UNTIL = NOW.plusMinutes(5);

    @Test
    void sealsExactlySixClosedV46DecisionsInCatalogOrder() {
        AuthoritySnapshot snapshot = snapshot(Set.of(
                "BASE:my-time", "BASE:my-hr-profile"));

        NavigationPolicyAggregateV3Catalog.SealedAuthority result =
                NavigationPolicyAggregateV3Catalog.seal(request(null), snapshot, NOW);

        assertThat(result.snapshot()).isSameAs(snapshot);
        assertThat(result.admittedDecisions())
                .extracting(RouteDecision::nodeId)
                .containsExactly("BASE:my-hr-profile", "BASE:my-time");
        assertThat(result.admittedPaths()).containsExactly(
                org.assertj.core.api.Assertions.entry(
                        "BASE:my-hr-profile", List.of("/hr/me")),
                org.assertj.core.api.Assertions.entry(
                        "BASE:my-time", List.of("/hr/time")));
    }

    @Test
    void rejectsDuplicateUnknownAndIncompleteDecisionSets() {
        AuthoritySnapshot valid = snapshot(Set.of());
        ArrayList<RouteDecision> duplicate = new ArrayList<>(valid.decisions());
        duplicate.set(5, duplicate.getFirst());
        assertRejected(withDecisions(valid, duplicate),
                "AUTHORITY_DECISION_DUPLICATE");

        ArrayList<RouteDecision> unknown = new ArrayList<>(valid.decisions());
        RouteDecision original = unknown.get(5);
        unknown.set(5, new RouteDecision(
                "BASE:unknown", original.path(), original.routeContractKey(),
                original.profileKey(), original.targetBinding(), original.scopeBinding(),
                original.decision(), original.reasonCode(), original.routeGrantRef(),
                original.decisionRevision(), original.authRevision(),
                original.policyRevision(), original.relationshipRevision(),
                original.freshUntil()));
        assertRejected(withDecisions(valid, unknown), "AUTHORITY_DECISION_SET_INVALID");

        assertRejected(withDecisions(valid, valid.decisions().subList(0, 5)),
                "AUTHORITY_SNAPSHOT_INVALID");
    }

    @Test
    void rejectsWrongContractProfileAndTargetBindings() {
        AuthoritySnapshot valid = snapshot(Set.of("BASE:my-hr-profile"));
        assertRejected(mutate(valid, 0, value -> copy(
                value, "route.hcm.personal.wrong.page", value.profileKey(),
                value.targetBinding(), value.scopeBinding(), value.routeGrantRef(),
                value.authRevision(), value.freshUntil())), "AUTHORITY_DECISION_INVALID");
        assertRejected(mutate(valid, 0, value -> copy(
                value, value.routeContractKey(), "management-reader",
                value.targetBinding(), value.scopeBinding(), value.routeGrantRef(),
                value.authRevision(), value.freshUntil())), "AUTHORITY_DECISION_INVALID");
        assertRejected(mutate(valid, 0, value -> copy(
                value, value.routeContractKey(), value.profileKey(),
                "TARGET_POPULATION", value.scopeBinding(), value.routeGrantRef(),
                value.authRevision(), value.freshUntil())), "AUTHORITY_DECISION_INVALID");
    }

    @Test
    void rejectsWrongSelfScopePersonGrantAndDeniedOpenBindings() {
        AuthoritySnapshot valid = snapshot(Set.of("BASE:my-hr-profile"));
        assertRejected(mutate(valid, 0, value -> copy(
                value, value.routeContractKey(), value.profileKey(),
                value.targetBinding(), new ScopeBinding(
                        "SELF", "scope-" + "f".repeat(32), PERSON),
                value.routeGrantRef(), value.authRevision(), value.freshUntil())),
                "AUTHORITY_SELF_BINDING_INVALID");
        assertRejected(mutate(valid, 0, value -> copy(
                value, value.routeContractKey(), value.profileKey(),
                value.targetBinding(), new ScopeBinding(
                        "SELF", SELF_SCOPE, UUID.randomUUID()),
                value.routeGrantRef(), value.authRevision(), value.freshUntil())),
                "AUTHORITY_SELF_BINDING_INVALID");
        assertRejected(mutate(valid, 0, value -> copy(
                value, value.routeContractKey(), value.profileKey(),
                value.targetBinding(), value.scopeBinding(), "grant-invalid",
                value.authRevision(), value.freshUntil())),
                "AUTHORITY_SELF_BINDING_INVALID");

        assertRejected(mutate(valid, 1, value -> new RouteDecision(
                value.nodeId(), value.path(), value.routeContractKey(),
                value.profileKey(), value.targetBinding(),
                new ScopeBinding("SELF", SELF_SCOPE, PERSON), value.decision(),
                value.reasonCode(), "grant-" + "a".repeat(32),
                value.decisionRevision(), value.authRevision(), value.policyRevision(),
                value.relationshipRevision(), value.freshUntil())),
                "DENIED_DECISION_OPEN");
    }

    @Test
    void rejectsWrongRegistryStaleSnapshotsAndRevisionMismatches() {
        AuthoritySnapshot valid = snapshot(Set.of("BASE:my-hr-profile"));
        assertRejected(new AuthoritySnapshot(
                45, valid.registryChecksum(), valid.snapshotRevision(),
                valid.decisionRevision(), valid.authRevision(), valid.policyRevision(),
                valid.relationshipRevision(), valid.freshUntil(), valid.decisions()),
                "AUTHORITY_SNAPSHOT_INVALID");
        assertRejected(new AuthoritySnapshot(
                valid.registryVersion(), "f".repeat(64), valid.snapshotRevision(),
                valid.decisionRevision(), valid.authRevision(), valid.policyRevision(),
                valid.relationshipRevision(), valid.freshUntil(), valid.decisions()),
                "AUTHORITY_SNAPSHOT_INVALID");
        assertRejected(new AuthoritySnapshot(
                valid.registryVersion(), valid.registryChecksum(),
                valid.snapshotRevision(), valid.decisionRevision(), valid.authRevision(),
                valid.policyRevision(), valid.relationshipRevision(), NOW,
                valid.decisions()), "AUTHORITY_SNAPSHOT_INVALID");

        assertRejected(mutate(valid, 0, value -> copy(
                value, value.routeContractKey(), value.profileKey(),
                value.targetBinding(), value.scopeBinding(), value.routeGrantRef(),
                "auth-" + "e".repeat(64), value.freshUntil())),
                "AUTHORITY_DECISION_INVALID");
        assertThatThrownBy(() -> NavigationPolicyAggregateV3Catalog.seal(
                request("decision-" + "f".repeat(64)), valid, NOW))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("AUTHORITY_SNAPSHOT_INVALID");
    }

    @Test
    void policyPathBridgeRejectsEveryUnknownOrWrongAlias() {
        assertThatThrownBy(() -> NavigationPolicyAggregateV3Catalog.sealPolicyPaths(
                java.util.Map.of("BASE:unknown", List.of("/hr/me"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POLICY_NODE_UNKNOWN");
        assertThatThrownBy(() -> NavigationPolicyAggregateV3Catalog.sealPolicyPaths(
                java.util.Map.of("BASE:my-hr-profile", List.of("/hr/me/profile"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POLICY_PATH_INVALID");
    }

    private static AuthorityRequest request(String expectedDecisionRevision) {
        return new AuthorityRequest(
                42, 101, AccessMode.NORMAL, PERSON, SELF_SCOPE,
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                expectedDecisionRevision);
    }

    private static AuthoritySnapshot snapshot(Set<String> allowed) {
        List<RouteDecision> decisions = NavigationPolicyAggregateV3Catalog.ROUTES.stream()
                .map(spec -> {
                    boolean isAllowed = allowed.contains(spec.nodeId());
                    return new RouteDecision(
                            spec.nodeId(), spec.path(), spec.routeContractKey(),
                            spec.profileKey(), spec.targetBinding(),
                            isAllowed ? new ScopeBinding("SELF", SELF_SCOPE, PERSON) : null,
                            isAllowed ? "ALLOWED" : "ROUTE_DENIED",
                            isAllowed ? "ALLOWED" : "APP_ENTITLEMENT_REQUIRED",
                            isAllowed ? "grant-" + "a".repeat(32) : null,
                            DECISION, AUTH, POLICY, RELATIONSHIP, FRESH_UNTIL);
                }).toList();
        return new AuthoritySnapshot(
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                SNAPSHOT, DECISION, AUTH, POLICY, RELATIONSHIP, FRESH_UNTIL, decisions);
    }

    private static AuthoritySnapshot mutate(
            AuthoritySnapshot snapshot,
            int index,
            UnaryOperator<RouteDecision> mutation) {
        ArrayList<RouteDecision> decisions = new ArrayList<>(snapshot.decisions());
        decisions.set(index, mutation.apply(decisions.get(index)));
        return withDecisions(snapshot, decisions);
    }

    private static AuthoritySnapshot withDecisions(
            AuthoritySnapshot snapshot,
            List<RouteDecision> decisions) {
        return new AuthoritySnapshot(
                snapshot.registryVersion(), snapshot.registryChecksum(),
                snapshot.snapshotRevision(), snapshot.decisionRevision(),
                snapshot.authRevision(), snapshot.policyRevision(),
                snapshot.relationshipRevision(), snapshot.freshUntil(), decisions);
    }

    private static RouteDecision copy(
            RouteDecision value,
            String routeContractKey,
            String profileKey,
            String targetBinding,
            ScopeBinding scopeBinding,
            String routeGrantRef,
            String authRevision,
            OffsetDateTime freshUntil) {
        return new RouteDecision(
                value.nodeId(), value.path(), routeContractKey, profileKey,
                targetBinding, scopeBinding, value.decision(), value.reasonCode(),
                routeGrantRef, value.decisionRevision(), authRevision,
                value.policyRevision(), value.relationshipRevision(), freshUntil);
    }

    private static void assertRejected(
            AuthoritySnapshot snapshot,
            String message) {
        assertThatThrownBy(() -> NavigationPolicyAggregateV3Catalog.seal(
                request(null), snapshot, NOW))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining(message);
    }
}
