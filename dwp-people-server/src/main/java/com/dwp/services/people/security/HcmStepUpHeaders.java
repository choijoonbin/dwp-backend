package com.dwp.services.people.security;

/** Exact command headers forwarded by Gateway for a People-owned step-up action. */
public record HcmStepUpHeaders(
        String challenge,
        String idempotencyKey,
        String decisionRevision,
        Long expectedObjectVersion) {

    public static final String CHALLENGE = "X-DWP-Step-Up-Challenge";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    public static final String EXPECTED_OBJECT_VERSION = "X-DWP-Expected-Object-Version";
}
