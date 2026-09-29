package com.dwp.services.auth.service;

import java.util.Set;

/** Unwired Auth-owner bridge. This carrier is not a signed or current Gateway proof. */
public final class NativeHrisAuthIdentityEvidenceTruthV2 {
    private NativeHrisAuthIdentityEvidenceTruthV2() { }
    public record Truth(long tenantId, long userId, String authRevision, Set<String> roles) {
        public Truth { roles = roles == null ? null : Set.copyOf(roles); }
    }
    /** Installed only by trusted server composition, never a request/body role supplier. */
    public interface Provider { Truth loadCurrent(long tenantId, long userId); }

    /** Reuses the actual owner algorithm, including permissions, responsibilities and duties.
     * No new hash rules, role-to-permission shortcuts or invented revision counters. */
    public static Provider fromNativeServices(AuthService auth, AppGovernanceService governance,
            ScopedAdminDutyEvidenceService duties) {
        if (auth == null || governance == null || duties == null) return null;
        var owner = new ProductAuthorizationIdentityEvidenceService(auth, governance, duties);
        return (tenant, user) -> {
            var evidence = owner.load(tenant, user);
            return new Truth(tenant, user, evidence.revision(), evidence.roles());
        };
    }
}
