package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class HrisSeparationDecisionRelayTest {
    @Test
    void configurationRejectsPlaintextEndpointsAndUnboundedClaims() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var credentials = mock(HrisSeparationWorkloadCredentialProvider.class);
        assertThatThrownBy(() -> new HrisSeparationDecisionRelay(jdbc, manager,
                credentials, URI.create("http://people.internal"), "worker", 25,
                30, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HrisSeparationDecisionRelay(jdbc, manager,
                credentials, URI.create("https://people.internal"), "worker", 0,
                30, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HrisSeparationDecisionRelay(jdbc, manager,
                credentials, URI.create("https://people.internal"), "worker", 25,
                301, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postSendPeerFailureClosesTheBodyAndRemainsAmbiguous()
            throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream body = mock(InputStream.class);
        SSLSession session = mock(SSLSession.class);
        when(response.body()).thenReturn(body);
        when(response.sslSession()).thenReturn(Optional.of(session));
        when(session.getProtocol()).thenReturn("TLSv1.2");

        assertThatThrownBy(() -> HrisSeparationDecisionRelay
                .requireTrustedPeerAfterSend(response,
                        URI.create("https://people.internal/internal/hris/v1"),
                        "spiffe://dwp/service/dwp-people-server"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("POST_SEND_PEER_IDENTITY_UNTRUSTED");
        verify(body).close();
    }
}
