package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Synthetic local HTTPS protocol checks, not a real vendor production/probe or a production trust override. */
class SignatureProviderHttpTransportTest {
    private static final String HOST = "provider.example.com";
    private HttpsServer server;
    private SSLContext trustedTestContext;
    private final AtomicInteger hits = new AtomicInteger(), resolved = new AtomicInteger();
    private final AtomicReference<String> hostHeader = new AtomicReference<>(), sni = new AtomicReference<>(), cookie = new AtomicReference<>();

    @BeforeEach void startSyntheticHttpsServerWithAnExplicitTestCa() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048); var keyPair = keys.generateKeyPair();
        var bc = new BouncyCastleProvider(); var subject = new X500Name("CN=" + HOST);
        var builder = new JcaX509v3CertificateBuilder(subject, java.math.BigInteger.ONE,
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)), subject, keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, HOST)));
        X509Certificate cert = new JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keyPair.getPrivate())));
        var keyStore = KeyStore.getInstance("PKCS12"); keyStore.load(null); char[] password = "synthetic-test-only".toCharArray();
        keyStore.setKeyEntry("server", keyPair.getPrivate(), password, new X509Certificate[]{cert});
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keyStore, password);
        var serverContext = SSLContext.getInstance("TLS"); serverContext.init(km.getKeyManagers(), null, null);
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null); trust.setCertificateEntry("test-ca", cert);
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
        trustedTestContext = SSLContext.getInstance("TLS"); trustedTestContext.init(null, tm.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            hits.incrementAndGet(); hostHeader.set(exchange.getRequestHeaders().getFirst("Host")); cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            var session = (javax.net.ssl.ExtendedSSLSession) ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession();
            sni.set(session.getRequestedServerNames().stream().filter(name -> name instanceof SNIHostName)
                    .map(name -> ((SNIHostName) name).getAsciiName()).findFirst().orElse(null));
            String path = exchange.getRequestURI().getPath(); int status = path.equals("/redirect") ? 302 : path.equals("/unavailable") ? 503 : 200;
            if (status == 302) exchange.getResponseHeaders().set("Location", "https://other.example.com/never");
            exchange.getResponseHeaders().set("Set-Cookie", "secret=must-not-replay");
            if (path.equals("/encoded")) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            if (path.equals("/slow")) {
                exchange.sendResponseHeaders(200, 0);
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                try (var out = exchange.getResponseBody()) { out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                return;
            }
            if (path.equals("/oversized")) {
                exchange.sendResponseHeaders(200, SignatureProviderHttpTransport.MAX_BYTES + 1);
                try (var out = exchange.getResponseBody()) { out.write(new byte[SignatureProviderHttpTransport.MAX_BYTES + 1]); }
                return;
            }
            if (path.equals("/oversized-chunked")) {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) { out.write(new byte[SignatureProviderHttpTransport.MAX_BYTES + 64]); }
                return;
            }
            byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    @AfterEach void stopOnlyOurServer() { if (server != null) server.stop(0); }

    @Test void actualApacheConnectUsesPinnedIpWhileKeepingOriginalHostnameForSniAndHost() throws Exception {
        try (var manager = SignatureProviderHttpTransport.connections(syntheticDns(HOST), trustedTls());
             var client = SignatureProviderHttpTransport.client(manager)) {
            assertThat(client.<Integer>execute(new HttpGet(uri(HOST, "/echo")), response -> { EntityUtils.consume(response.getEntity()); return response.getCode(); })).isEqualTo(200);
        }
        assertThat(resolved.get()).isPositive(); assertThat(hits.get()).isEqualTo(1);
        assertThat(hostHeader.get()).isEqualTo(HOST + ":" + server.getAddress().getPort()); assertThat(sni.get()).isEqualTo(HOST);
    }

    @Test void defaultProductionTlsRejectsAnUntrustedCertificateBeforeBusinessHttp() throws Exception {
        try (var manager = SignatureProviderHttpTransport.connections(syntheticDns(HOST), DefaultClientTlsStrategy.createDefault());
             var client = SignatureProviderHttpTransport.client(manager)) {
            assertThatThrownBy(() -> client.execute(new HttpGet(uri(HOST, "/echo")), response -> response.getCode()))
                    .isInstanceOf(javax.net.ssl.SSLHandshakeException.class);
        }
        assertThat(resolved.get()).isPositive(); assertThat(hits.get()).isZero();
    }

    @Test void aTrustedCertificateStillCannotBypassHostnameValidation() throws Exception {
        String wrong = "other.example.com";
        try (var manager = SignatureProviderHttpTransport.connections(syntheticDns(wrong), trustedTls());
             var client = SignatureProviderHttpTransport.client(manager)) {
            assertThatThrownBy(() -> client.execute(new HttpGet(uri(wrong, "/echo")), response -> response.getCode()))
                    .isInstanceOf(java.io.IOException.class);
        }
        assertThat(hits.get()).isZero();
    }

    @Test void neverFollowsRedirectsRetries503OrReplaysCookies() throws Exception {
        try (var manager = SignatureProviderHttpTransport.connections(syntheticDns(HOST), trustedTls());
             var client = SignatureProviderHttpTransport.client(manager)) {
            for (var target : new String[]{"/redirect", "/unavailable", "/echo"}) {
                int expected = target.equals("/redirect") ? 302 : target.equals("/unavailable") ? 503 : 200;
                assertThat(client.<Integer>execute(new HttpGet(uri(HOST, target)), response -> { EntityUtils.consume(response.getEntity()); return response.getCode(); })).isEqualTo(expected);
                assertThat(cookie.get()).isNull();
            }
        }
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test void boundedProductionResponseHandlingRejectsOversizeEncodedAndRedirectReplies() throws Exception {
        try (var transport = new SignatureProviderHttpTransport()) {
            for (String path : new String[]{"/oversized", "/oversized-chunked", "/encoded", "/redirect"})
                assertThatThrownBy(() -> transport.executePinned(new HttpGet(uri(HOST, path)), syntheticDns(HOST), trustedTls(),
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)))
                        .isInstanceOf(java.io.IOException.class);
        }
        assertThat(hits.get()).isEqualTo(4);
    }

    @Test void completeCallDeadlineCancelsASlowBodyInsteadOfResettingPerReadTimeout() throws Exception {
        long start = System.nanoTime();
        try (var transport = new SignatureProviderHttpTransport()) {
            assertThatThrownBy(() -> transport.executePinned(new HttpGet(uri(HOST, "/slow")), syntheticDns(HOST), trustedTls(),
                    start + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500))).isInstanceOf(java.io.IOException.class);
        }
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test void responseBytesAreDefensiveAndADefaultDeadlineCannotBeExtended() throws Exception {
        try (var transport = new SignatureProviderHttpTransport()) {
            var response = transport.executePinned(new HttpGet(uri(HOST, "/echo")), syntheticDns(HOST), trustedTls(),
                    System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
            byte[] altered = response.body(); altered[0] = 0; assertThat(response.body()[0]).isEqualTo((byte) '{');
            assertThatThrownBy(() -> transport.executePinned(new HttpGet(uri(HOST, "/echo")), syntheticDns(HOST), trustedTls(),
                    System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6))).isInstanceOf(java.io.IOException.class);
        }
        assertThat(hits.get()).isEqualTo(1);
    }

    private DefaultClientTlsStrategy trustedTls() {
        return new DefaultClientTlsStrategy(trustedTestContext, HostnameVerificationPolicy.BOTH, new DefaultHostnameVerifier());
    }
    private URI uri(String host, String path) { return URI.create("https://" + host + ":" + server.getAddress().getPort() + path); }
    private DnsResolver syntheticDns(String expectedHost) {
        return new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                if (!expectedHost.equals(host)) throw new java.net.UnknownHostException("Synthetic host changed");
                resolved.incrementAndGet(); return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            }
            @Override public String resolveCanonicalHostname(String host) { return expectedHost; }
        };
    }
}
