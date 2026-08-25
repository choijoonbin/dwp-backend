package com.dwp.services.people.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic derived-scope key shared by eligibility and service PEP checks. */
public final class HcmEligibilityScopeKeys {

    private HcmEligibilityScopeKeys() {
    }

    public static String derived(
            long tenantId,
            long actorId,
            String surfaceKey,
            String sourceScopeKey,
            String relationshipRevision,
            String targetPopulationRevision) {
        String material = tenantId + "\n" + actorId + "\n" + surfaceKey + "\n"
                + sourceScopeKey + "\n" + relationshipRevision + "\n"
                + targetPopulationRevision;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return "hcm-scope-" + digest.substring(0, 40);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
