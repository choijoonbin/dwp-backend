package com.dwp.services.people.security;

import com.dwp.core.security.HcmEligibilityScopeKey;

/** Deterministic derived-scope key shared by eligibility and service PEP checks. */
public final class HcmEligibilityScopeKeys {

    private HcmEligibilityScopeKeys() {
    }

    public static String derived(
            long tenantId,
            long actorId,
            String surfaceKey,
            String sourceScopeKey,
            String relationshipRevision,
            String targetPopulationRevision) {
        return HcmEligibilityScopeKey.derived(
                tenantId,
                actorId,
                surfaceKey,
                sourceScopeKey,
                relationshipRevision,
                targetPopulationRevision);
    }
}
