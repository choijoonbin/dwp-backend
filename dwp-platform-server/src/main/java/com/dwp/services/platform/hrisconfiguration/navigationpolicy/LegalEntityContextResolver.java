package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.util.UUID;

/** Platform-to-People owner boundary for tenant-scoped native legal-employer identity. */
public interface LegalEntityContextResolver {

    UUID resolve(OwnerRequest request);

    enum ContextSurface { MANAGEMENT, OPERATIONS }

    record OwnerRequest(
            long tenantId,
            long actorId,
            UUID personPublicId,
            String roles,
            String permissions,
            ContextSurface contextSurface,
            String contextScopeKey,
            UUID requestedLegalEmployerPublicId) {
    }
}
