package com.dwp.services.auth.identity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

@Service
public class IdentitySubjectLookupService {

    private final UserRepository users;
    private final RoleMemberRepository roleMembers;
    private final RoleRepository roles;
    private final DirectoryGroupMemberRepository groupMembers;
    private final DirectoryGroupRepository groups;
    private final EffectivePermissionKeyResolver permissionKeys;

    public IdentitySubjectLookupService(
            UserRepository users,
            RoleMemberRepository roleMembers,
            RoleRepository roles,
            DirectoryGroupMemberRepository groupMembers,
            DirectoryGroupRepository groups,
            EffectivePermissionKeyResolver permissionKeys) {
        this.users = users;
        this.roleMembers = roleMembers;
        this.roles = roles;
        this.groupMembers = groupMembers;
        this.groups = groups;
        this.permissionKeys = permissionKeys;
    }

    /**
     * Resolves an exact tenant-plane identity without imposing a lifecycle policy. Callers use
     * the returned status to decide whether an ACTIVE, INACTIVE, or INVITED subject is valid for
     * their operation.
     */
    @Transactional(readOnly = true)
    public Subject subject(Long tenantId, Long userId) {
        User user = users.findTenantIdentityByUserIdAndTenantId(userId, tenantId)
                .filter(this::isTenantIdentity)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new Subject(
                user.getTenantId(), user.getUserId(), user.getPublicId(),
                user.getPersonPublicId(), user.getDisplayName(), user.getEmail(),
                user.getJobTitle(), user.getStatus(), user.getIdentityPlane(),
                roleCodes(tenantId, userId), groupRefs(tenantId, userId),
                permissionKeys.resolve(tenantId, userId));
    }

    /**
     * Lists custody candidates. ACTIVE users can be targets; the broader source search also
     * admits INACTIVE users. INVITED users have never activated and are not custody candidates.
     */
    @Transactional(readOnly = true)
    public List<DirectorySubject> search(
            Long tenantId, String query, boolean activeOnly, int limit) {
        String normalized = query == null ? "" : query.trim();
        int boundedLimit = Math.max(1, Math.min(limit, 30));
        return users.searchTenantDirectoryUsers(
                        tenantId, normalized, activeOnly, PageRequest.of(0, boundedLimit))
                .stream()
                .filter(this::isTenantIdentity)
                .filter(user -> isDirectoryEligibleStatus(user, activeOnly))
                .map(user -> new DirectorySubject(
                        user.getTenantId(), user.getUserId(), user.getPublicId(),
                        user.getPersonPublicId(), user.getDisplayName(), user.getEmail(),
                        user.getJobTitle(), user.getStatus(), user.getIdentityPlane(),
                        roleCodes(tenantId, user.getUserId()),
                        groupRefs(tenantId, user.getUserId()),
                        permissionKeys.resolve(tenantId, user.getUserId())))
                .toList();
    }

    public record Subject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            String identityPlane,
            List<String> roles,
            List<UUID> groupRefs,
            List<String> permissionKeys) { }

    public record DirectorySubject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            String identityPlane,
            List<String> roles,
            List<UUID> groupRefs,
            List<String> permissionKeys) { }

    private List<String> roleCodes(Long tenantId, Long userId) {
        List<Long> roleIds = roleMembers.findRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) return List.of();
        return roles.findByRoleIdIn(roleIds).stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .map(role -> role.getCode())
                .distinct()
                .sorted()
                .toList();
    }

    private List<UUID> groupRefs(Long tenantId, Long userId) {
        List<Long> groupIds = groupMembers.findByTenantIdAndUserId(tenantId, userId)
                .stream()
                .map(member -> member.getGroupId())
                .distinct()
                .toList();
        if (groupIds.isEmpty()) return List.of();
        return groups.findByTenantIdAndGroupIdInAndStatus(tenantId, groupIds, "ACTIVE")
                .stream()
                .filter(group -> group.getPublicId() != null)
                .map(group -> group.getPublicId())
                .distinct()
                .sorted()
                .toList();
    }

    private boolean isTenantIdentity(User user) {
        return "TENANT".equalsIgnoreCase(user.getIdentityPlane());
    }

    private boolean isDirectoryEligibleStatus(User user, boolean activeOnly) {
        if ("ACTIVE".equalsIgnoreCase(user.getStatus())) return true;
        return !activeOnly && "INACTIVE".equalsIgnoreCase(user.getStatus());
    }
}
