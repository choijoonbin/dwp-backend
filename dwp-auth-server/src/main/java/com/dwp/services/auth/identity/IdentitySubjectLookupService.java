package com.dwp.services.auth.identity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
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

    public IdentitySubjectLookupService(
            UserRepository users,
            RoleMemberRepository roleMembers,
            RoleRepository roles) {
        this.users = users;
        this.roleMembers = roleMembers;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public Subject subject(Long tenantId, Long userId) {
        User user = users.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new Subject(
                user.getTenantId(), user.getUserId(), user.getPublicId(),
                user.getPersonPublicId(), user.getDisplayName(), user.getEmail(),
                user.getJobTitle(), user.getStatus(), roleCodes(tenantId, userId));
    }

    @Transactional(readOnly = true)
    public List<DirectorySubject> search(Long tenantId, String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        int boundedLimit = Math.max(1, Math.min(limit, 30));
        return users.searchActiveDirectoryUsers(
                        tenantId, normalized, PageRequest.of(0, boundedLimit))
                .stream()
                .map(user -> new DirectorySubject(
                        user.getTenantId(), user.getUserId(), user.getPublicId(),
                        user.getPersonPublicId(), user.getDisplayName(), user.getEmail(),
                        user.getJobTitle(), user.getStatus()))
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
            List<String> roles) { }

    public record DirectorySubject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status) { }

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
}
