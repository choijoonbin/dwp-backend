package com.dwp.platform.contracts.hris.capability.v1;

/** Fail-closed exception raised before an affected provider operation can execute. */
public final class OptionalCapabilityAdmissionExceptionV1 extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final OptionalCapabilityAdmissionErrorV1 error;

    public OptionalCapabilityAdmissionExceptionV1(OptionalCapabilityAdmissionErrorV1 error) {
        super(error == null ? "optional capability admission denied" : error.code().name());
        if (error == null) {
            throw new IllegalArgumentException("typed admission error is required");
        }
        this.error = error;
    }

    public OptionalCapabilityAdmissionErrorV1 error() {
        return error;
    }
}
