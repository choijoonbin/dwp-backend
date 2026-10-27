package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.PopulationScope;

/** Structural read views keep helper dependencies independent of service-owned records. */
final class ReferenceConfigurationContext {
    private ReferenceConfigurationContext() {
    }

    interface Access {
        long tenantId();
        long subjectId();
        UUID subjectPublicId();
        String permissions();
        String roles();
        String contextKey();
        String contextScopeKey();
        PopulationScope scope();
        long fieldPolicyRevision();
        long authorizationRevision();
        OffsetDateTime revalidateAt();
        Set<String> maskedFieldGroups();
        Set<String> omittedFieldGroups();
        String purposeCode();
    }

    interface Command {
        String idempotencyKey();
        UUID correlationId();
        String purposeCode();
        Long expectedObjectVersion();
        String expectedDecisionRevision();
        String stepUpChallenge();
        String originRouteContractKey();
        String originContextKey();
        String originContextScopeKey();
        String originDecisionRevision();
    }

    interface Cursor {
        String kind();
        long tenantId();
        String scopeRevision();
        String queryDigest();
        OffsetDateTime asOf();
        UUID seekAfter();
    }

    @FunctionalInterface
    interface CursorFactory {
        Cursor create(String kind, long tenantId, String scopeRevision, String queryDigest,
                OffsetDateTime asOf, UUID seekAfter);
    }
}
