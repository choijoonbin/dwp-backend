package com.dwp.services.approval.routingdirectory;

import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RoutingDirectoryModels {
    private RoutingDirectoryModels() {
    }

    public enum Lifecycle {
        DRAFT, ACTIVE, RETIRED
    }

    public enum ResolverKind {
        MANAGER, ORG_ROLE, PROJECT_ROLE, DIRECTORY_GROUP, STATIC_SUBJECT
    }

    public enum SourceState {
        NOT_VERIFIED, HEALTHY, STALE, UNAVAILABLE, UNKNOWN
    }

    public enum MemberKind {
        SUBJECT, GROUP, RESOLVER
    }

    public record Context(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            UUID actorPersonPublicId,
            String idempotencyKey) {
        public Context {
            if (tenantId < 1 || actorUserId < 1 || actorPersonPublicId == null
                    || resourceSetKey == null
                    || !resourceSetKey.matches("RS_[A-Z0-9_]{1,76}")
                    || idempotencyKey == null
                    || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
                throw RoutingDirectoryRejected.invalid("Current routing command context is invalid.");
            }
        }

        public static Context current(String idempotencyKey) {
            ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
            if (actor.tenantId() == null || actor.userId() == null) {
                throw RoutingDirectoryRejected.forbidden("Current routing authority is incomplete.");
            }
            return new Context(actor.tenantId(),
                    ApprovalManagementScopeContext.requireResourceSetKey(),
                    actor.userId(), actor.personPublicId(), idempotencyKey);
        }
    }

    public record ResolverDraft(
            UUID resolverId,
            String resolverKey,
            String displayName,
            ResolverKind resolverKind,
            Map<String, Object> definition,
            Lifecycle lifecycle,
            Instant effectiveFrom,
            Instant effectiveTo,
            long expectedVersion) {
    }

    public record SourceObservation(
            UUID observationId,
            SourceState state,
            String sourceRevision,
            String evidenceSha256,
            Instant observedAt,
            Instant validUntil,
            long expectedVersion) {
    }

    public record MemberDraft(
            UUID memberId,
            MemberKind kind,
            Long userId,
            UUID personPublicId,
            UUID nestedGroupId,
            UUID resolverId,
            int priority,
            boolean required) {
        public static MemberDraft subject(
                UUID memberId, long userId, UUID personPublicId,
                int priority, boolean required) {
            return new MemberDraft(memberId, MemberKind.SUBJECT, userId,
                    personPublicId, null, null, priority, required);
        }

        public static MemberDraft group(
                UUID memberId, UUID groupId, int priority, boolean required) {
            return new MemberDraft(memberId, MemberKind.GROUP, null,
                    null, groupId, null, priority, required);
        }

        public static MemberDraft resolver(
                UUID memberId, UUID resolverId, int priority, boolean required) {
            return new MemberDraft(memberId, MemberKind.RESOLVER, null,
                    null, null, resolverId, priority, required);
        }
    }

    public record GroupDraft(
            UUID groupId,
            String groupKey,
            String displayName,
            String description,
            Lifecycle lifecycle,
            Instant effectiveFrom,
            Instant effectiveTo,
            List<MemberDraft> members,
            long expectedVersion) {
    }

    public record ResolverView(
            UUID resolverId,
            String resolverKey,
            String displayName,
            ResolverKind resolverKind,
            Map<String, Object> definition,
            Lifecycle lifecycle,
            Instant effectiveFrom,
            Instant effectiveTo,
            SourceState sourceState,
            String sourceRevision,
            String evidenceSha256,
            Instant observedAt,
            Instant validUntil,
            long version) {
    }

    public record GroupView(
            UUID groupId,
            String groupKey,
            String displayName,
            String description,
            Lifecycle lifecycle,
            Instant effectiveFrom,
            Instant effectiveTo,
            long version,
            List<MemberDraft> members) {
    }

    public record Candidate(
            long userId,
            UUID personPublicId,
            String displayName,
            String authorityRevision) {
    }

    public record Resolution(
            UUID groupId,
            long groupVersion,
            List<Candidate> candidates,
            List<String> sourceRevisions,
            Instant evaluatedAt) {
    }

    public record Usage(
            UUID groupId,
            String usageKind,
            UUID usageOwnerId,
            String usageRevision,
            String usageSha256,
            boolean active,
            Instant observedAt,
            long expectedGroupVersion) {
    }

    public record RetireImpact(
            UUID groupId,
            long groupVersion,
            long directUsageCount,
            long parentGroupCount,
            long totalImpactCount) {
    }

    public record ActivationCommand(long expectedVersion) {
    }

    public record RetirementCommand(long expectedVersion, long acknowledgedImpact) {
    }
}
