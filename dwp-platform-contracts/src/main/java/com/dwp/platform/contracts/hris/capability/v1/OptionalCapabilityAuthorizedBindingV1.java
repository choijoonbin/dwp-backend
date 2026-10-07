package com.dwp.platform.contracts.hris.capability.v1;

import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.BindingKind;

/** Least-privilege binding reference returned only after admission. */
public record OptionalCapabilityAuthorizedBindingV1(String capabilityId, BindingKind kind) {
    public OptionalCapabilityAuthorizedBindingV1 {
        if (capabilityId == null || capabilityId.isBlank() || kind == null) {
            throw new IllegalArgumentException("authorized binding fields are required");
        }
    }
}
