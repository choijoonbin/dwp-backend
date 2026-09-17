package com.dwp.services.approval.routingdirectory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingDirectoryGraphTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final UUID PERSON = UUID.fromString("00000000-0000-4000-8000-000000000019");

    @Test
    void rejectsCyclesBeforeAnyCandidateResolution() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, GroupView> groups = Map.of(
                first, group(first, List.of(MemberDraft.group(UUID.randomUUID(), second, 1, true))),
                second, group(second, List.of(MemberDraft.group(UUID.randomUUID(), first, 1, true))));

        assertThatThrownBy(() -> RoutingDirectoryGraph.requireAcyclic(groups))
                .isInstanceOf(RoutingDirectoryRejected.class)
                .hasMessageContaining("cycles");
    }

    @Test
    void failsClosedForStaleOrEmptyRequiredResolverAndDeduplicatesVerifiedCandidates() {
        UUID groupId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();
        GroupView group = group(groupId, List.of(
                MemberDraft.resolver(UUID.randomUUID(), resolverId, 1, true)));
        ResolverView stale = resolver(resolverId, NOW.minusSeconds(1));
        Context context = new Context(42, "RS_APPROVALS", 17, PERSON, "resolve-test");

        assertThatThrownBy(() -> RoutingDirectoryGraph.resolve(
                context, groupId, Map.of(groupId, group), Map.of(resolverId, stale),
                (ignoredContext, ignoredResolver, ignoredAt) -> List.of(), NOW))
                .isInstanceOf(RoutingDirectoryRejected.class)
                .hasMessageContaining("not currently verified");

        ResolverView healthy = resolver(resolverId, NOW.plusSeconds(300));
        Candidate candidate = new Candidate(71, PERSON, "Approver", "people-r7");
        Resolution resolution = RoutingDirectoryGraph.resolve(
                context, groupId, Map.of(groupId, group), Map.of(resolverId, healthy),
                (ignoredContext, ignoredResolver, ignoredAt) -> List.of(candidate, candidate), NOW);
        assertThat(resolution.candidates()).containsExactly(candidate);
        assertThat(resolution.sourceRevisions()).containsExactly("directory-r9");

        assertThatThrownBy(() -> RoutingDirectoryGraph.resolve(
                context, groupId, Map.of(groupId, group), Map.of(resolverId, healthy),
                (ignoredContext, ignoredResolver, ignoredAt) -> List.of(), NOW))
                .isInstanceOf(RoutingDirectoryRejected.class)
                .hasMessageContaining("required approver member");
    }

    private GroupView group(UUID id, List<MemberDraft> members) {
        return new GroupView(id, "GROUP." + id.toString().substring(0, 8).toUpperCase(),
                "Group", "", Lifecycle.ACTIVE, NOW.minusSeconds(60), null, 1, members);
    }

    private ResolverView resolver(UUID id, Instant validUntil) {
        return new ResolverView(id, "RESOLVER.TEST", "Resolver", ResolverKind.MANAGER,
                Map.of("purpose", "APPROVAL"), Lifecycle.ACTIVE, NOW.minusSeconds(60), null,
                SourceState.HEALTHY, "directory-r9", "a".repeat(64),
                NOW.minusSeconds(30), validUntil, 2);
    }
}
