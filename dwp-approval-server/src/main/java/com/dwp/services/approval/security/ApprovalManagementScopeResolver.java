package com.dwp.services.approval.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Maps an Auth-issued opaque Approval scope back to one exact trusted resource-set role. */
@Component
public final class ApprovalManagementScopeResolver {

    private static final String PRODUCT_KEY = "approvals";
    private static final String SURFACE_KEY = "approvals.admin";
    private static final String ROOT_SET = "RS_APPROVALS";

    public String resolve(
            long tenantId,
            long actorId,
            String opaqueScopeKey,
            List<ApprovalPilotPepRegistry.RouteAuthority> authorities,
            String resourceRoles) {
        if (tenantId <= 0 || actorId <= 0 || opaqueScopeKey == null
                || opaqueScopeKey.isBlank() || authorities == null || authorities.isEmpty()) {
            return null;
        }
        Set<String> wireRoles = roles(resourceRoles);
        Set<String> resolved = new LinkedHashSet<>();
        for (ApprovalPilotPepRegistry.RouteAuthority authority : authorities) {
            if (!authority.routeContractKey().startsWith("route.approvals.admin.")) continue;
            String capability = authority.capabilityContractKey();
            if (capability == null || authority.resolvedCapabilityCode() == null) return null;
            if (capability.startsWith("approvals.oversight.")) {
                String legacy = ProductSurfaceScopeKey.key(
                        tenantId, actorId, PRODUCT_KEY, SURFACE_KEY,
                        "APP_RESOURCE_SET:" + ROOT_SET, "RESOURCE_SET");
                if (legacy.equals(opaqueScopeKey)) resolved.add(ROOT_SET);
                continue;
            }
            Set<String> sets;
            try {
                sets = ScopedAuthorityToken.matchingResourceSetKeys(
                        wireRoles, capability, authority.resolvedCapabilityCode());
            } catch (IllegalArgumentException exception) {
                return null;
            }
            for (String set : sets) {
                if (authority.requiredResponsibilityCode() != null
                        && !wireRoles.contains(authority.requiredResponsibilityCode()
                        .toUpperCase(Locale.ROOT) + '@' + set)) {
                    continue;
                }
                if (ProductSurfaceScopeKey.resourceSet(
                        tenantId, actorId, PRODUCT_KEY, SURFACE_KEY, set)
                        .equals(opaqueScopeKey)) {
                    resolved.add(set);
                }
            }
        }
        return resolved.size() == 1 ? resolved.iterator().next() : null;
    }

    private Set<String> roles(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(",", -1))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
