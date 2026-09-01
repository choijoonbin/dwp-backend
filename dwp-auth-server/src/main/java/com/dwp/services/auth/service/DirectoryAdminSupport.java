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


abstract class DirectoryAdminSupport {
    protected static final String ACTIVE = "ACTIVE";
    protected static final String INACTIVE = "INACTIVE";
    protected static final String LOCAL = "LOCAL";

    protected final OrganizationUnitRepository organizationRepository;
    protected final DirectoryGroupRepository groupRepository;
    protected final DirectoryGroupMemberRepository groupMemberRepository;
    protected final UserRepository userRepository;
    protected final AuthSessionRepository authSessionRepository;
    protected final IdentityAuditService auditService;
    protected final GroupRoleConflictGuard groupRoleConflictGuard;


    DirectoryAdminSupport(
            OrganizationUnitRepository organizationRepository,
            DirectoryGroupRepository groupRepository,
            DirectoryGroupMemberRepository groupMemberRepository,
            UserRepository userRepository,
            AuthSessionRepository authSessionRepository,
            IdentityAuditService auditService,
            GroupRoleConflictGuard groupRoleConflictGuard) {
        this.organizationRepository = organizationRepository;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.auditService = auditService;
        this.groupRoleConflictGuard = groupRoleConflictGuard;
    }

    protected Map<Long, OrganizationUnit> lockedOrganizations(Long tenantId) {
        return organizationRepository.findByTenantIdForUpdate(tenantId).stream()
                .collect(Collectors.toMap(
                        OrganizationUnit::getOrgUnitId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
    }

    protected OrganizationUnit requireOrganization(
            Map<Long, OrganizationUnit> organizations,
            Long orgUnitId) {
        OrganizationUnit organization = organizations.get(orgUnitId);
        if (organization == null) throw new BaseException(ErrorCode.NOT_FOUND);
        return organization;
    }

    protected OrganizationUnit findOrganization(Long tenantId, Long orgUnitId) {
        return organizationRepository.findByOrgUnitIdAndTenantId(orgUnitId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    protected DirectoryGroup findGroup(Long tenantId, Long groupId) {
        return groupRepository.findByGroupIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    protected DirectoryGroup findGroupForUpdate(Long tenantId, Long groupId) {
        return groupRepository.findByGroupIdAndTenantIdForUpdate(groupId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    protected OrganizationUnit validateParent(
            Map<Long, OrganizationUnit> organizations,
            Long targetOrgUnitId,
            Long parentOrgUnitId) {
        if (parentOrgUnitId == null) return null;
        if (Objects.equals(targetOrgUnitId, parentOrgUnitId)) {
            throw conflict("An organization cannot be its own parent.");
        }
        OrganizationUnit parent = organizations.get(parentOrgUnitId);
        if (parent == null) throw new BaseException(ErrorCode.NOT_FOUND);
        requireActive(parent.getStatus(), "parent organization");
        return parent;
    }

    protected void ensureNoCycle(
            Map<Long, OrganizationUnit> organizations,
            Long targetOrgUnitId,
            OrganizationUnit parent) {
        Set<Long> visited = new LinkedHashSet<>();
        OrganizationUnit current = parent;
        while (current != null) {
            if (Objects.equals(current.getOrgUnitId(), targetOrgUnitId)) {
                throw conflict("The selected parent would create an organization cycle.");
            }
            if (!visited.add(current.getOrgUnitId())) {
                throw conflict("The organization hierarchy already contains a cycle.");
            }
            Long nextParentId = current.getParentOrgUnitId();
            if (nextParentId == null) return;
            current = organizations.get(nextParentId);
            if (current == null) {
                throw conflict("The organization hierarchy references a missing parent.");
            }
        }
    }

    protected void validateRequestedUsers(Set<Long> requestedIds, Map<Long, User> usersById) {
        if (requestedIds.size() != usersById.keySet().stream()
                .filter(requestedIds::contains)
                .count()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "One or more users do not exist in this tenant.");
        }
        boolean hasInactiveUser = requestedIds.stream()
                .map(usersById::get)
                .anyMatch(user -> !ACTIVE.equals(user.getStatus()));
        if (hasInactiveUser) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Inactive users cannot be assigned to an organization or group.");
        }
    }

    protected List<User> usersForMemberships(
            Long tenantId,
            List<DirectoryGroupMember> memberships) {
        if (memberships.isEmpty()) return List.of();
        Set<Long> userIds = memberships.stream()
                .map(DirectoryGroupMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> usersById = userRepository.findAllById(userIds).stream()
                .filter(user -> tenantId.equals(user.getTenantId()))
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        if (usersById.size() != userIds.size()) {
            throw conflict("A group membership references an invalid tenant user.");
        }
        return usersForIds(userIds, usersById);
    }

    protected List<User> usersForIds(Set<Long> userIds, Map<Long, User> usersById) {
        return userIds.stream().map(usersById::get).sorted(userComparator()).toList();
    }

    protected Comparator<User> userComparator() {
        return Comparator.comparing(User::getDisplayName).thenComparing(User::getUserId);
    }

    protected void markIdentityContextChanged(User user, Long actorId) {
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
        user.setUpdatedBy(actorId);
    }

    protected void revokeSessions(Long tenantId, Collection<User> users, Long actorId) {
        if (users.isEmpty()) return;
        Set<Long> userIds = users.stream()
                .map(User::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Instant now = Instant.now();
        List<AuthSession> sessions = authSessionRepository
                .findByTenantIdAndUserIdInAndRevokedAtIsNull(tenantId, userIds);
        sessions.forEach(session -> {
            session.setRevokedAt(now);
            session.setUpdatedBy(actorId);
        });
        authSessionRepository.saveAll(sessions);
    }

    protected void persistDirectoryChanges(
            List<OrganizationUnit> organizations,
            List<User> users) {
        try {
            organizationRepository.saveAll(organizations);
            userRepository.saveAll(users);
            organizationRepository.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw conflict(
                    "The directory changed after it was loaded. Refresh and try again.",
                    exception);
        }
    }

    protected OrganizationUnit saveOrganization(OrganizationUnit organization) {
        try {
            return organizationRepository.saveAndFlush(organization);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The organization key already exists in this tenant.", exception);
        } catch (OptimisticLockingFailureException exception) {
            throw conflict(
                    "The organization changed after it was loaded. Refresh and try again.",
                    exception);
        }
    }

    protected DirectoryGroup saveGroup(DirectoryGroup group) {
        try {
            return groupRepository.saveAndFlush(group);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The group key already exists in this tenant.", exception);
        } catch (OptimisticLockingFailureException exception) {
            throw conflict(
                    "The group changed after it was loaded. Refresh and try again.",
                    exception);
        }
    }

    protected DirectoryAdminDtos.OrganizationUnitDetail toOrganizationDetail(
            Long tenantId,
            OrganizationUnit organization,
            List<User> members) {
        String parentName = organization.getParentOrgUnitId() == null
                ? null
                : organizationRepository
                        .findByOrgUnitIdAndTenantId(
                                organization.getParentOrgUnitId(), tenantId)
                        .map(OrganizationUnit::getName)
                        .orElse(null);
        return new DirectoryAdminDtos.OrganizationUnitDetail(
                toOrganizationSummary(organization, parentName, members.size()),
                memberSummaries(tenantId, members));
    }

    protected DirectoryAdminDtos.OrganizationUnitSummary toOrganizationSummary(
            OrganizationUnit organization,
            String parentName,
            long memberCount) {
        return new DirectoryAdminDtos.OrganizationUnitSummary(
                organization.getOrgUnitId(),
                organization.getOrgKey(),
                organization.getName(),
                organization.getDescription(),
                organization.getParentOrgUnitId(),
                parentName,
                organization.getSourceType(),
                organization.getStatus(),
                memberCount,
                valueOrZero(organization.getRevision()),
                valueOrZero(organization.getVersion()),
                organization.getUpdatedAt(),
                organization.getUpdatedBy());
    }

    protected DirectoryAdminDtos.DirectoryGroupSummary toGroupSummary(
            DirectoryGroup group,
            long memberCount) {
        return new DirectoryAdminDtos.DirectoryGroupSummary(
                group.getGroupId(),
                group.getGroupKey(),
                group.getDisplayName(),
                group.getDescription(),
                group.getSourceType(),
                group.getStatus(),
                memberCount,
                valueOrZero(group.getRevision()),
                valueOrZero(group.getVersion()),
                group.getUpdatedAt(),
                group.getUpdatedBy());
    }

    protected List<DirectoryAdminDtos.DirectoryMemberSummary> memberSummaries(
            Long tenantId,
            List<User> members) {
        Map<Long, String> organizationNames = organizationNamesForUsers(tenantId, members);
        return members.stream()
                .sorted(userComparator())
                .map(user -> toMemberSummary(user, organizationNames))
                .toList();
    }

    protected DirectoryAdminDtos.DirectoryMemberSummary toMemberSummary(
            User user,
            Map<Long, String> organizationNames) {
        return new DirectoryAdminDtos.DirectoryMemberSummary(
                user.getUserId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getStatus(),
                user.getPrimaryOrgUnitId(),
                user.getPrimaryOrgUnitId() == null
                        ? null
                        : organizationNames.get(user.getPrimaryOrgUnitId()));
    }

    protected Map<Long, String> organizationNamesForUsers(Long tenantId, Collection<User> users) {
        Set<Long> organizationIds = users.stream()
                .map(User::getPrimaryOrgUnitId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (organizationIds.isEmpty()) return Map.of();
        return organizationRepository.findByTenantIdAndOrgUnitIdIn(tenantId, organizationIds)
                .stream()
                .collect(Collectors.toMap(
                        OrganizationUnit::getOrgUnitId,
                        OrganizationUnit::getName));
    }

    protected Map<Long, String> parentNames(
            Long tenantId,
            Collection<OrganizationUnit> organizations) {
        Set<Long> parentIds = organizations.stream()
                .map(OrganizationUnit::getParentOrgUnitId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) return Map.of();
        return organizationRepository.findByTenantIdAndOrgUnitIdIn(tenantId, parentIds).stream()
                .collect(Collectors.toMap(OrganizationUnit::getOrgUnitId, OrganizationUnit::getName));
    }

    protected Map<Long, Long> organizationMemberCounts(
            Long tenantId,
            Collection<OrganizationUnit> organizations) {
        List<Long> ids = organizations.stream().map(OrganizationUnit::getOrgUnitId).toList();
        if (ids.isEmpty()) return Map.of();
        return userRepository.countMembersByOrganizationIds(tenantId, ids).stream()
                .collect(Collectors.toMap(
                        UserRepository.OrganizationMemberCount::getOrgUnitId,
                        UserRepository.OrganizationMemberCount::getMemberCount));
    }

    protected Map<Long, Long> groupMemberCounts(
            Long tenantId,
            Collection<DirectoryGroup> groups) {
        List<Long> ids = groups.stream().map(DirectoryGroup::getGroupId).toList();
        if (ids.isEmpty()) return Map.of();
        return groupMemberRepository.countMembersByGroupIds(tenantId, ids).stream()
                .collect(Collectors.toMap(
                        DirectoryGroupMemberRepository.GroupMemberCount::getGroupId,
                        DirectoryGroupMemberRepository.GroupMemberCount::getMemberCount));
    }

    protected String parentName(
            Map<Long, OrganizationUnit> organizations,
            Long parentOrgUnitId) {
        if (parentOrgUnitId == null) return null;
        OrganizationUnit parent = organizations.get(parentOrgUnitId);
        return parent == null ? null : parent.getName();
    }

    protected Map<String, Object> organizationSnapshot(
            OrganizationUnit organization,
            String parentName,
            long memberCount) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("orgUnitId", organization.getOrgUnitId());
        snapshot.put("orgKey", organization.getOrgKey());
        snapshot.put("name", organization.getName());
        snapshot.put("description", organization.getDescription());
        snapshot.put("parentOrgUnitId", organization.getParentOrgUnitId());
        snapshot.put("parentName", parentName);
        snapshot.put("sourceType", organization.getSourceType());
        snapshot.put("status", organization.getStatus());
        snapshot.put("memberCount", memberCount);
        snapshot.put("revision", valueOrZero(organization.getRevision()));
        return snapshot;
    }

    protected Map<String, Object> groupSnapshot(DirectoryGroup group, long memberCount) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("groupId", group.getGroupId());
        snapshot.put("groupKey", group.getGroupKey());
        snapshot.put("displayName", group.getDisplayName());
        snapshot.put("description", group.getDescription());
        snapshot.put("sourceType", group.getSourceType());
        snapshot.put("status", group.getStatus());
        snapshot.put("memberCount", memberCount);
        snapshot.put("revision", valueOrZero(group.getRevision()));
        return snapshot;
    }

    protected Map<String, Object> membershipSnapshot(
            Long resourceId,
            Collection<Long> userIds,
            Long revision) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceId", resourceId);
        snapshot.put("userIds", userIds.stream().sorted().toList());
        snapshot.put("revision", revision);
        return snapshot;
    }

    protected Map<String, Object> movedMembersSnapshot(
            Long orgUnitId,
            Collection<Long> userIds,
            Long revision) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("orgUnitId", orgUnitId);
        snapshot.put("movedUserIds", userIds.stream().sorted().toList());
        snapshot.put("revision", revision);
        return snapshot;
    }

    protected String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        return requiredLifecycleStatus(status);
    }

    protected String requiredLifecycleStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ACTIVE.equals(normalized) && !INACTIVE.equals(normalized)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Status must be ACTIVE or INACTIVE.");
        }
        return normalized;
    }

    protected void requireLocal(String sourceType) {
        if (!LOCAL.equals(sourceType)) {
            throw conflict("Externally managed directory records are read-only.");
        }
    }

    protected void requireActive(String status, String resourceName) {
        if (!ACTIVE.equals(status)) {
            throw conflict("The " + resourceName + " must be active.");
        }
    }

    protected void requireVersion(Long currentVersion, Long requestedVersion) {
        if (!Objects.equals(valueOrZero(currentVersion), requestedVersion)) {
            throw conflict("The directory changed after it was loaded. Refresh and try again.");
        }
    }

    protected String normalizeKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    protected String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected Long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    protected BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    protected BaseException conflict(String message, Throwable cause) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
