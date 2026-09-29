package com.dwp.platform.contracts.hris.capability.v1;

/** Operation receives only the bindings admitted for the selected capability dependency closure. */
@FunctionalInterface
public interface OptionalCapabilityOperationV1<T> {
    T execute(OptionalCapabilityAdmissionDecisionV1 admission);
}
