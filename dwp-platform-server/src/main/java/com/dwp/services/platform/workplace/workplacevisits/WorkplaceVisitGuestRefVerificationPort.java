package com.dwp.services.platform.workplace.workplacevisits;

import java.time.OffsetDateTime;
import java.util.Map;

/** Verifies opaque guest references without returning or accepting raw identity fields. */
public interface WorkplaceVisitGuestRefVerificationPort {
    boolean supports(String opaqueGuestRef);

    Verification verify(VerificationRequest request);

    record VerificationRequest(
            long tenantId,
            String opaqueGuestRef,
            String maskedLabel,
            String purpose,
            Map<String, OffsetDateTime> fieldRetentionExpiresAt) { }

    record Verification(boolean valid, String evidenceReference, String limitationCode) {
        public Verification {
            if (valid && (evidenceReference == null
                    || !evidenceReference.matches("[A-Za-z0-9._~:/-]{8,320}"))) {
                throw new IllegalArgumentException(
                        "Valid guest-reference verification requires opaque evidence.");
            }
            if (limitationCode != null && !limitationCode.matches("[A-Z0-9_]{2,120}")) {
                throw new IllegalArgumentException("Guest-reference limitation code is invalid.");
            }
        }
    }
}
