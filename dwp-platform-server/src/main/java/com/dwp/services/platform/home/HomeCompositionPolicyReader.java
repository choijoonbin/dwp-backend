package com.dwp.services.platform.home;

public interface HomeCompositionPolicyReader {

    boolean personalCustomizationEnabled(Long tenantId);

    /**
     * Resolves requests from pre-Wave1 clients that did not send a mode key.
     * Explicit mode keys remain authoritative in the personalization service.
     */
    default String effectiveExperienceVariant(Long tenantId) {
        return HomeCompositionPolicyRegistry.CLASSIC;
    }

    /**
     * Server-side rollout boundary for Phase 2 mutations. Reads intentionally
     * remain available so a preserved personalized home can still render while
     * editing is disabled or the tenant is rolled back to Classic.
     */
    default boolean flowPersonalizationEnabled(Long tenantId) {
        return false;
    }
}
