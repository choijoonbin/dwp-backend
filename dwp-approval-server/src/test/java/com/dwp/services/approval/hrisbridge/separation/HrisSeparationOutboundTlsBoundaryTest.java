package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class HrisSeparationOutboundTlsBoundaryTest {
    private static final String PATH = "/internal/auth/v1/example";

    @Test
    void acceptsOnlyHttpsExactPathAndTls13WithExactUriAndDnsSans() throws Exception {
        URI endpoint = URI.create("https://auth.internal" + PATH);
        HrisSeparationWorkloadCredentialProvider.requireEndpoint(endpoint, PATH);
        assertThatThrownBy(() -> HrisSeparationWorkloadCredentialProvider
                .requireEndpoint(URI.create("http://auth.internal" + PATH), PATH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HrisSeparationWorkloadCredentialProvider
                .requireEndpoint(URI.create(endpoint + "?alias=1"), PATH))
                .isInstanceOf(IllegalArgumentException.class);

        X509Certificate leaf = mock(X509Certificate.class);
        doNothing().when(leaf).checkValidity();
        when(leaf.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, "spiffe://dwp/service/dwp-auth-server"),
                List.of(2, "auth.internal")));
        SSLSession session = mock(SSLSession.class);
        when(session.getProtocol()).thenReturn("TLSv1.3");
        when(session.getPeerCertificates()).thenReturn(
                new java.security.cert.Certificate[] {leaf});
        @SuppressWarnings("unchecked") HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.sslSession()).thenReturn(Optional.of(session));

        assertThatCode(() -> HrisSeparationWorkloadCredentialProvider.requirePeer(
                response, endpoint, "spiffe://dwp/service/dwp-auth-server"))
                .doesNotThrowAnyException();
        when(session.getProtocol()).thenReturn("TLSv1.2");
        assertThatThrownBy(() -> HrisSeparationWorkloadCredentialProvider.requirePeer(
                response, endpoint, "spiffe://dwp/service/dwp-auth-server"))
                .isInstanceOf(HrisSeparationProtocolException.class);
    }
}
