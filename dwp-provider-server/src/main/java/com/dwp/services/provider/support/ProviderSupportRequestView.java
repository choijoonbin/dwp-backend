package com.dwp.services.provider.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Narrow projection consumed by support-ledger mapping.
 *
 * <p>The interface keeps the support package independent from the provider's
 * public DTO catalog while preserving the existing HTTP response records.</p>
 */
public interface ProviderSupportRequestView {

    UUID supportAccessRequestId();

    UUID tenantId();

    String tenantKey();

    String tenantName();

    String requesterName();

    String lifecycleState();

    String accessMode();

    String justification();

    List<String> scopes();

    int durationMinutes();

    String approvalReference();

    boolean customerApprovalRequired();

    String riskTier();

    Instant requestedAt();

    Instant decisionDueAt();

    UUID supportSessionId();

    Instant activatedAt();

    Instant completedAt();

    String postReviewState();

    long version();
}
