package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class Hrm013RequesterIdentityIngressFilterTest {
    @Test
    void requesterPathRejectsQueryAndBorrowedBrowserAuthority() {
        MockHttpServletRequest request = request();
        request.setQueryString("person=forbidden");
        assertThatThrownBy(() ->
                HrisServiceGrantAuthorityIngressFilter.requireExactRequestLine(
                        request, Hrm013RequesterIdentityAuthorityV1.PATH))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);

        MockHttpServletRequest borrowed = request();
        borrowed.setQueryString(null);
        borrowed.addHeader("Authorization", "Bearer browser-token");
        assertThatThrownBy(() ->
                HrisServiceGrantAuthorityIngressFilter.exactApplicationHeaders(borrowed))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void requesterBodyIsBoundToCanonicalSharedContract() throws Exception {
        var expected = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                17, new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));
        byte[] body = Hrm013RequesterIdentityAuthorityV1.encodeRequest(
                new com.fasterxml.jackson.databind.ObjectMapper(), expected);
        MockHttpServletRequest request = request();
        request.setContent(body);

        byte[] read = HrisServiceGrantAuthorityIngressFilter.boundedBody(
                request, Hrm013RequesterIdentityAuthorityV1.MAX_REQUEST_BYTES);
        assertThat(Hrm013RequesterIdentityAuthorityV1.decodeRequest(
                new com.fasterxml.jackson.databind.ObjectMapper(), read)).isEqualTo(expected);
    }

    @Test
    void missingTlsClientCertificateIsAnEmptyNoStoreAuthenticationChallenge()
            throws Exception {
        var filter = new Hrm013RequesterIdentityIngressFilter(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                java.time.Clock.systemUTC(),
                mock(HrisServiceGrantAuthorityIngressFilter
                        .ClientCertificateValidator.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, mock(jakarta.servlet.FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", Hrm013RequesterIdentityAuthorityV1.PATH);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-approval-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID", new UUID(0, 3).toString());
        return request;
    }
}
