package com.dwp.services.messaging.collaboration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ConversationMembershipService {

    private static final int MAX_ACTIVE_MEMBERS = 500;
    private static final Set<String> MANAGEABLE_TYPES = Set.of("GROUP", "CHANNEL");
    private static final Set<String> MANAGER_ROLES = Set.of("OWNER", "MODERATOR");
    private static final Set<String> PROTECTED_SOURCES = Set.of("SPACE_MIRRORED", "SYSTEM");

    private final ConversationMembershipRepository repository;
    private final MessagingEventRecorder events;

    public ConversationMembershipService(
            ConversationMembershipRepository repository,
            MessagingEventRecorder events) {
        this.repository = repository;
        this.events = events;
    }

    public CollaborationDtos.ConversationMembersResponse members(UUID conversationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        ConversationMembershipRepository.ConversationAccess access = repository.conversationAccess(
                        subject.tenantId(), conversationId, subject.userId())
                .orElseThrow(this::conversationNotFound);
        requireManageableType(access);
        return response(subject.tenantId(), conversationId, access);
    }

    @Transactional
    public CollaborationDtos.MembershipMutationResponse addMember(
            UUID conversationId,
            CollaborationDtos.AddConversationMemberRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        ConversationMembershipRepository.ConversationAccess access = lock(subject, conversationId);
        requireManager(access);
        requireOwnerAssignmentAuthority(access, null, request.role());

        ConversationMembershipRepository.PersonRecord person = repository.activePerson(
                        subject.tenantId(), request.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The requested member is not active in the requesting tenant."));
        var existing = repository.member(subject.tenantId(), conversationId, request.userId());
        if (existing.isPresent() && existing.get().active()) {
            if (existing.get().role().equals(request.role().name())) {
                return mutation(response(subject.tenantId(), conversationId, access), true);
            }
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The person is already active with a different role; use the role endpoint.");
        }
        existing.ifPresent(member -> requireMutableSource(member.source()));
        if (repository.activeMemberCount(subject.tenantId(), conversationId) >= MAX_ACTIVE_MEMBERS) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The conversation has reached its 500-member safety limit.");
        }
        requireVersion(access.conversationVersion(), request.conversationVersion());

        long historyStart = repository.nextHistoryStartSequence(subject.tenantId(), conversationId);
        advanceConversation(subject, conversationId, access.conversationVersion());
        if (repository.addOrReactivate(
                subject.tenantId(), conversationId, person, request.role(),
                historyStart, subject.userId()) < 0) {
            throw membershipConflict();
        }
        record(subject, "messaging.membership.added", conversationId, request.userId(), correlationId);
        return mutation(response(
                subject.tenantId(), conversationId,
                accessWithVersion(access, access.conversationVersion() + 1)), false);
    }

    @Transactional
    public CollaborationDtos.MembershipMutationResponse updateRole(
            UUID conversationId,
            long targetUserId,
            CollaborationDtos.UpdateConversationMemberRoleRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        ConversationMembershipRepository.ConversationAccess access = lock(subject, conversationId);
        requireManager(access);
        ConversationMembershipRepository.MemberRecord target = activeTarget(
                subject, conversationId, targetUserId);
        requireMutableSource(target.source());
        requireOwnerAssignmentAuthority(access, target.role(), request.role());
        if (target.role().equals(request.role().name())) {
            return mutation(response(subject.tenantId(), conversationId, access), true);
        }
        requireOwnerContinuity(subject.tenantId(), conversationId, target.role(), request.role().name());

        if (repository.updateRole(
                subject.tenantId(), conversationId, targetUserId, request.role(),
                request.version(), subject.userId()) == 0) {
            throw membershipConflict();
        }
        advanceConversation(subject, conversationId, access.conversationVersion());
        record(subject, "messaging.membership.role-updated",
                conversationId, targetUserId, correlationId);
        return mutation(response(
                subject.tenantId(), conversationId,
                accessWithVersion(access, access.conversationVersion() + 1)), false);
    }

    @Transactional
    public CollaborationDtos.MembershipMutationResponse removeMember(
            UUID conversationId,
            long targetUserId,
            long version,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        ConversationMembershipRepository.ConversationAccess access = lock(subject, conversationId);
        requireManager(access);
        ConversationMembershipRepository.MemberRecord target = activeTarget(
                subject, conversationId, targetUserId);
        requireMutableSource(target.source());
        requireOwnerRemovalAuthority(access, target.role());
        requireOwnerContinuity(subject.tenantId(), conversationId, target.role(), null);

        if (repository.revoke(
                subject.tenantId(), conversationId, targetUserId,
                version, subject.userId()) == 0) {
            throw membershipConflict();
        }
        advanceConversation(subject, conversationId, access.conversationVersion());
        record(subject, "messaging.membership.removed",
                conversationId, targetUserId, correlationId);
        return mutation(response(
                subject.tenantId(), conversationId,
                accessWithVersion(access, access.conversationVersion() + 1)), false);
    }

    @Transactional
    public CollaborationDtos.MembershipMutationResponse leave(
            UUID conversationId,
            CollaborationDtos.LeaveConversationRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        ConversationMembershipRepository.ConversationAccess access = lock(subject, conversationId);
        requireMutableSource(access.actorSource());
        requireOwnerContinuity(subject.tenantId(), conversationId, access.actorRole(), null);

        if (repository.revoke(
                subject.tenantId(), conversationId, subject.userId(),
                request.version(), subject.userId()) == 0) {
            throw membershipConflict();
        }
        advanceConversation(subject, conversationId, access.conversationVersion());
        record(subject, "messaging.membership.left",
                conversationId, subject.userId(), correlationId);
        CollaborationDtos.ConversationMembersResponse remaining = new CollaborationDtos.ConversationMembersResponse(
                conversationId,
                access.conversationType(),
                access.conversationVersion() + 1,
                summaries(repository.activeMembers(subject.tenantId(), conversationId)));
        return mutation(remaining, false);
    }

    private ConversationMembershipRepository.ConversationAccess lock(
            MessagingRequestContext.Subject subject, UUID conversationId) {
        ConversationMembershipRepository.ConversationAccess access = repository.lockConversation(
                        subject.tenantId(), conversationId, subject.userId())
                .orElseThrow(this::conversationNotFound);
        requireManageableType(access);
        return access;
    }

    private ConversationMembershipRepository.MemberRecord activeTarget(
            MessagingRequestContext.Subject subject, UUID conversationId, long targetUserId) {
        return repository.member(subject.tenantId(), conversationId, targetUserId)
                .filter(ConversationMembershipRepository.MemberRecord::active)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The active conversation member was not found."));
    }

    private void requireManageableType(ConversationMembershipRepository.ConversationAccess access) {
        if (!MANAGEABLE_TYPES.contains(access.conversationType())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Membership can be managed only for group and channel conversations.");
        }
    }

    private void requireManager(ConversationMembershipRepository.ConversationAccess access) {
        if (!MANAGER_ROLES.contains(access.actorRole())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Only conversation owners and moderators can manage members.");
        }
    }

    private void requireOwnerAssignmentAuthority(
            ConversationMembershipRepository.ConversationAccess access,
            String currentRole,
            CollaborationDtos.MemberRole requestedRole) {
        if (("OWNER".equals(currentRole) || requestedRole == CollaborationDtos.MemberRole.OWNER)
                && !"OWNER".equals(access.actorRole())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Only an owner can grant or revoke the owner role.");
        }
    }

    private void requireOwnerRemovalAuthority(
            ConversationMembershipRepository.ConversationAccess access, String targetRole) {
        if ("OWNER".equals(targetRole) && !"OWNER".equals(access.actorRole())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Only an owner can remove another owner.");
        }
    }

    private void requireOwnerContinuity(
            long tenantId, UUID conversationId, String currentRole, String requestedRole) {
        if (!"OWNER".equals(currentRole) || "OWNER".equals(requestedRole)) return;
        if (repository.activeOwnerCount(tenantId, conversationId) <= 1) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The final conversation owner cannot be removed or leave.");
        }
    }

    private void requireMutableSource(String source) {
        if (PROTECTED_SOURCES.contains(source)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Space-mirrored and system-managed memberships cannot be changed directly.");
        }
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) throw membershipConflict();
    }

    private void advanceConversation(
            MessagingRequestContext.Subject subject, UUID conversationId, long expectedVersion) {
        if (repository.advanceConversationVersion(
                subject.tenantId(), conversationId, subject.userId(), expectedVersion) == 0) {
            throw membershipConflict();
        }
    }

    private void record(
            MessagingRequestContext.Subject subject,
            String eventType,
            UUID conversationId,
            long targetUserId,
            String correlationId) {
        repository.recordAudit(
                subject.tenantId(), subject.userId(), eventType,
                conversationId, targetUserId, correlationId);
        events.conversationEvent(subject, eventType, conversationId, null, Map.of());
    }

    private CollaborationDtos.ConversationMembersResponse response(
            long tenantId,
            UUID conversationId,
            ConversationMembershipRepository.ConversationAccess access) {
        return new CollaborationDtos.ConversationMembersResponse(
                conversationId,
                access.conversationType(),
                access.conversationVersion(),
                summaries(repository.activeMembers(tenantId, conversationId)));
    }

    private List<CollaborationDtos.ManagedMemberSummary> summaries(
            List<ConversationMembershipRepository.MemberRecord> members) {
        return members.stream().map(member -> new CollaborationDtos.ManagedMemberSummary(
                member.userId(),
                member.personPublicId(),
                member.displayName(),
                member.emailAddress(),
                member.jobTitle(),
                member.organizationName(),
                member.role(),
                member.source(),
                member.historyStartSequence(),
                member.membershipStartedAt(),
                member.version())).toList();
    }

    private ConversationMembershipRepository.ConversationAccess accessWithVersion(
            ConversationMembershipRepository.ConversationAccess access, long version) {
        return new ConversationMembershipRepository.ConversationAccess(
                access.conversationType(), access.visibility(), access.lifecycleState(), version,
                access.actorRole(), access.actorSource(), access.actorVersion());
    }

    private CollaborationDtos.MembershipMutationResponse mutation(
            CollaborationDtos.ConversationMembersResponse membership, boolean replay) {
        return new CollaborationDtos.MembershipMutationResponse(membership, replay);
    }

    private BaseException conversationNotFound() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found.");
    }

    private BaseException membershipConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The conversation membership changed before this request completed.");
    }
}
