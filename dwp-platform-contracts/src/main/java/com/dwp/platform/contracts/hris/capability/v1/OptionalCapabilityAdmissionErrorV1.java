package com.dwp.platform.contracts.hris.capability.v1;

import java.io.Serial;
import java.io.Serializable;

/** Typed, secret-free failure returned at the pre-call boundary. */
public record OptionalCapabilityAdmissionErrorV1(
        Code code,
        String requestedCapabilityId,
        String operation,
        String failingCapabilityId,
        String failureCode) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    public enum Code {
        INVALID_CONFIGURATION,
        DUPLICATE_CAPABILITY,
        UNKNOWN_PREREQUISITE,
        DEPENDENCY_CYCLE,
        OPERATION_COLLISION,
        FORBIDDEN_BINDING_DEPENDENCY,
        INVALID_REQUEST,
        UNKNOWN_CAPABILITY,
        UNAUTHORIZED_CONSUMER,
        UNKNOWN_OPERATION,
        CAPABILITY_UNINSTALLED,
        CAPABILITY_DISABLED,
        VERSION_INCOMPATIBLE,
        REQUIRED_BINDING_MISSING,
        PREREQUISITE_NOT_READY
    }

    public OptionalCapabilityAdmissionErrorV1 {
        if (code == null || requestedCapabilityId == null || operation == null
                || failingCapabilityId == null || failureCode == null) {
            throw new IllegalArgumentException("typed admission error fields are required");
        }
    }
}
