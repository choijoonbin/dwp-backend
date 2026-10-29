package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.util.Objects;

/** Immutable receipt proof input detached from durable dispatch storage. */
public record Hrm013IdentityReceiptProofContext(
        byte[] exactCallbackBody,
        Hrm013WorkloadProofV1.BusinessAuthority authorityMode,
        Hrm013WorkloadProofV1.DrainClaims drainClaims) {
    public Hrm013IdentityReceiptProofContext {
        exactCallbackBody = exactCallbackBody == null ? null : exactCallbackBody.clone();
        Objects.requireNonNull(exactCallbackBody, "exactCallbackBody");
        Objects.requireNonNull(authorityMode, "authorityMode");
        if (authorityMode.drainExisting() != (drainClaims != null)) {
            throw new IllegalArgumentException("receipt drain authority");
        }
    }

    @Override
    public byte[] exactCallbackBody() {
        return exactCallbackBody.clone();
    }
}
