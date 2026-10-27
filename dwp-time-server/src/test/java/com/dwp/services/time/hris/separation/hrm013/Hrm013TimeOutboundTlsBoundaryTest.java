package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Hrm013TimeOutboundTlsBoundaryTest {
    @Test
    void authAndPeopleTransportsPinHttpsTls13SpiffeDnsAndNoRedirects()
            throws IOException {
        String credential = source("Hrm013TimeWorkloadCredentialProvider.java");
        String auth = source("AuthHrm013ServiceAuthorityClient.java");
        String relay = source("Hrm013TimeTaskReceiptRelay.java");

        assertThat(credential)
                .contains("tls.setProtocols(new String[] {\"TLSv1.3\"})")
                .contains("tls.setEndpointIdentificationAlgorithm(\"HTTPS\")")
                .contains("followRedirects(HttpClient.Redirect.NEVER)");
        assertThat(auth)
                .contains("spiffe://dwp/service/dwp-auth-server")
                .contains("!\"TLSv1.3\".equals(value.tlsProtocol())")
                .contains("!endpoint.getHost().equals(value.peerDnsName())");
        assertThat(relay)
                .contains("spiffe://dwp/service/dwp-people-server")
                .contains("!\"TLSv1.3\".equals(value.tlsProtocol())")
                .contains("response.previousResponse().isPresent()");
    }

    private static String source(String name) throws IOException {
        Path path = Path.of("dwp-time-server/src/main/java/com/dwp/services/time/hris/"
                + "separation/hrm013/" + name);
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return Files.readString(path);
    }
}
