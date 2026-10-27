package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class Hrm013TimeTaskDispatchIngressFilterTest {
    @Test
    void admitsOnlyTheClosedFiveApplicationHeaderEnvelope() {
        MockHttpServletRequest request = request();

        var headers = Hrm013TimeTaskDispatchIngressFilter.exactHeaders(request);

        assertThat(headers).hasSize(5);
        assertThat(headers).extracting(value -> value.name().toLowerCase())
                .containsExactlyInAnyOrder("content-type", "accept",
                        "x-dwp-service-identity", "x-dwp-workload-proof",
                        "x-correlation-id");
    }

    @Test
    void rejectsAmbientAuthorizationAndTenantHeaders() {
        MockHttpServletRequest authorization = request();
        authorization.addHeader("Authorization", "Bearer forbidden");
        assertDenied(authorization);

        MockHttpServletRequest tenant = request();
        tenant.addHeader("X-Tenant-ID", "99");
        assertDenied(tenant);
    }

    @Test
    void recognizesTheTomcatTls13AttributeAndRequiresACompleteRevocationPolicy() {
        MockHttpServletRequest request = request();
        request.setSecure(true);
        request.setAttribute("org.apache.tomcat.util.net.secure_protocol_version",
                "TLSv1.3");
        assertThat(Hrm013TimeTaskDispatchIngressFilter.protocol(request))
                .isEqualTo("TLSv1.3");

        TrustAnchor anchor = new TrustAnchor(mock(X509Certificate.class), null);
        assertThatThrownBy(() -> new Hrm013TimeTaskDispatchIngressFilter
                .PkixClientCertificateValidator(Set.of(anchor), List.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revocation policy");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/hris-time/v1/separation-task-dispatches");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-people-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        return request;
    }

    private static void assertDenied(MockHttpServletRequest request) {
        assertThatThrownBy(() -> Hrm013TimeTaskDispatchIngressFilter.exactHeaders(request))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }
}
