package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class Hrm013PayrollTaskDispatchIngressFilterTest {
    @Test
    void acceptsOnlyTheClosedFiveHeaderApplicationEnvelope() {
        MockHttpServletRequest request = exactRequest();
        assertThat(Hrm013PayrollTaskDispatchIngressFilter.exactHeaders(request))
                .extracting(value -> value.name().toLowerCase())
                .containsExactlyInAnyOrder("content-type", "accept",
                        "x-dwp-service-identity", "x-dwp-workload-proof",
                        "x-correlation-id");
    }

    @Test
    void rejectsBrowserOrBearerAuthorityHeaders() {
        MockHttpServletRequest request = exactRequest();
        request.addHeader("Authorization", "Bearer forbidden");
        assertThatThrownBy(() ->
                Hrm013PayrollTaskDispatchIngressFilter.exactHeaders(request))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
    }

    private static MockHttpServletRequest exactRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-people-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID",
                "d6990436-76cf-4eb7-b97d-a98da96979fa");
        return request;
    }
}
