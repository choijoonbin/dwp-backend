package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.util.Set;

public interface NavigationPolicyEligibilityProvider {

    Set<String> installedNodeIds(long tenantId);

    /** Server-owned deployment truth; a configured installation is not proof of a live route. */
    default Set<String> deployedNodeIds(long tenantId) {
        return installedNodeIds(tenantId);
    }

    Set<String> pageAuthorizedNodeIds(Set<String> trustedPermissions);

    /**
     * Rechecks the exact canonical or sealed compatibility PAGE route. Implementations backed by
     * an authoritative route catalog override this so a permission for one path cannot authorize a
     * different alias of the same aggregate node.
     */
    default boolean pageAuthorizedPath(
            String nodeId, String path, Set<String> trustedPermissions) {
        return pageAuthorizedNodeIds(trustedPermissions).contains(nodeId);
    }
}
