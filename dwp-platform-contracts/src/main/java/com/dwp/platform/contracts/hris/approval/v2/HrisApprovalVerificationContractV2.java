package com.dwp.platform.contracts.hris.approval.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Closed verification view of one Approval bridge endpoint.
 *
 * <p>The package-private constructor keeps digest and failure semantics owned by
 * the bridge while allowing the proof verifier to depend only on this value.</p>
 */
public final class HrisApprovalVerificationContractV2 {
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CALLBACK_PATH = Pattern.compile(
            "^/internal/hris-people/v1/employee-change-requests/"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12})/approval-decisions$");

    private final String bindingId;
    private final String ownerWorkloadId;
    private final String callerWorkloadId;
    private final String method;
    private final String pathTemplate;
    private final String purpose;
    private final Function<String, RuntimeException> deniedFailure;
    private final Predicate<RuntimeException> verificationFailure;

    HrisApprovalVerificationContractV2(
            String bindingId,
            String ownerWorkloadId,
            String callerWorkloadId,
            String method,
            String pathTemplate,
            String purpose,
            Function<String, RuntimeException> deniedFailure,
            Predicate<RuntimeException> verificationFailure) {
        this.bindingId = Objects.requireNonNull(bindingId, "bindingId");
        this.ownerWorkloadId = Objects.requireNonNull(ownerWorkloadId, "ownerWorkloadId");
        this.callerWorkloadId = Objects.requireNonNull(callerWorkloadId, "callerWorkloadId");
        this.method = Objects.requireNonNull(method, "method");
        this.pathTemplate = Objects.requireNonNull(pathTemplate, "pathTemplate");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.deniedFailure = Objects.requireNonNull(deniedFailure, "deniedFailure");
        this.verificationFailure = Objects.requireNonNull(
                verificationFailure, "verificationFailure");
    }

    public String bindingId() {
        return bindingId;
    }

    public String ownerWorkloadId() {
        return ownerWorkloadId;
    }

    public String callerWorkloadId() {
        return callerWorkloadId;
    }

    public String method() {
        return method;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    public String purpose() {
        return purpose;
    }

    public String path(UUID requestPublicId) {
        if (!pathTemplate.contains("{requestPublicId}")) {
            if (requestPublicId != null) {
                throw new IllegalArgumentException(
                        "requestPublicId is forbidden for an exact endpoint");
            }
            return pathTemplate;
        }
        return pathTemplate.replace(
                "{requestPublicId}", Objects.requireNonNull(requestPublicId).toString());
    }

    public boolean matchesPath(String path) {
        if (!pathTemplate.contains("{requestPublicId}")) {
            return pathTemplate.equals(path);
        }
        return path != null && CALLBACK_PATH.matcher(path).matches();
    }

    public String commandDigest(String exactPath, long tenantId, String bodySha256) {
        if (!matchesPath(exactPath) || exactPath.indexOf('?') >= 0) {
            throw new IllegalArgumentException("exactPath does not match endpoint");
        }
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        requireDigest(bodySha256, "bodySha256");
        String material = "HRIS-BRIDGE-V2\n"
                + bindingId + "\n"
                + method + "\n"
                + exactPath + "\n"
                + callerWorkloadId + "\n"
                + ownerWorkloadId + "\n"
                + tenantId + "\n"
                + purpose + "\n"
                + "application/json\n"
                + bodySha256;
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    RuntimeException denied(String reason) {
        return Objects.requireNonNull(
                deniedFailure.apply(reason), "denied verification failure");
    }

    boolean isVerificationFailure(RuntimeException failure) {
        return verificationFailure.test(failure);
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HrisApprovalVerificationContractV2 that)) {
            return false;
        }
        return bindingId.equals(that.bindingId)
                && ownerWorkloadId.equals(that.ownerWorkloadId)
                && callerWorkloadId.equals(that.callerWorkloadId)
                && method.equals(that.method)
                && pathTemplate.equals(that.pathTemplate)
                && purpose.equals(that.purpose);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                bindingId, ownerWorkloadId, callerWorkloadId,
                method, pathTemplate, purpose);
    }
}
