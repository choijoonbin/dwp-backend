package com.dwp.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cross-service contract for the People-owned HCM eligibility scope emitted after the Auth scope
 * and current workforce relationship have been intersected.
 */
public final class HcmEligibilityScopeKey {

    private static final String PREFIX = "hcm-scope-";
    private static final int DIGEST_LENGTH = 40;

    private HcmEligibilityScopeKey() {
    }

    public static String derived(
            long tenantId,
            long actorId,
            String surfaceKey,
            String sourceScopeKey,
            String relationshipRevision,
            String targetPopulationRevision) {
        if (tenantId <= 0 || actorId <= 0) {
            throw new IllegalArgumentException("tenantId and actorId must be positive.");
        }
        requireText(surfaceKey, "surfaceKey");
        requireText(sourceScopeKey, "sourceScopeKey");
        requireText(relationshipRevision, "relationshipRevision");
        requireText(targetPopulationRevision, "targetPopulationRevision");
        String material = tenantId + "\n" + actorId + "\n" + surfaceKey + "\n"
                + sourceScopeKey + "\n" + relationshipRevision + "\n"
                + targetPopulationRevision;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return PREFIX + digest.substring(0, DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static boolean isCanonical(String value) {
        if (value == null || value.length() != PREFIX.length() + DIGEST_LENGTH
                || !value.startsWith(PREFIX)) {
            return false;
        }
        for (int index = PREFIX.length(); index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 500
                || !value.equals(value.trim()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " is not canonical.");
        }
    }
}
