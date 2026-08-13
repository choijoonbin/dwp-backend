package com.dwp.services.auth.security;

import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
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

    public AuthSessionJwtValidator(
            AuthSessionRepository authSessionRepository,
            RoleMemberRepository roleMemberRepository,
            RoleRepository roleRepository) {
        this.authSessionRepository = authSessionRepository;
        this.roleMemberRepository = roleMemberRepository;
        this.roleRepository = roleRepository;
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
                        && currentRolesMatch(jwt, session.getTenantId(), session.getUserId()))
                .orElse(false);
        return active
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_SESSION);
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
        return currentRoles.equals(claimedRoles);
    }
}
