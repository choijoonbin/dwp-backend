package com.dwp.services.payroll.statutory.pay003;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class Pay003Ports {

    private Pay003Ports() {
    }

    enum Verification { VERIFIED, INVALID, UNAVAILABLE }

    interface SignatureVerificationPort {
        Verification verify(Pay003Models.PackSnapshot snapshot);
    }

    record ApprovalEvidence(
            Verification verification,
            UUID receiptId,
            UUID approverSubjectPublicId,
            long tenantId,
            UUID legalEntityId,
            UUID payrollGroupId,
            String officialReferenceSet,
            String goldenPackDigest) {
    }

    interface StatutoryApprovalPort {
        ApprovalEvidence resolve(
                Pay003Authority.Actor actor,
                Pay003Models.PackSnapshot snapshot,
                UUID receiptId);
    }

    interface CountryRuleProviderPort {
        Verification validate(
                Pay003Models.PackSnapshot snapshot,
                Pay003Models.ActivationCommand command);
    }

    interface ProjectionDecisionPort {
        Map<String, Pay003Models.Decision> decisions(
                Pay003Authority.Actor actor, String variantKey);
    }

    interface ActivationDecisionPort {
        boolean allow(
                Pay003Authority.Actor actor,
                Pay003Models.PackSnapshot snapshot,
                boolean preliminary);
    }

    record StepUpEvidence(
            String challengeId,
            String nonce,
            String issuer,
            Instant expiresAt,
            String payloadSha256,
            String decisionRevision) {
    }

    interface StepUpPort {
        StepUpEvidence require(
                Pay003Authority.Actor actor,
                Pay003Models.PackSnapshot snapshot,
                Pay003Models.ActivationCommand command,
                Pay003Models.ActivationHeaders headers);
    }
}
