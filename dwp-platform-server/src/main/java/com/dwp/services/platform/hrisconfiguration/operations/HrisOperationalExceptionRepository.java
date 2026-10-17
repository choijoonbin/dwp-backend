package com.dwp.services.platform.hrisconfiguration.operations;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;

interface HrisOperationalExceptionRepository {

    IngressResult apply(ValidatedEvent event);

    ProjectionPage list(
            long tenantId,
            Set<OwnerModule> ownerModules,
            OwnerModule ownerModule,
            Severity severity,
            PublicState state,
            OffsetDateTime asOf,
            ProjectionPosition after,
            int limit);

    Optional<Projection> find(
            long tenantId,
            Set<OwnerModule> ownerModules,
            UUID exceptionPublicId);

    ProjectionSummary summary(long tenantId, OffsetDateTime asOf);
}
