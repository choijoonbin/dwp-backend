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


class DirectoryAdminOrganizationService extends DirectoryAdminSupport {
    DirectoryAdminOrganizationService(
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

}
