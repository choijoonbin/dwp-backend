package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Hrm013TimeWorkloadCredentialProviderTest {
    @Test
    void absentAtomicRuntimeLeaseFailsClosedWithoutFallbackCredentials() {
        AtomicReference<String> requestedBinding = new AtomicReference<>();
        var provider = new Hrm013TimeWorkloadCredentialProvider(
                (tenant, binding, role) -> {
                    requestedBinding.set(binding + ":" + role);
                    return null;
                }, new ObjectMapper(), Clock.fixed(
                        Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> provider.current(
                7, "SVC-AUT-HRM-015",
                Hrm013TimeWorkloadCredentialProvider.ServiceRole.CALLER))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
        assertThat(requestedBinding).hasValue("SVC-AUT-HRM-015:CALLER");
    }
}
