package com.dwp.services.auth.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Closed provider AMR policy and canonical MFA evidence for cross-service step-up tokens. */
final class OidcStepUpAmrPolicy {

    static final String AUTHENTICATION_METHOD = "OIDC_STEP_UP";
    private static final Set<String> KNOWN_AMR = Set.of(
            "pwd", "otp", "mfa", "hwk", "webauthn", "fido", "fido2");
    private static final Set<String> HARDWARE_AMR = Set.of(
            "hwk", "webauthn", "fido", "fido2");

    private OidcStepUpAmrPolicy() {
    }

    static List<String> parseProviderPolicy(String value) {
        if (value == null || value.isBlank()) return List.of();
        return normalizeProviderPolicy(List.of(value.trim().split("\\s+", -1)));
    }

    static List<String> normalizeProviderPolicy(Collection<String> values) {
        List<String> normalized = normalize(values);
        if (normalized.isEmpty()
                || !KNOWN_AMR.containsAll(normalized)
                || !isStrong(normalized)) {
            return List.of();
        }
        return normalized;
    }

    static List<String> canonicalize(
            Collection<String> acceptedProviderAmr,
            Collection<String> actualProviderAmr) {
        List<String> accepted = normalizeProviderPolicy(acceptedProviderAmr);
        List<String> actual = normalize(actualProviderAmr);
        if (accepted.isEmpty() || actual.isEmpty()
                || !KNOWN_AMR.containsAll(actual)
                || !accepted.containsAll(actual)
                || !isStrong(actual)) {
            return List.of();
        }
        LinkedHashSet<String> canonical = new LinkedHashSet<>(actual);
        canonical.add("mfa");
        return canonical.stream().sorted().toList();
    }

    static boolean isCanonicalStepUpEvidence(
            String authenticationMethod,
            Collection<String> amr) {
        List<String> normalized = normalize(amr);
        return AUTHENTICATION_METHOD.equals(authenticationMethod)
                && !normalized.isEmpty()
                && KNOWN_AMR.containsAll(normalized)
                && normalized.contains("mfa");
    }

    private static List<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) return List.of();
            String value = raw.trim();
            if (!value.matches("[a-z0-9]{2,64}") || !result.add(value)) return List.of();
        }
        return result.stream().sorted().toList();
    }

    private static boolean isStrong(Collection<String> amr) {
        return amr.contains("mfa")
                || amr.stream().anyMatch(HARDWARE_AMR::contains)
                || (amr.contains("pwd") && amr.contains("otp"));
    }
}
