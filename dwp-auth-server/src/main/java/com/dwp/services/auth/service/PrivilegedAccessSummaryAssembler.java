package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.PrivilegedAccessDtos;
import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.PrivilegedAccessApproval;
import com.dwp.services.auth.entity.PrivilegedAccessRequest;
import com.dwp.services.auth.entity.PrivilegedRoleEligibility;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.PrivilegedAccessApprovalRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-model assembly for privileged access eligibility and activation requests. */
final class PrivilegedAccessSummaryAssembler {

    private final RoleRepository roles;
    private final UserRepository users;
    private final DirectoryGroupRepository groups;
    private final PrivilegedAccessApprovalRepository approvals;

    PrivilegedAccessSummaryAssembler(
            RoleRepository roles,
            UserRepository users,
            DirectoryGroupRepository groups,
            PrivilegedAccessApprovalRepository approvals) {
        this.roles = roles;
        this.users = users;
        this.groups = groups;
        this.approvals = approvals;
    }

    List<PrivilegedAccessDtos.EligibilitySummary> eligibilities(
            Long tenantId, List<PrivilegedRoleEligibility> values) {
        Map<Long, Role> rolesById = rolesById(tenantId, values.stream()
                .map(PrivilegedRoleEligibility::getRoleId).toList());
        Map<Long, User> usersById = usersById(tenantId, values.stream()
                .filter(value -> "USER".equals(value.getPrincipalType()))
                .map(PrivilegedRoleEligibility::getPrincipalId).toList());
        Map<Long, DirectoryGroup> groupsById = groups.findAllById(values.stream()
                        .filter(value -> "GROUP".equals(value.getPrincipalType()))
                        .map(PrivilegedRoleEligibility::getPrincipalId).toList()).stream()
                .filter(group -> tenantId.equals(group.getTenantId()))
                .collect(Collectors.toMap(DirectoryGroup::getGroupId, Function.identity()));
        return values.stream().filter(value -> rolesById.containsKey(value.getRoleId()))
                .map(value -> {
                    Role role = rolesById.get(value.getRoleId());
                    String principalName = "USER".equals(value.getPrincipalType())
                            ? displayName(usersById.get(value.getPrincipalId()))
                            : displayName(groupsById.get(value.getPrincipalId()));
                    return new PrivilegedAccessDtos.EligibilitySummary(
                            value.getPrivilegedRoleEligibilityId(), value.getPrincipalType(),
                            value.getPrincipalId(), principalName, role.getRoleId(), role.getCode(),
                            role.getName(), value.getScopeType(), value.getScopeRef(),
                            value.getValidFrom(), value.getValidTo(), value.getJustification(),
                            value.getLifecycleState(), valueOrZero(value.getVersion()));
                }).toList();
    }

    PrivilegedAccessDtos.RequestSummary request(Long tenantId, PrivilegedAccessRequest request) {
        Role role = roles.findByRoleIdAndTenantId(request.getRoleId(), tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        User requester = users.findByUserIdAndTenantId(request.getRequesterUserId(), tenantId)
                .orElse(null);
        List<PrivilegedAccessApproval> decisions = approvals
                .findByPrivilegedAccessRequestIdOrderByDecidedAtAsc(
                        request.getPrivilegedAccessRequestId());
        Map<Long, User> approvers = usersById(tenantId, decisions.stream()
                .map(PrivilegedAccessApproval::getApproverUserId).toList());
        return new PrivilegedAccessDtos.RequestSummary(
                request.getPrivilegedAccessRequestId(), request.getRequesterUserId(),
                displayName(requester), role.getRoleId(), role.getCode(), role.getName(),
                request.getEligibilityId(), request.getRequestType(), request.getScopeType(),
                request.getScopeRef(), request.getDurationMinutes(), request.getJustification(),
                request.getTicketReference(), request.getAssuranceLevel(),
                request.getApprovalQuorum(), request.getLifecycleState(), request.getRequestedAt(),
                request.getActivatedAt(), request.getExpiresAt(), request.getRevokedAt(),
                valueOrZero(request.getVersion()), decisions.stream()
                .map(approval -> new PrivilegedAccessDtos.ApprovalSummary(
                        approval.getApproverUserId(),
                        displayName(approvers.get(approval.getApproverUserId())),
                        approval.getDecision(), approval.getReason(), approval.getDecidedAt()))
                .toList());
    }

    private Map<Long, Role> rolesById(Long tenantId, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return roles.findByRoleIdIn(ids.stream().distinct().toList()).stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
    }

    private Map<Long, User> usersById(Long tenantId, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findByTenantIdAndUserIdIn(
                        tenantId, ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
    }

    private static String displayName(Object principal) {
        if (principal instanceof User user) return user.getDisplayName();
        if (principal instanceof DirectoryGroup group) return group.getDisplayName();
        return null;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
