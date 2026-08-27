package com.dwp.services.auth.security;

import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuthSessionJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_SESSION = new OAuth2Error(
            "invalid_token",
            "The authentication session is missing, expired, or revoked.",
            null);

    private final AuthSessionRepository authSessionRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public AuthSessionJwtValidator(
            AuthSessionRepository authSessionRepository,
            RoleMemberRepository roleMemberRepository,
            RoleRepository roleRepository,
            UserRepository userRepository) {
        this.authSessionRepository = authSessionRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String tokenId = jwt.getId();
        if (tokenId == null || tokenId.isBlank()) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }
        boolean active = authSessionRepository.findByTokenId(tokenId)
                .map(session -> session.isActiveAt(Instant.now())
                        && Objects.equals(String.valueOf(session.getUserId()), jwt.getSubject())
                        && Objects.equals(
                                String.valueOf(session.getTenantId()),
                                jwt.getClaimAsString("tenant_id"))
                        && Objects.equals(
                                session.getSessionFamilyId().toString(),
                                jwt.getClaimAsString("sid"))
                        && assuranceMatches(jwt, session)
                        && currentRolesMatch(jwt, session.getTenantId(), session.getUserId()))
                .orElse(false);
        return active
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_SESSION);
    }

    private boolean assuranceMatches(
            Jwt jwt,
            com.dwp.services.auth.entity.AuthSession session) {
        if (session.getAuthenticatedAt() == null || session.getAssuranceAcr() == null) {
            return jwt.getClaims().get("auth_time") == null
                    && jwt.getClaims().get("acr") == null;
        }
        Object authTime = jwt.getClaims().get("auth_time");
        long claimedAuthTime = authTime instanceof Number number
                ? number.longValue()
                : Long.MIN_VALUE;
        Object amr = jwt.getClaims().get("amr");
        Set<String> claimedAmr = amr instanceof Collection<?> values
                ? values.stream().map(String::valueOf).collect(Collectors.toSet())
                : Set.of();
        return claimedAuthTime == session.getAuthenticatedAt().getEpochSecond()
                && session.getAssuranceAcr().equals(jwt.getClaimAsString("acr"))
                && claimedAmr.equals(new LinkedHashSet<>(session.getAssuranceAmr()));
    }

    private boolean currentRolesMatch(Jwt jwt, Long tenantId, Long userId) {
        Object claim = jwt.getClaims().get("roles");
        Set<String> claimedRoles = claim instanceof Collection<?> values
                ? values.stream().map(String::valueOf)
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        Set<String> currentRoles = roleRepository
                .findByRoleIdIn(roleMemberRepository.findRoleIds(tenantId, userId))
                .stream()
                .filter(role -> tenantId.equals(role.getTenantId()))
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .map(role -> role.getCode())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String identityPlane = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .map(com.dwp.services.auth.entity.User::getIdentityPlane)
                .orElse(null);
        boolean currentPlaneMatches = "PROVIDER".equals(identityPlane)
                ? currentRoles.stream().allMatch(RolePlaneBoundary::isProviderRole)
                : "TENANT".equals(identityPlane)
                        && currentRoles.stream().noneMatch(RolePlaneBoundary::isProviderRole);
        return !RolePlaneBoundary.hasConflict(currentRoles)
                && !RolePlaneBoundary.hasConflict(claimedRoles)
                && currentPlaneMatches
                && currentRoles.equals(claimedRoles);
    }
}
