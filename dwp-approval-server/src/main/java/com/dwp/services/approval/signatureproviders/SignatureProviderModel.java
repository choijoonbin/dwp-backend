package com.dwp.services.approval.signatureproviders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Diagnostic observations are not signing authority or legal qualification. */
public final class SignatureProviderModel {
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public static final int MAX_PROVIDERS = 10;
    public static final int MAX_HISTORY = 50;

    private SignatureProviderModel() { }

    public enum ProviderKind { INTERNAL, DOCUSIGN, ADOBE_SIGN, CUSTOM }
    public enum Environment { UNCONFIGURED, INTERNAL, SANDBOX, PRODUCTION }
    public enum ObservationState { PASS, FAIL, NOT_CONFIGURED, NOT_OBSERVED, NOT_APPLICABLE }
    public enum PolicySourceState { AVAILABLE, MISSING_INTERNAL, NOT_CONFIGURED, UNRECORDED }
    public enum Readiness {
        MISSING_INTERNAL, DISABLED, CONFIGURATION_REQUIRED, NOT_VERIFIED, DEGRADED,
        VERIFIED_INTERNAL_KEY, VERIFIED_SANDBOX, VERIFIED_PRODUCTION
    }
    public enum GateState { NOT_EVALUATED, BLOCKED, ELIGIBLE }
    public enum PhaseKind { INTERNAL_DECISION, EXTERNAL_HANDOVER, VERIFIED_COMPLETION }
    public enum KmsBackend { NONE, INTERNAL_JCA, AWS_KMS, PKCS11 }
    public enum VerificationKind { NONE, INTERNAL_KEY, CONFIGURED_KMS, HARDWARE_TOKEN }
    public enum ObjectLockMode { NONE, GOVERNANCE, COMPLIANCE }
    public enum ProbeState { PENDING, RUNNING, COMPLETE, PARTIAL, UNKNOWN_REMOTE_OUTCOME }
    public enum ProbeOutcome { PASS, FAIL, INELIGIBLE, COOLDOWN, UNKNOWN_REMOTE_OUTCOME }
    public enum ExternalState {
        PREPARED, HANDOVER_PENDING, OUT_FOR_SIGNATURE, COMPLETION_PENDING,
        COMPLETED_VERIFIED, CANCEL_PENDING, CANCELLED, FAILED, UNKNOWN_REMOTE_OUTCOME
    }
    public enum ArtifactKind { UNSIGNED_PDF, SIGNED_PDF, CERTIFICATE, AUDIT_TRAIL, TSA }

    static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
            throw invalid("Invalid bounded text");
        return value;
    }

    static String sha(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid("Invalid SHA-256");
        return value;
    }

    static long version(long value) {
        if (value < 0 || value > MAX_VERSION) throw invalid("Invalid exact version");
        return value;
    }

    static String key(String value) {
        text(value, 120);
        if (!value.matches("[A-Za-z0-9._:-]{1,120}")) throw invalid("Invalid stable key");
        return value;
    }

    static <T> T required(T value) {
        if (value == null) throw invalid("Required value missing");
        return value;
    }

    static <T> List<T> bounded(List<T> values, int maximum) {
        if (values == null || values.size() > maximum || values.stream().anyMatch(v -> v == null))
            throw invalid("Invalid bounded list");
        return List.copyOf(values);
    }

    static <T> List<T> unique(List<T> values, int maximum) {
        var result = bounded(values, maximum);
        if (Set.copyOf(result).size() != result.size()) throw invalid("Duplicate list item");
        return result;
    }

    static List<String> reasons(List<String> values) {
        var result = unique(values, 32);
        result.forEach(value -> {
            text(value, 80);
            if (!value.matches("[A-Z][A-Z0-9_]{0,79}")) throw invalid("Invalid reason code");
        });
        return result;
    }

    static void evidence(UUID id, String digest) {
        if ((id == null) != (digest == null)) throw invalid("Evidence ID and digest must be paired");
        if (digest != null) sha(digest);
    }

    static void interval(Instant observed, Instant until) {
        if ((observed == null) != (until == null) || observed != null && !until.isAfter(observed))
            throw invalid("Invalid observation interval");
    }

    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
