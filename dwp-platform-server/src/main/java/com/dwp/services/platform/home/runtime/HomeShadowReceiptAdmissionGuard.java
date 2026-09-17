package com.dwp.services.platform.home.runtime;

interface HomeShadowReceiptAdmissionGuard {

    Admission admit(
            long tenantId,
            long userId,
            String decisionRevision,
            HomeShadowReceiptController.ShadowReceiptRequest request);

    enum Admission {
        ADMITTED,
        DUPLICATE,
        RATE_LIMITED,
        CAPACITY_REJECTED,
        UNAVAILABLE,
        DISABLED
    }
}
