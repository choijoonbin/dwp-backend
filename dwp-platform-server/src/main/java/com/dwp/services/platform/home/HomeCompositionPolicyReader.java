package com.dwp.services.platform.home;

public interface HomeCompositionPolicyReader {

    boolean personalCustomizationEnabled(Long tenantId);

    /**
     * Server-side rollout boundary for Phase 2 mutations. Reads intentionally
     * remain available so a preserved personalized home can still render while
     * editing is disabled or the tenant is rolled back to Classic.
     */
    default boolean flowPersonalizationEnabled(Long tenantId) {
        return false;
    }
}
