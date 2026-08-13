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

@Service
public class DirectoryAdminService {

    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";
    private static final String LOCAL = "LOCAL";

    private final OrganizationUnitRepository organizationRepository;
    private final DirectoryGroupRepository groupRepository;
    private final DirectoryGroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final IdentityAuditService auditService;
    private final GroupRoleConflictGuard groupRoleConflictGuard;

    public DirectoryAdminService(
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

    @Transactional(readOnly = true)
    public DirectoryAdminDtos.PageResult<DirectoryAdminDtos.OrganizationUnitSummary>
            listOrganizations(Long tenantId, String query, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<OrganizationUnit> specification = (root, ignored, builder) ->
                builder.equal(root.get("tenantId"), tenantId);
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("orgKey")), pattern),
                    builder.like(builder.lower(root.get("name")), pattern)));
        }
        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus != null) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("status"), normalizedStatus));
        }

        Page<OrganizationUnit> result = organizationRepository.findAll(
                specification,
                PageRequest.of(
                        safePage,
                        safeSize,
                        Sort.by("name").ascending().and(Sort.by("orgUnitId").ascending())));
        Map<Long, String> parentNames = parentNames(tenantId, result.getContent());
        Map<Long, Long> memberCounts = organizationMemberCounts(tenantId, result.getContent());
        return new DirectoryAdminDtos.PageResult<>(
                result.stream()
                        .map(organization -> toOrganizationSummary(
                                organization,
                                organization.getParentOrgUnitId() == null
                                        ? null
                                        : parentNames.get(organization.getParentOrgUnitId()),
                                memberCounts.getOrDefault(organization.getOrgUnitId(), 0L)))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public DirectoryAdminDtos.OrganizationUnitDetail getOrganization(
            Long tenantId,
            Long orgUnitId) {
        OrganizationUnit organization = findOrganization(tenantId, orgUnitId);
        List<User> members = userRepository
                .findByTenantIdAndPrimaryOrgUnitIdOrderByDisplayNameAscUserIdAsc(
                        tenantId, orgUnitId);
        return toOrganizationDetail(tenantId, organization, members);
    }

    @Transactional(readOnly = true)
    public DirectoryAdminDtos.PageResult<DirectoryAdminDtos.DirectoryMemberSummary> listUsers(
            Long tenantId,
            String query,
            String status,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<User> specification = (root, ignored, builder) ->
                builder.equal(root.get("tenantId"), tenantId);
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("displayName")), pattern),
                    builder.like(builder.lower(root.get("email")), pattern)));
        }
        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus != null) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("status"), normalizedStatus));
        }
        Page<User> result = userRepository.findAll(
                specification,
                PageRequest.of(
                        safePage,
                        safeSize,
                        Sort.by("displayName").ascending().and(Sort.by("userId").ascending())));
        Map<Long, String> organizationNames = organizationNamesForUsers(
                tenantId, result.getContent());
        return new DirectoryAdminDtos.PageResult<>(
                result.stream()
                        .map(user -> toMemberSummary(user, organizationNames))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional
    public DirectoryAdminDtos.OrganizationUnitSummary createOrganization(
            Long tenantId,
            Long actorId,
            String correlationId,
            DirectoryAdminDtos.CreateOrganizationUnitRequest request) {
        Map<Long, OrganizationUnit> organizations = lockedOrganizations(tenantId);
        OrganizationUnit parent = validateParent(
                organizations, null, request.parentOrgUnitId());
        OrganizationUnit organization = OrganizationUnit.builder()
                .tenantId(tenantId)
                .orgKey(normalizeKey(request.orgKey()))
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .parentOrgUnitId(parent == null ? null : parent.getOrgUnitId())
                .sourceType(LOCAL)
                .status(ACTIVE)
                .revision(1L)
                .build();
        organization.setCreatedBy(actorId);
        organization.setUpdatedBy(actorId);
        organization = saveOrganization(organization);
        auditService.success(
                tenantId,
                actorId,
                "directory.organization.created",
                "ORGANIZATION_UNIT",
                String.valueOf(organization.getOrgUnitId()),
                correlationId,
                null,
                organizationSnapshot(organization, parent == null ? null : parent.getName(), 0L));
        return toOrganizationSummary(
                organization, parent == null ? null : parent.getName(), 0L);
    }

    @Transactional
    public DirectoryAdminDtos.OrganizationUnitSummary updateOrganization(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long orgUnitId,
            DirectoryAdminDtos.UpdateOrganizationUnitRequest request) {
        Map<Long, OrganizationUnit> organizations = lockedOrganizations(tenantId);
        OrganizationUnit organization = requireOrganization(organizations, orgUnitId);
        requireLocal(organization.getSourceType());
        requireVersion(organization.getVersion(), request.version());
        OrganizationUnit parent = validateParent(
                organizations, orgUnitId, request.parentOrgUnitId());
        ensureNoCycle(organizations, orgUnitId, parent);
        long memberCount = userRepository.countByTenantIdAndPrimaryOrgUnitId(
                tenantId, orgUnitId);
        String previousParentName = parentName(organizations, organization.getParentOrgUnitId());
        Map<String, Object> before = organizationSnapshot(
                organization, previousParentName, memberCount);

        organization.setName(request.name().trim());
        organization.setDescription(trimToNull(request.description()));
        organization.setParentOrgUnitId(parent == null ? null : parent.getOrgUnitId());
        organization.setRevision(valueOrZero(organization.getRevision()) + 1L);
        organization.setUpdatedBy(actorId);
        organization = saveOrganization(organization);
        String nextParentName = parent == null ? null : parent.getName();
        auditService.success(
                tenantId,
                actorId,
                "directory.organization.updated",
                "ORGANIZATION_UNIT",
                String.valueOf(orgUnitId),
                correlationId,
                before,
                organizationSnapshot(organization, nextParentName, memberCount));
        return toOrganizationSummary(organization, nextParentName, memberCount);
    }

    @Transactional
    public DirectoryAdminDtos.OrganizationUnitSummary changeOrganizationStatus(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long orgUnitId,
            String nextStatus,
            DirectoryAdminDtos.LifecycleRequest request) {
        Map<Long, OrganizationUnit> organizations = lockedOrganizations(tenantId);
        OrganizationUnit organization = requireOrganization(organizations, orgUnitId);
        requireLocal(organization.getSourceType());
        requireVersion(organization.getVersion(), request.version());
        String normalizedStatus = requiredLifecycleStatus(nextStatus);
        long memberCount = userRepository.countByTenantIdAndPrimaryOrgUnitId(
                tenantId, orgUnitId);
        if (normalizedStatus.equals(organization.getStatus())) {
            return toOrganizationSummary(
                    organization,
                    parentName(organizations, organization.getParentOrgUnitId()),
                    memberCount);
        }
        if (INACTIVE.equals(normalizedStatus)) {
            long activeChildren = organizationRepository
                    .countByTenantIdAndParentOrgUnitIdAndStatus(tenantId, orgUnitId, ACTIVE);
            if (activeChildren > 0 || memberCount > 0) {
                throw conflict(
                        "An organization with active children or assigned members cannot be deactivated.");
            }
        } else {
            validateParent(organizations, orgUnitId, organization.getParentOrgUnitId());
        }

        String parentName = parentName(organizations, organization.getParentOrgUnitId());
        Map<String, Object> before = organizationSnapshot(organization, parentName, memberCount);
        organization.setStatus(normalizedStatus);
        organization.setRevision(valueOrZero(organization.getRevision()) + 1L);
        organization.setUpdatedBy(actorId);
        organization = saveOrganization(organization);
        auditService.success(
                tenantId,
                actorId,
                ACTIVE.equals(normalizedStatus)
                        ? "directory.organization.activated"
                        : "directory.organization.deactivated",
                "ORGANIZATION_UNIT",
                String.valueOf(orgUnitId),
                correlationId,
                before,
                organizationSnapshot(organization, parentName, memberCount));
        return toOrganizationSummary(organization, parentName, memberCount);
    }

    @Transactional
    public DirectoryAdminDtos.OrganizationUnitDetail replaceOrganizationMembers(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long orgUnitId,
            DirectoryAdminDtos.ReplaceMembersRequest request) {
        Map<Long, OrganizationUnit> organizations = lockedOrganizations(tenantId);
        OrganizationUnit organization = requireOrganization(organizations, orgUnitId);
        requireLocal(organization.getSourceType());
        requireActive(organization.getStatus(), "organization");
        requireVersion(organization.getVersion(), request.version());

        Set<Long> requestedIds = new LinkedHashSet<>(request.userIds());
        List<User> currentMembers = userRepository
                .findByTenantIdAndPrimaryOrgUnitIdOrderByDisplayNameAscUserIdAsc(
                        tenantId, orgUnitId);
        Set<Long> beforeIds = currentMembers.stream()
                .map(User::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> lockIds = new LinkedHashSet<>(beforeIds);
        lockIds.addAll(requestedIds);
        List<User> lockedUsers = lockIds.isEmpty()
                ? List.of()
                : userRepository.findByTenantIdAndUserIdInForUpdate(tenantId, lockIds);
        Map<Long, User> usersById = lockedUsers.stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        validateRequestedUsers(requestedIds, usersById);

        Map<Long, List<Long>> movedFrom = new LinkedHashMap<>();
        List<User> changedUsers = new ArrayList<>();
        for (User user : lockedUsers) {
            Long previousOrgUnitId = user.getPrimaryOrgUnitId();
            Long nextOrgUnitId;
            if (requestedIds.contains(user.getUserId())) {
                nextOrgUnitId = orgUnitId;
            } else if (Objects.equals(previousOrgUnitId, orgUnitId)) {
                nextOrgUnitId = null;
            } else {
                continue;
            }
            if (Objects.equals(previousOrgUnitId, nextOrgUnitId)) continue;
            if (previousOrgUnitId != null && !Objects.equals(previousOrgUnitId, orgUnitId)) {
                OrganizationUnit previousOrganization = organizations.get(previousOrgUnitId);
                if (previousOrganization == null) {
                    throw conflict("A user's current organization is not valid for this tenant.");
                }
                requireLocal(previousOrganization.getSourceType());
                movedFrom.computeIfAbsent(previousOrgUnitId, ignored -> new ArrayList<>())
                        .add(user.getUserId());
            }
            user.setPrimaryOrgUnitId(nextOrgUnitId);
            markIdentityContextChanged(user, actorId);
            changedUsers.add(user);
        }

        if (changedUsers.isEmpty()) {
            return toOrganizationDetail(tenantId, organization, currentMembers);
        }

        Map<Long, Long> revisionBefore = new LinkedHashMap<>();
        Set<Long> affectedOrganizationIds = new LinkedHashSet<>();
        affectedOrganizationIds.add(orgUnitId);
        affectedOrganizationIds.addAll(movedFrom.keySet());
        List<OrganizationUnit> affectedOrganizations = affectedOrganizationIds.stream()
                .map(organizations::get)
                .toList();
        for (OrganizationUnit affected : affectedOrganizations) {
            revisionBefore.put(affected.getOrgUnitId(), valueOrZero(affected.getRevision()));
            affected.setRevision(valueOrZero(affected.getRevision()) + 1L);
            affected.setUpdatedBy(actorId);
        }

        persistDirectoryChanges(affectedOrganizations, changedUsers);
        revokeSessions(tenantId, changedUsers, actorId);
        auditService.success(
                tenantId,
                actorId,
                "directory.organization.members.replaced",
                "ORGANIZATION_UNIT",
                String.valueOf(orgUnitId),
                correlationId,
                membershipSnapshot(orgUnitId, beforeIds, revisionBefore.get(orgUnitId)),
                membershipSnapshot(orgUnitId, requestedIds, organization.getRevision()));
        for (Map.Entry<Long, List<Long>> entry : movedFrom.entrySet()) {
            OrganizationUnit previous = organizations.get(entry.getKey());
            auditService.success(
                    tenantId,
                    actorId,
                    "directory.organization.members.moved-out",
                    "ORGANIZATION_UNIT",
                    String.valueOf(entry.getKey()),
                    correlationId,
                    movedMembersSnapshot(
                            entry.getKey(), entry.getValue(), revisionBefore.get(entry.getKey())),
                    movedMembersSnapshot(
                            entry.getKey(), List.of(), previous.getRevision()));
        }

        List<User> nextMembers = requestedIds.stream()
                .map(usersById::get)
                .sorted(userComparator())
                .toList();
        return toOrganizationDetail(tenantId, organization, nextMembers);
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

    private Map<Long, OrganizationUnit> lockedOrganizations(Long tenantId) {
        return organizationRepository.findByTenantIdForUpdate(tenantId).stream()
                .collect(Collectors.toMap(
                        OrganizationUnit::getOrgUnitId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));
    }

    private OrganizationUnit requireOrganization(
            Map<Long, OrganizationUnit> organizations,
            Long orgUnitId) {
        OrganizationUnit organization = organizations.get(orgUnitId);
        if (organization == null) throw new BaseException(ErrorCode.NOT_FOUND);
        return organization;
    }

    private OrganizationUnit findOrganization(Long tenantId, Long orgUnitId) {
        return organizationRepository.findByOrgUnitIdAndTenantId(orgUnitId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private DirectoryGroup findGroup(Long tenantId, Long groupId) {
        return groupRepository.findByGroupIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private DirectoryGroup findGroupForUpdate(Long tenantId, Long groupId) {
        return groupRepository.findByGroupIdAndTenantIdForUpdate(groupId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private OrganizationUnit validateParent(
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

    private void ensureNoCycle(
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

    private void validateRequestedUsers(Set<Long> requestedIds, Map<Long, User> usersById) {
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

    private List<User> usersForMemberships(
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

    private List<User> usersForIds(Set<Long> userIds, Map<Long, User> usersById) {
        return userIds.stream().map(usersById::get).sorted(userComparator()).toList();
    }

    private Comparator<User> userComparator() {
        return Comparator.comparing(User::getDisplayName).thenComparing(User::getUserId);
    }

    private void markIdentityContextChanged(User user, Long actorId) {
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
        user.setUpdatedBy(actorId);
    }

    private void revokeSessions(Long tenantId, Collection<User> users, Long actorId) {
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

    private void persistDirectoryChanges(
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

    private OrganizationUnit saveOrganization(OrganizationUnit organization) {
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

    private DirectoryGroup saveGroup(DirectoryGroup group) {
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

    private DirectoryAdminDtos.OrganizationUnitDetail toOrganizationDetail(
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

    private DirectoryAdminDtos.OrganizationUnitSummary toOrganizationSummary(
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

    private DirectoryAdminDtos.DirectoryGroupSummary toGroupSummary(
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

    private List<DirectoryAdminDtos.DirectoryMemberSummary> memberSummaries(
            Long tenantId,
            List<User> members) {
        Map<Long, String> organizationNames = organizationNamesForUsers(tenantId, members);
        return members.stream()
                .sorted(userComparator())
                .map(user -> toMemberSummary(user, organizationNames))
                .toList();
    }

    private DirectoryAdminDtos.DirectoryMemberSummary toMemberSummary(
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

    private Map<Long, String> organizationNamesForUsers(Long tenantId, Collection<User> users) {
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

    private Map<Long, String> parentNames(
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

    private Map<Long, Long> organizationMemberCounts(
            Long tenantId,
            Collection<OrganizationUnit> organizations) {
        List<Long> ids = organizations.stream().map(OrganizationUnit::getOrgUnitId).toList();
        if (ids.isEmpty()) return Map.of();
        return userRepository.countMembersByOrganizationIds(tenantId, ids).stream()
                .collect(Collectors.toMap(
                        UserRepository.OrganizationMemberCount::getOrgUnitId,
                        UserRepository.OrganizationMemberCount::getMemberCount));
    }

    private Map<Long, Long> groupMemberCounts(
            Long tenantId,
            Collection<DirectoryGroup> groups) {
        List<Long> ids = groups.stream().map(DirectoryGroup::getGroupId).toList();
        if (ids.isEmpty()) return Map.of();
        return groupMemberRepository.countMembersByGroupIds(tenantId, ids).stream()
                .collect(Collectors.toMap(
                        DirectoryGroupMemberRepository.GroupMemberCount::getGroupId,
                        DirectoryGroupMemberRepository.GroupMemberCount::getMemberCount));
    }

    private String parentName(
            Map<Long, OrganizationUnit> organizations,
            Long parentOrgUnitId) {
        if (parentOrgUnitId == null) return null;
        OrganizationUnit parent = organizations.get(parentOrgUnitId);
        return parent == null ? null : parent.getName();
    }

    private Map<String, Object> organizationSnapshot(
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

    private Map<String, Object> groupSnapshot(DirectoryGroup group, long memberCount) {
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

    private Map<String, Object> membershipSnapshot(
            Long resourceId,
            Collection<Long> userIds,
            Long revision) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceId", resourceId);
        snapshot.put("userIds", userIds.stream().sorted().toList());
        snapshot.put("revision", revision);
        return snapshot;
    }

    private Map<String, Object> movedMembersSnapshot(
            Long orgUnitId,
            Collection<Long> userIds,
            Long revision) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("orgUnitId", orgUnitId);
        snapshot.put("movedUserIds", userIds.stream().sorted().toList());
        snapshot.put("revision", revision);
        return snapshot;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        return requiredLifecycleStatus(status);
    }

    private String requiredLifecycleStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ACTIVE.equals(normalized) && !INACTIVE.equals(normalized)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Status must be ACTIVE or INACTIVE.");
        }
        return normalized;
    }

    private void requireLocal(String sourceType) {
        if (!LOCAL.equals(sourceType)) {
            throw conflict("Externally managed directory records are read-only.");
        }
    }

    private void requireActive(String status, String resourceName) {
        if (!ACTIVE.equals(status)) {
            throw conflict("The " + resourceName + " must be active.");
        }
    }

    private void requireVersion(Long currentVersion, Long requestedVersion) {
        if (!Objects.equals(valueOrZero(currentVersion), requestedVersion)) {
            throw conflict("The directory changed after it was loaded. Refresh and try again.");
        }
    }

    private String normalizeKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException conflict(String message, Throwable cause) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
