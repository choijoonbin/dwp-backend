package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.DirectoryAdminDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.OrganizationUnit;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.OrganizationUnitRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


class DirectoryAdminGroupService extends DirectoryAdminOrganizationService {
    DirectoryAdminGroupService(
            OrganizationUnitRepository organizationRepository,
            DirectoryGroupRepository groupRepository,
            DirectoryGroupMemberRepository groupMemberRepository,
            UserRepository userRepository,
            AuthSessionRepository authSessionRepository,
            IdentityAuditService auditService,
            GroupRoleConflictGuard groupRoleConflictGuard) {
        super(organizationRepository, groupRepository, groupMemberRepository, userRepository,
                authSessionRepository, auditService, groupRoleConflictGuard);
    }

    @Transactional(readOnly = true)
    public DirectoryAdminDtos.PageResult<DirectoryAdminDtos.DirectoryGroupSummary> listGroups(
            Long tenantId,
            String query,
            String status,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<DirectoryGroup> specification = (root, ignored, builder) ->
                builder.equal(root.get("tenantId"), tenantId);
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("groupKey")), pattern),
                    builder.like(builder.lower(root.get("displayName")), pattern)));
        }
        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus != null) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("status"), normalizedStatus));
        }

        Page<DirectoryGroup> result = groupRepository.findAll(
                specification,
                PageRequest.of(
                        safePage,
                        safeSize,
                        Sort.by("displayName").ascending().and(Sort.by("groupId").ascending())));
        Map<Long, Long> memberCounts = groupMemberCounts(tenantId, result.getContent());
        return new DirectoryAdminDtos.PageResult<>(
                result.stream()
                        .map(group -> toGroupSummary(
                                group, memberCounts.getOrDefault(group.getGroupId(), 0L)))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public DirectoryAdminDtos.DirectoryGroupDetail getGroup(Long tenantId, Long groupId) {
        DirectoryGroup group = findGroup(tenantId, groupId);
        List<DirectoryGroupMember> memberships =
                groupMemberRepository.findByTenantIdAndGroupId(tenantId, groupId);
        List<User> members = usersForMemberships(tenantId, memberships);
        return new DirectoryAdminDtos.DirectoryGroupDetail(
                toGroupSummary(group, memberships.size()), memberSummaries(tenantId, members));
    }

    @Transactional
    public DirectoryAdminDtos.DirectoryGroupSummary createGroup(
            Long tenantId,
            Long actorId,
            String correlationId,
            DirectoryAdminDtos.CreateDirectoryGroupRequest request) {
        DirectoryGroup group = DirectoryGroup.builder()
                .tenantId(tenantId)
                .groupKey(normalizeKey(request.groupKey()))
                .displayName(request.displayName().trim())
                .description(trimToNull(request.description()))
                .sourceType(LOCAL)
                .status(ACTIVE)
                .revision(1L)
                .build();
        group.setCreatedBy(actorId);
        group.setUpdatedBy(actorId);
        group = saveGroup(group);
        auditService.success(
                tenantId,
                actorId,
                "directory.group.created",
                "DIRECTORY_GROUP",
                String.valueOf(group.getGroupId()),
                correlationId,
                null,
                groupSnapshot(group, 0L));
        return toGroupSummary(group, 0L);
    }

    @Transactional
    public DirectoryAdminDtos.DirectoryGroupSummary updateGroup(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long groupId,
            DirectoryAdminDtos.UpdateDirectoryGroupRequest request) {
        DirectoryGroup group = findGroupForUpdate(tenantId, groupId);
        requireLocal(group.getSourceType());
        requireVersion(group.getVersion(), request.version());
        long memberCount = groupMemberRepository.countByTenantIdAndGroupId(tenantId, groupId);
        Map<String, Object> before = groupSnapshot(group, memberCount);
        group.setDisplayName(request.displayName().trim());
        group.setDescription(trimToNull(request.description()));
        group.setRevision(valueOrZero(group.getRevision()) + 1L);
        group.setUpdatedBy(actorId);
        group = saveGroup(group);
        auditService.success(
                tenantId,
                actorId,
                "directory.group.updated",
                "DIRECTORY_GROUP",
                String.valueOf(groupId),
                correlationId,
                before,
                groupSnapshot(group, memberCount));
        return toGroupSummary(group, memberCount);
    }

    @Transactional
    public DirectoryAdminDtos.DirectoryGroupSummary changeGroupStatus(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long groupId,
            String nextStatus,
            DirectoryAdminDtos.LifecycleRequest request) {
        DirectoryGroup group = findGroupForUpdate(tenantId, groupId);
        requireLocal(group.getSourceType());
        requireVersion(group.getVersion(), request.version());
        String normalizedStatus = requiredLifecycleStatus(nextStatus);
        long memberCount = groupMemberRepository.countByTenantIdAndGroupId(tenantId, groupId);
        if (normalizedStatus.equals(group.getStatus())) {
            return toGroupSummary(group, memberCount);
        }
        if (INACTIVE.equals(normalizedStatus) && memberCount > 0) {
            throw conflict("A group with assigned members cannot be deactivated.");
        }
        Map<String, Object> before = groupSnapshot(group, memberCount);
        group.setStatus(normalizedStatus);
        group.setRevision(valueOrZero(group.getRevision()) + 1L);
        group.setUpdatedBy(actorId);
        group = saveGroup(group);
        auditService.success(
                tenantId,
                actorId,
                ACTIVE.equals(normalizedStatus)
                        ? "directory.group.activated"
                        : "directory.group.deactivated",
                "DIRECTORY_GROUP",
                String.valueOf(groupId),
                correlationId,
                before,
                groupSnapshot(group, memberCount));
        return toGroupSummary(group, memberCount);
    }

    @Transactional
    public DirectoryAdminDtos.DirectoryGroupDetail replaceGroupMembers(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long groupId,
            DirectoryAdminDtos.ReplaceMembersRequest request) {
        DirectoryGroup group = findGroupForUpdate(tenantId, groupId);
        requireLocal(group.getSourceType());
        requireActive(group.getStatus(), "group");
        requireVersion(group.getVersion(), request.version());

        List<DirectoryGroupMember> currentMemberships =
                groupMemberRepository.findByTenantIdAndGroupId(tenantId, groupId);
        if (currentMemberships.stream().anyMatch(member -> !LOCAL.equals(member.getSourceType()))) {
            throw conflict("Externally managed group memberships cannot be changed locally.");
        }
        Set<Long> beforeIds = currentMemberships.stream()
                .map(DirectoryGroupMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> requestedIds = new LinkedHashSet<>(request.userIds());
        Set<Long> lockIds = new LinkedHashSet<>(beforeIds);
        lockIds.addAll(requestedIds);
        List<User> lockedUsers = lockIds.isEmpty()
                ? List.of()
                : userRepository.findByTenantIdAndUserIdInForUpdate(tenantId, lockIds);
        Map<Long, User> usersById = lockedUsers.stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        validateRequestedUsers(requestedIds, usersById);
        if (beforeIds.equals(requestedIds)) {
            return new DirectoryAdminDtos.DirectoryGroupDetail(
                    toGroupSummary(group, beforeIds.size()),
                    memberSummaries(tenantId, usersForIds(beforeIds, usersById)));
        }

        Set<Long> addedIds = new LinkedHashSet<>(requestedIds);
        addedIds.removeAll(beforeIds);
        groupRoleConflictGuard.evaluateMembershipAddition(tenantId, groupId, addedIds)
                .ifPresent(violation -> {
                    auditService.denied(
                            tenantId,
                            actorId,
                            "directory.group.members.rejected",
                            "DIRECTORY_GROUP",
                            groupId.toString(),
                            correlationId,
                            violation.reason(),
                            Map.of(
                                    "userId", violation.userId(),
                                    "currentRoleCodes", violation.currentRoleCodes(),
                                    "additionalRoleCodes", violation.additionalRoleCodes()));
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "A requested member would violate separation-of-duties policy.");
                });

        List<DirectoryGroupMember> removals = currentMemberships.stream()
                .filter(member -> !requestedIds.contains(member.getUserId()))
                .toList();
        List<DirectoryGroupMember> additions = requestedIds.stream()
                .filter(userId -> !beforeIds.contains(userId))
                .map(userId -> {
                    DirectoryGroupMember member = DirectoryGroupMember.builder()
                            .tenantId(tenantId)
                            .groupId(groupId)
                            .userId(userId)
                            .sourceType(LOCAL)
                            .build();
                    member.setCreatedBy(actorId);
                    member.setUpdatedBy(actorId);
                    return member;
                })
                .toList();
        Set<Long> changedUserIds = new LinkedHashSet<>();
        removals.forEach(member -> changedUserIds.add(member.getUserId()));
        additions.forEach(member -> changedUserIds.add(member.getUserId()));
        List<User> changedUsers = changedUserIds.stream().map(usersById::get).toList();
        changedUsers.forEach(user -> markIdentityContextChanged(user, actorId));

        groupMemberRepository.deleteAll(removals);
        groupMemberRepository.saveAll(additions);
        userRepository.saveAll(changedUsers);
        revokeSessions(tenantId, changedUsers, actorId);
        Map<String, Object> before = membershipSnapshot(
                groupId, beforeIds, valueOrZero(group.getRevision()));
        group.setRevision(valueOrZero(group.getRevision()) + 1L);
        group.setUpdatedBy(actorId);
        group = saveGroup(group);
        auditService.success(
                tenantId,
                actorId,
                "directory.group.members.replaced",
                "DIRECTORY_GROUP",
                String.valueOf(groupId),
                correlationId,
                before,
                membershipSnapshot(groupId, requestedIds, group.getRevision()));
        return new DirectoryAdminDtos.DirectoryGroupDetail(
                toGroupSummary(group, requestedIds.size()),
                memberSummaries(tenantId, usersForIds(requestedIds, usersById)));
    }

}
