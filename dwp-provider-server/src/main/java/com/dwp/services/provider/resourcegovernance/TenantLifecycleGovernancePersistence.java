package com.dwp.services.provider.resourcegovernance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.*;

/** Dependency-inverted persistence contract for tenant lifecycle governance. */
interface TenantLifecycleGovernancePersistence<T> {
    List<T> lifecycleRequests(UUID tenantId, int fetchLimit);
    Optional<T> lifecycleRequest(UUID requestId);
    Optional<T> lockLifecycleRequest(UUID requestId);
    T createLifecycleRequest(UUID requestId, UUID tenantId,
                             CreateTenantLifecycleRequest request,
                             String lifecycleState, String holdEvaluationState,
                             List<String> holdEvidenceRefs, Long actorId);
    boolean refreshLifecycleHold(UUID requestId, long version,
                                 String lifecycleState, String holdEvaluationState,
                                 List<String> holdEvidenceRefs);
    boolean submitLifecycleRequest(UUID requestId, long version, Long actorId);
    boolean cancelLifecycleRequest(UUID requestId, long version, Long actorId, String reason);
    boolean decideLifecycleRequest(UUID requestId,
                                   TenantLifecycleDecisionRequest request, Long actorId);
}
