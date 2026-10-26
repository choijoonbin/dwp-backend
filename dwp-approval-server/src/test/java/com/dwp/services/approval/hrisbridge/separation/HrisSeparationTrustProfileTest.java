package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HrisSeparationTrustProfileTest {
    @Test
    void admitsOnlyTls13WithTheExactPeopleUriSanAndClosedHeaders() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        doNothing().when(certificate).checkValidity();
        when(certificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, HrisSeparationTrustProfile.PEOPLE_SPIFFE)));
        var request = secure(certificate);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-people-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID",
                "00000000-0000-4000-8000-000000000001");

        var profile = new HrisSeparationTrustProfile();
        assertThat(profile.authenticate(request).workloadId())
                .isEqualTo("dwp-people-server");
        assertThat(profile.applicationHeaders(request)).hasSize(5);

        request.addHeader("Authorization", "Bearer forbidden");
        assertThatThrownBy(() -> profile.authenticate(request))
                .isInstanceOf(HrisSeparationProtocolException.class);
    }

    @Test
    void rejectsInsecureTransportAndAdditionalUriSans() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        doNothing().when(certificate).checkValidity();
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, HrisSeparationTrustProfile.PEOPLE_SPIFFE),
                List.of(6, "spiffe://dwp/service/other")));
        var request = secure(certificate);
        assertThatThrownBy(() -> new HrisSeparationTrustProfile()
                .authenticate(request)).isInstanceOf(
                        HrisSeparationProtocolException.class);
        request.setSecure(false);
        assertThatThrownBy(() -> new HrisSeparationTrustProfile()
                .authenticate(request)).isInstanceOf(
                        HrisSeparationProtocolException.class);
    }

    @Test
    void certificateAuthenticationPrecedesClosedApplicationHeaderValidation() {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(HrisSeparationTrustProfile.TLS_PROTOCOL, "TLSv1.3");
        request.addHeader("Authorization", "must-not-be-inspected-first");

        var failure = catchThrowableOfType(
                HrisSeparationProtocolException.class,
                () -> new HrisSeparationTrustProfile().authenticate(request));

        assertThat(failure.failure().errorCode()).isEqualTo(
                com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1
                        .ErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static MockHttpServletRequest secure(X509Certificate certificate) {
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(HrisSeparationTrustProfile.TLS_PROTOCOL, "TLSv1.3");
        request.setAttribute(HrisSeparationTrustProfile.CERTIFICATES,
                new X509Certificate[] {certificate});
        return request;
    }
}
