package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Verifies issuer signature, freshness, subject, command and CAS binding. */
public interface StepUpEvidenceVerifier {

    boolean verify(StepUpEvidence evidence);

    record StepUpEvidence(
            long tenantId,
            long actorUserId,
            String action,
            UUID revisionId,
            long expectedObjectVersion,
            String contextKey,
            String contextScopeKey,
            String idempotencyKey,
            String commandMethod,
            String commandPath,
            Object canonicalPayload,
            String decisionRevision,
            String proof,
            HcmPlatformPepRegistry.RouteAuthority requestAuthority) {
    }
}

/** Missing verifier integration denies every high-risk command. */
@Component
@ConditionalOnMissingBean(StepUpEvidenceVerifier.class)
final class FailClosedStepUpEvidenceVerifier implements StepUpEvidenceVerifier {

    @Override
    public boolean verify(StepUpEvidence evidence) {
        return false;
    }
}
