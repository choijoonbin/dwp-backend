package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.PermissionDTO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class ProductAuthorizationIdentityEvidenceService {

    private final AuthService authService;
    private final AppGovernanceService governanceService;
    private final ScopedAdminDutyEvidenceService scopedDutyEvidenceService;

    ProductAuthorizationIdentityEvidenceService(
            AuthService authService,
            AppGovernanceService governanceService,
            ScopedAdminDutyEvidenceService scopedDutyEvidenceService) {
        this.authService = authService;
        this.governanceService = governanceService;
        this.scopedDutyEvidenceService = scopedDutyEvidenceService;
    }

    IdentityEvidence load(Long tenantId, Long actorId) {
        Set<String> permissions = authService.getPermissions(actorId, tenantId).stream()
                .filter(value -> "ALLOW".equalsIgnoreCase(value.getEffect()))
                .map(ProductAuthorizationIdentityEvidenceService::permissionKey)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> roles = authService.getRoleCodes(actorId, tenantId).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        List<AppGovernanceDtos.ResourceRole> responsibilities = governanceService
                .resourceRoles(tenantId, actorId).stream()
                .sorted(Comparator.comparing(AppGovernanceDtos.ResourceRole::resourceSetKey)
                        .thenComparing(AppGovernanceDtos.ResourceRole::resourceKey)
                        .thenComparing(AppGovernanceDtos.ResourceRole::responsibilityCode))
                .toList();
        List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties =
                scopedDutyEvidenceService.effectiveDuties(tenantId, actorId);
        String material = tenantId + "\n" + actorId + "\n"
                + permissions.stream().sorted().collect(Collectors.joining("\n"))
                + "\n--roles--\n"
                + roles.stream().sorted().collect(Collectors.joining("\n"))
                + "\n--responsibilities--\n"
                + responsibilities.stream()
                        .map(value -> String.join("|",
                                value.responsibilityCode(),
                                value.resourceType(),
                                value.resourceKey(),
                                value.resourceSetKey(),
                                String.valueOf(value.validTo())))
                        .collect(Collectors.joining("\n"))
                + "\n--scoped-duties--\n"
                + duties.stream()
                        .map(value -> String.join("|",
                                value.dutyCode(), value.resourceSetKey(),
                                value.evidenceRevision()))
                        .sorted()
                        .collect(Collectors.joining("\n"));
        return new IdentityEvidence(
                permissions,
                roles,
                responsibilities,
                duties,
                "auth-" + sha256(material));
    }

    private static String permissionKey(PermissionDTO permission) {
        return (permission.getResourceKey() + ':' + permission.getPermissionCode())
                .toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record IdentityEvidence(
            Set<String> permissions,
            Set<String> roles,
            List<AppGovernanceDtos.ResourceRole> responsibilities,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> scopedDuties,
            String revision) {

        public IdentityEvidence {
            scopedDuties = scopedDuties == null ? List.of() : List.copyOf(scopedDuties);
        }

        boolean hasPermission(String key) {
            return key != null && permissions.contains(key.toUpperCase(Locale.ROOT));
        }

        boolean hasRole(String code) {
            return code != null && roles.contains(code.toUpperCase(Locale.ROOT));
        }
    }
}
