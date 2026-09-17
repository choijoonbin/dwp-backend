package com.dwp.services.approval.routingdirectory;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RoutingDirectoryGraph {
    private RoutingDirectoryGraph() {
    }

    static void requireAcyclic(Map<UUID, GroupView> groups) {
        Set<UUID> complete = new HashSet<>();
        Set<UUID> active = new HashSet<>();
        for (UUID groupId : groups.keySet()) {
            visit(groupId, groups, active, complete);
        }
    }

    static Resolution resolve(
            Context context,
            UUID rootGroupId,
            Map<UUID, GroupView> groups,
            Map<UUID, ResolverView> resolvers,
            RoutingDirectorySource source,
            Instant effectiveAt) {
        requireAcyclic(groups);
        GroupView root = groups.get(rootGroupId);
        if (root == null) {
            throw RoutingDirectoryRejected.unavailable("The requested approver group is unavailable.");
        }
        LinkedHashMap<UUID, Candidate> candidates = new LinkedHashMap<>();
        Set<String> revisions = new HashSet<>();
        expand(context, root, groups, resolvers, source, effectiveAt,
                new ArrayDeque<>(), candidates, revisions);
        if (candidates.isEmpty()) {
            throw RoutingDirectoryRejected.unavailable(
                    "No currently verified approver candidate could be resolved.");
        }
        List<Candidate> ordered = candidates.values().stream()
                .sorted(Comparator.comparing(Candidate::personPublicId))
                .toList();
        return new Resolution(root.groupId(), root.version(), ordered,
                revisions.stream().sorted().toList(), effectiveAt);
    }

    private static void visit(
            UUID groupId,
            Map<UUID, GroupView> groups,
            Set<UUID> active,
            Set<UUID> complete) {
        if (complete.contains(groupId)) return;
        if (!active.add(groupId)) {
            throw RoutingDirectoryRejected.conflict("Approver groups cannot contain cycles.");
        }
        GroupView group = groups.get(groupId);
        if (group == null) {
            throw RoutingDirectoryRejected.conflict("A nested approver group does not exist.");
        }
        for (MemberDraft member : group.members()) {
            if (member.kind() == MemberKind.GROUP) {
                visit(member.nestedGroupId(), groups, active, complete);
            }
        }
        active.remove(groupId);
        complete.add(groupId);
    }

    private static void expand(
            Context context,
            GroupView group,
            Map<UUID, GroupView> groups,
            Map<UUID, ResolverView> resolvers,
            RoutingDirectorySource source,
            Instant at,
            ArrayDeque<UUID> path,
            Map<UUID, Candidate> candidates,
            Set<String> revisions) {
        requireEffective(group.lifecycle(), group.effectiveFrom(), group.effectiveTo(), at,
                "Approver group is not active at the requested time.");
        if (path.contains(group.groupId())) {
            throw RoutingDirectoryRejected.conflict("Approver groups cannot contain cycles.");
        }
        path.addLast(group.groupId());
        List<MemberDraft> members = group.members().stream()
                .sorted(Comparator.comparingInt(MemberDraft::priority)
                        .thenComparing(MemberDraft::memberId))
                .toList();
        for (MemberDraft member : members) {
            int before = candidates.size();
            if (member.kind() == MemberKind.SUBJECT) {
                candidates.putIfAbsent(member.personPublicId(), new Candidate(
                        member.userId(), member.personPublicId(), "", "STATIC_SUBJECT"));
            } else if (member.kind() == MemberKind.GROUP) {
                GroupView nested = groups.get(member.nestedGroupId());
                if (nested == null) {
                    throw RoutingDirectoryRejected.unavailable(
                            "A nested approver group is unavailable.");
                }
                expand(context, nested, groups, resolvers, source, at,
                        path, candidates, revisions);
            } else {
                ResolverView resolver = resolvers.get(member.resolverId());
                requireResolver(resolver, at);
                List<Candidate> observed;
                try {
                    observed = source.resolve(context, resolver, at);
                } catch (RoutingDirectoryRejected exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw RoutingDirectoryRejected.unavailable(
                            "Approver resolver execution is unavailable.");
                }
                if (observed == null) {
                    throw RoutingDirectoryRejected.unavailable(
                            "Approver resolver returned no authoritative result.");
                }
                for (Candidate candidate : observed) {
                    requireCandidate(candidate);
                    candidates.putIfAbsent(candidate.personPublicId(), candidate);
                }
                revisions.add(resolver.sourceRevision());
            }
            if (member.required() && candidates.size() == before) {
                throw RoutingDirectoryRejected.unavailable(
                        "A required approver member produced no candidate.");
            }
        }
        path.removeLast();
    }

    private static void requireResolver(ResolverView resolver, Instant at) {
        if (resolver == null) {
            throw RoutingDirectoryRejected.unavailable("Approver resolver is unavailable.");
        }
        requireEffective(resolver.lifecycle(), resolver.effectiveFrom(), resolver.effectiveTo(), at,
                "Approver resolver is not active at the requested time.");
        if (resolver.sourceState() != SourceState.HEALTHY
                || resolver.validUntil() == null
                || !resolver.validUntil().isAfter(at)
                || resolver.observedAt() == null
                || resolver.observedAt().isAfter(at)
                || resolver.sourceRevision() == null
                || resolver.evidenceSha256() == null) {
            throw RoutingDirectoryRejected.unavailable(
                    "Approver resolver source is not currently verified.");
        }
    }

    private static void requireEffective(
            Lifecycle state, Instant from, Instant to, Instant at, String message) {
        if (state != Lifecycle.ACTIVE || from == null || from.isAfter(at)
                || (to != null && !to.isAfter(at))) {
            throw RoutingDirectoryRejected.unavailable(message);
        }
    }

    private static void requireCandidate(Candidate candidate) {
        if (candidate == null || candidate.userId() < 1
                || candidate.personPublicId() == null
                || candidate.authorityRevision() == null
                || candidate.authorityRevision().isBlank()) {
            throw RoutingDirectoryRejected.unavailable(
                    "Approver resolver returned an invalid candidate.");
        }
    }
}
