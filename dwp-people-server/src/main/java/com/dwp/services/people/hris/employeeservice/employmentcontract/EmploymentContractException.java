package com.dwp.services.people.hris.employeeservice.employmentcontract;

/** Typed fail-closed error used by the HRM-011 owner boundary. */
public final class EmploymentContractException extends RuntimeException {

    private final String code;
    private final int status;
    private final Long currentVersion;
    private final boolean retryable;

    private EmploymentContractException(
            String code, int status, String message, Long currentVersion,
            boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.currentVersion = currentVersion;
        this.retryable = retryable;
    }

    public static EmploymentContractException invalid(String message) {
        return new EmploymentContractException(
                "EMPLOYMENT_CONTRACT_INVALID_REQUEST", 400, message, null, false);
    }

    public static EmploymentContractException denied(String message) {
        return new EmploymentContractException(
                "EMPLOYMENT_CONTRACT_FORBIDDEN", 403, message, null, false);
    }

    public static EmploymentContractException conflict(String code, String message) {
        return new EmploymentContractException(code, 409, message, null, false);
    }

    public static EmploymentContractException stale(long currentVersion) {
        return new EmploymentContractException(
                "EMPLOYMENT_CONTRACT_VERSION_CONFLICT", 409,
                "The work relationship resourceVersion is stale.",
                currentVersion, false);
    }

    public static EmploymentContractException unavailable(String message) {
        return new EmploymentContractException(
                "EMPLOYMENT_CONTRACT_ARTIFACT_UNAVAILABLE", 503,
                message, null, true);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public Long currentVersion() {
        return currentVersion;
    }

    public boolean retryable() {
        return retryable;
    }
}
