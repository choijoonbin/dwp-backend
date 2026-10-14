package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.util.Set;

public interface NavigationPolicyEligibilityProvider {

    Set<String> installedNodeIds(long tenantId);

    Set<String> pageAuthorizedNodeIds(Set<String> trustedPermissions);
}
