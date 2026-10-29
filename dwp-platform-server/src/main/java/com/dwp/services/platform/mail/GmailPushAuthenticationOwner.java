package com.dwp.services.platform.mail;

import java.util.List;
import java.util.Map;

/** Deployment-owned Google Pub/Sub OIDC signature, issuer and audience verifier. */
public interface GmailPushAuthenticationOwner {
    boolean supports(Map<String, List<String>> headers);

    Authentication verify(Map<String, List<String>> headers, String expectedAudience);

    record Authentication(
            boolean verified,
            String issuer,
            String audience,
            String subject,
            String errorCode) {
        public Authentication {
            issuer = bounded(issuer);
            audience = bounded(audience);
            subject = bounded(subject);
            if (verified && (issuer == null || audience == null || subject == null))
                throw new IllegalArgumentException(
                        "Verified Pub/Sub authentication requires issuer, audience and subject");
            if (errorCode != null && !errorCode.matches("[A-Z0-9_.-]{1,120}"))
                throw new IllegalArgumentException("errorCode must be canonical");
        }

        private static String bounded(String value) {
            if (value == null || value.isBlank()) return null;
            String normalized = value.trim();
            if (normalized.length() > 500 || normalized.chars()
                    .anyMatch(character -> character < 0x20))
                throw new IllegalArgumentException("OIDC evidence is invalid");
            return normalized;
        }
    }
}
