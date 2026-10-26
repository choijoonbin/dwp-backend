package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HrisServiceGrantAuthorityIngressFilterTest {
    private static final Clock CERTIFICATE_CLOCK = Clock.fixed(
            Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void acceptsOnlyTheFiveApplicationHeadersAndExactRequestLine() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Host", "auth.internal");
        request.addHeader("Content-Length", "2");
        request.addHeader("Connection", "keep-alive");
        request.addHeader("User-Agent", "dwp-runtime/1");

        HrisServiceGrantAuthorityIngressFilter.requireExactRequestLine(
                request, HrisServiceGrantAuthorityV1.PATH);
        assertThat(HrisServiceGrantAuthorityIngressFilter.exactApplicationHeaders(request))
                .hasSize(5);
        assertThat(HrisServiceGrantAuthorityIngressFilter.boundedBody(
                request, HrisServiceGrantAuthorityV1.MAX_REQUEST_BYTES))
                .isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsRoutingOverrideHeadersBeforeCertificateAndProofParsing() throws Exception {
        var proofVerifier = mock(HrisServiceGrantAuthorityProofVerifier.class);
        var certificateValidator = mock(
                HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator.class);
        FilterChain chain = mock(FilterChain.class);
        var filter = new HrisServiceGrantAuthorityIngressFilter(
                new ObjectMapper(), Clock.systemUTC(), proofVerifier, certificateValidator);

        for (String unapproved : new String[] {
                "X-HTTP-Method-Override", "X-Original-URL", "X-Rewrite-URL",
                "Accept-Encoding", "Accept-Language", "Cache-Control", "Pragma",
                "Traceparent", "Tracestate", "Baggage"}) {
            MockHttpServletRequest request = request();
            request.addHeader(unapproved, "/internal/alternate");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
        }
        verifyNoInteractions(certificateValidator, proofVerifier, chain);
    }

    @Test
    void enforcesTheInclusiveSingleApplicationHeaderByteBoundary() {
        String header = Hrm013WorkloadProofV1.WORKLOAD_PROOF_HEADER;
        int boundaryValueBytes = Hrm013WorkloadProofV1.MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES
                - header.getBytes(StandardCharsets.US_ASCII).length;
        MockHttpServletRequest boundary = request();
        boundary.removeHeader(header);
        boundary.addHeader(header, "a".repeat(boundaryValueBytes));
        assertThat(HrisServiceGrantAuthorityIngressFilter.exactApplicationHeaders(boundary))
                .hasSize(5);

        MockHttpServletRequest oversized = request();
        oversized.removeHeader(header);
        oversized.addHeader(header, "a".repeat(boundaryValueBytes + 1));
        assertThatThrownBy(() ->
                HrisServiceGrantAuthorityIngressFilter.exactApplicationHeaders(oversized))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void rejectsBorrowedIdentityForwardingAndEncodingHeadersBeforeProof() {
        for (String forbidden : new String[] {
                "Authorization", "Cookie", "Forwarded", "X-Forwarded-Client-Cert",
                "X-Tenant-Id", "X-DWP-Roles", "Content-Encoding", "Origin"}) {
            MockHttpServletRequest request = request();
            request.addHeader(forbidden, "forbidden");
            assertThatThrownBy(() ->
                    HrisServiceGrantAuthorityIngressFilter.exactApplicationHeaders(request))
                    .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        }
    }

    @Test
    void rejectsQueryMethodAndPathAliases() {
        MockHttpServletRequest query = request();
        query.setQueryString("tenant=17");
        assertThatThrownBy(() ->
                HrisServiceGrantAuthorityIngressFilter.requireExactRequestLine(
                        query, HrisServiceGrantAuthorityV1.PATH))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);

        MockHttpServletRequest alias = request();
        alias.setRequestURI(HrisServiceGrantAuthorityV1.PATH + "/");
        assertThatThrownBy(() ->
                HrisServiceGrantAuthorityIngressFilter.requireExactRequestLine(
                        alias, HrisServiceGrantAuthorityV1.PATH))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void validatesAnActualTrustedUnrevokedClientCertificatePath() throws Exception {
        X509Certificate ca = certificate(TRUSTED_CA_PEM);
        X509Certificate leaf = certificate(VALID_CLIENT_PEM);
        var validator = validator(ca, crl(EMPTY_CRL_PEM));

        assertThat(validator.validate(new X509Certificate[] {leaf, ca}, CERTIFICATE_CLOCK))
                .isEqualTo(leaf);
    }

    @Test
    void rejectsAnActuallyRevokedClientCertificate() throws Exception {
        X509Certificate ca = certificate(TRUSTED_CA_PEM);
        X509Certificate leaf = certificate(VALID_CLIENT_PEM);
        var validator = validator(ca, crl(REVOKED_CRL_PEM));

        assertThatThrownBy(() -> validator.validate(
                new X509Certificate[] {leaf, ca}, CERTIFICATE_CLOCK))
                .isInstanceOf(java.security.cert.CertPathValidatorException.class);
    }

    @Test
    void rejectsAnActualChainIssuedByAnUntrustedCa() throws Exception {
        X509Certificate trustedCa = certificate(TRUSTED_CA_PEM);
        X509Certificate otherCa = certificate(UNTRUSTED_CA_PEM);
        X509Certificate leaf = certificate(UNTRUSTED_CLIENT_PEM);
        var validator = validator(trustedCa, crl(EMPTY_CRL_PEM));

        assertThatThrownBy(() -> validator.validate(
                new X509Certificate[] {leaf, otherCa}, CERTIFICATE_CLOCK))
                .isInstanceOf(java.security.cert.CertPathValidatorException.class);
    }

    @Test
    void rejectsAnActualTrustedLeafWithServerAuthInsteadOfClientAuth() throws Exception {
        X509Certificate ca = certificate(TRUSTED_CA_PEM);
        X509Certificate leaf = certificate(WRONG_EKU_CLIENT_PEM);
        var validator = validator(ca, crl(EMPTY_CRL_PEM));

        assertThatThrownBy(() -> validator.validate(
                new X509Certificate[] {leaf, ca}, CERTIFICATE_CLOCK))
                .isInstanceOf(java.security.cert.CertificateException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void rejectsLeafWithoutDigitalSignatureOrClientAuthBeforePkix() throws Exception {
        var validator = validator();
        X509Certificate leaf = mock(X509Certificate.class);
        RSAPublicKey rsa = mock(RSAPublicKey.class);
        when(rsa.getModulus()).thenReturn(BigInteger.ONE.shiftLeft(2047));
        when(leaf.getPublicKey()).thenReturn(rsa);
        when(leaf.getBasicConstraints()).thenReturn(-1);
        when(leaf.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        when(leaf.getKeyUsage()).thenReturn(new boolean[] {false});
        assertThatThrownBy(() -> validator.validate(
                new X509Certificate[] {leaf}, Clock.systemUTC()))
                .isInstanceOf(java.security.cert.CertificateException.class)
                .hasMessageContaining("profile");

        when(leaf.getKeyUsage()).thenReturn(new boolean[] {true});
        when(leaf.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.1"));
        assertThatThrownBy(() -> validator.validate(
                new X509Certificate[] {leaf}, Clock.systemUTC()))
                .isInstanceOf(java.security.cert.CertificateException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void acceptsOnlyOneExactSpiffeUriSan() throws Exception {
        X509Certificate leaf = mock(X509Certificate.class);
        when(leaf.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, "spiffe://dwp/service/dwp-people-server")));
        HrisServiceGrantAuthorityIngressFilter.requireSingleSpiffeUri(
                leaf, "spiffe://dwp/service/dwp-people-server");

        when(leaf.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, "spiffe://dwp/service/dwp-people-server"),
                List.of(6, "spiffe://dwp/service/other")));
        assertThatThrownBy(() ->
                HrisServiceGrantAuthorityIngressFilter.requireSingleSpiffeUri(
                        leaf, "spiffe://dwp/service/dwp-people-server"))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
            validator() {
        X509Certificate ca = mock(X509Certificate.class);
        return new HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator(
                Set.of(new TrustAnchor(ca, null)),
                HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                        .RevocationMode.CRL,
                List.of(mock(X509CRL.class)), null);
    }

    private static HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
            validator(X509Certificate ca, X509CRL crl) {
        return new HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator(
                Set.of(new TrustAnchor(ca, null)),
                HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                        .RevocationMode.CRL,
                List.of(crl), null);
    }

    static X509Certificate certificate(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static X509CRL crl(String pem) throws Exception {
        return (X509CRL) CertificateFactory.getInstance("X.509")
                .generateCRL(new ByteArrayInputStream(
                        pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", HrisServiceGrantAuthorityV1.PATH);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-people-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID", new UUID(0, 1).toString());
        return request;
    }

    static final String TRUSTED_CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDBTCCAe2gAwIBAgIUfYxtWjWKl+HHcxQVQRCVmQWpKdowDQYJKoZIhvcNAQEL
            BQAwGTEXMBUGA1UEAwwOSFJNMDEzIFRlc3QgQ0EwHhcNMjYxMDI2MTkyMjE0WhcN
            MzYxMDIzMTkyMjE0WjAZMRcwFQYDVQQDDA5IUk0wMTMgVGVzdCBDQTCCASIwDQYJ
            KoZIhvcNAQEBBQADggEPADCCAQoCggEBAMVdcciCFZjMq86KkAhMngCUolsPPl1c
            9Z6P2bczhR+SnAu6m3NPld0YOb4l9AHVYaUCKyi6NO1gYHT6Yh23InAPoyHlzxtd
            mP285Yd9yV1r1dNsI679OgYfiqIaYYEZSzzo/QudUy7vcokHACWyOT41gHik/8wt
            DD4SRx1jzQzBluoHTu334gR0vENnt5pxH+jUxRTpvcafA/w+oc6GsDnYEoK1uZde
            2Xxic3An1q8qtZQn/pOwqY4wpDvic7VN1BhPUlnLZk/sKvgwNljSz3amu0aiKD53
            Rz4TcQ21Mvlp5uWR8eizCBeqle99fl0nLJ5gO8gb9MNzDO04oLPntMUCAwEAAaNF
            MEMwEgYDVR0TAQH/BAgwBgEB/wIBADAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYE
            FHLxgZTHXCuhMmmwXoyRHORp3MxQMA0GCSqGSIb3DQEBCwUAA4IBAQBQbUJlox3U
            DJJ2wo6SZIDEr1yPEOYGUv5YrGJk9utangvSmTiHwzuqQa9evFilcGragWJMw4pV
            1sJHAYTgYrIVa4fxTkHr+GhZ4GDGoMUHkeZN0++24LiwqgqFW4JgHMvgLN4rGj6f
            tKDTkmxNJVDkO/Y/l/AJSF+U1HHkk5G+C7ChODbu/Rk4PxyEpkgPomlDPNoHoMjF
            0Hzbk6zTgb1Ab55dCieCoesL7jFRuBsmtlaEOH+QtalQk6rH7YJElC8o6Uifa3Y8
            sA5hgxWbhiFG+BVpk1GqlqkvFTcn2eJm63I/Ybv+AziukKQOIRx/s3otKbisx9Tu
            onX07DDgMZPT
            -----END CERTIFICATE-----
            """;

    private static final String VALID_CLIENT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDYDCCAkigAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwGTEXMBUGA1UEAwwOSFJN
            MDEzIFRlc3QgQ0EwHhcNMjYxMDI2MTkyMjE0WhcNMzYxMDIzMTkyMjE0WjAeMRww
            GgYDVQQDDBNIUk0wMTMgVmFsaWQgQ2xpZW50MIIBIjANBgkqhkiG9w0BAQEFAAOC
            AQ8AMIIBCgKCAQEAzKHETeev7lygytKqgL9OK9zkrnCyTDa9rLiQqsy+FcrZCkcO
            xBdEatzhouG3fivmeZupO/+sdALS5W4TOvjYUAWFluMM28ILjyW9U1cQHVu7nKmN
            +JhzjoNedIqMvfhr4cbaAVU3ZjmFc5bkasCKHqU2kjjMLuF6J0pBo21tiwn73itc
            aqaLME+ULxQudpssvE2KA02Ax5b9hNpmjQAe24IPgglR/drjes8U5dv83nUZupn6
            CpV9eb/pei7G37EqapCAKw6RzP1hMOmZakNd5ILBS6oQ5eH7RW/gVe3ZThNZzN9k
            gLe4aclQVl/N1yrbVD3jM+PmA8ZwH0FdD9SMAwIDAQABo4GsMIGpMAwGA1UdEwEB
            /wQCMAAwDgYDVR0PAQH/BAQDAgeAMBYGA1UdJQEB/wQMMAoGCCsGAQUFBwMCMDEG
            A1UdEQQqMCiGJnNwaWZmZTovL2R3cC9zZXJ2aWNlL2R3cC1wZW9wbGUtc2VydmVy
            MB0GA1UdDgQWBBSg2FM4piE3TmaWCBBeHqRqCvb6XDAfBgNVHSMEGDAWgBRy8YGU
            x1wroTJpsF6MkRzkadzMUDANBgkqhkiG9w0BAQsFAAOCAQEADa58OEJdn4kyeyFt
            FhwWN43ts1Eq+vLHsIqUwebp76hLCfHLtuUh3c085m2dVM4wPf6H9A6JZgH5WO9B
            2TvGyilYG/1dbSKBqt9ytpVxjQG0NqDuEMykgajt2Q5I5K/qcvv6EkLIjthUJ7PB
            EBJMO32mnCN78Nwd8AaOGC+Cn3ZDQpQ6ZCjtKi9StzqmyynkAP00p+D+JluKwSNT
            vgXRxCzh+b7Y0GX7SKmtdpI3nNY8wyv3vUdEn4nZvjjAYN3C61BwMMegmN8IpTGG
            TJk9SiE+JR0nU6yEuufgLx8SLUlyuwSVGK7X3+jVMo9F1D5GizPtuKoUgAPQvqr5
            eQ+PTA==
            -----END CERTIFICATE-----
            """;

    private static final String WRONG_EKU_CLIENT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDXTCCAkWgAwIBAgICEAEwDQYJKoZIhvcNAQELBQAwGTEXMBUGA1UEAwwOSFJN
            MDEzIFRlc3QgQ0EwHhcNMjYxMDI2MTkyMjE0WhcNMzYxMDIzMTkyMjE0WjAbMRkw
            FwYDVQQDDBBIUk0wMTMgV3JvbmcgRUtVMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
            MIIBCgKCAQEA0N3dpLwTJ14Bjy2MrdMWqCHlcdlhgjvM1N9AgB2eUKflRKKMHllR
            w2ffAv/Z+bklvAtFAvQwnemuoahqKCiFAGyrYzHLILaXwuxfsNDLfqzo9emMlK17
            3p/xaairHHc+PCFSxjOhTarROfXHrwgvbVGWDMfgtKK1hik9E1Cu8FmBFGmqAKch
            V6ThgD8sYmHxeSbEZK0gwuASXlxptcxDcbiOtWnn85g0YnY5E8UuLg5c1Oa1Tvfh
            BhXJiIzXfVLJliobRFEX+jMFwa1W5cfDvldRHJQnPe3k0tNCPlIimPaSS8nPspuB
            0Ni3x4wptdpfSJR8bN0pvqeDFNT3Uogc9QIDAQABo4GsMIGpMAwGA1UdEwEB/wQC
            MAAwDgYDVR0PAQH/BAQDAgeAMBYGA1UdJQEB/wQMMAoGCCsGAQUFBwMBMDEGA1Ud
            EQQqMCiGJnNwaWZmZTovL2R3cC9zZXJ2aWNlL2R3cC1wZW9wbGUtc2VydmVyMB0G
            A1UdDgQWBBS1N4dAJxFdLSz+ivmmwILv4JEpwzAfBgNVHSMEGDAWgBRy8YGUx1wr
            oTJpsF6MkRzkadzMUDANBgkqhkiG9w0BAQsFAAOCAQEAOZKxgLZxmV6hrkuAjwVI
            /QJ5sGo/nG/Ao5WfNxvb3NpsCFzu644s0XBPwl2fY8f034Uv7ibf4BPUTkkupKO5
            A/F3OvfNLRuCaBFoV1DvbzflgLXIwyGH1orEjZbkhRe91qLN/td7UAsSlNGFdOhq
            WW0A/Q9yIiZ6jYgwVv9lm5c76xb6/tKNEqMnEIJbBcbth+Fsf6yhRvfnWXM1XzAI
            QrCWKTm80M8bLTMVCelveMZHtaEb7Sb3b3+e+0RnHY5Ux/ee8ckamO0C/ZN4ot/Q
            EBV0RjeHXw2tADsNgfFOPhfd5VT3UOiss/JtG0Lpn4/gsmRSKFEK3KdQFPj+nI8m
            OA==
            -----END CERTIFICATE-----
            """;

    private static final String EMPTY_CRL_PEM = """
            -----BEGIN X509 CRL-----
            MIIBcjBcAgEBMA0GCSqGSIb3DQEBCwUAMBkxFzAVBgNVBAMMDkhSTTAxMyBUZXN0
            IENBFw0yNjEwMjYxOTIyMTRaFw0zNjEwMjMxOTIyMTRaoA8wDTALBgNVHRQEBAIC
            EAAwDQYJKoZIhvcNAQELBQADggEBAKN+WttH19tpzdT1+nWoqyWLCFarcThQaAFK
            MEW/l4FExDezn/Stppo9MWMBjg3OozkLbVeiWnqHiLq8A4NsBYMKWhYhgQn1bV/e
            EK/u6Yw6aYeXMsp71jNl+obM7q3s+NBSRANfeAkRI4KP66jm5rhpNlgMM91DLtF9
            UXlQH1hvnKlJr+w/9GL4RYyOvbaN4H64H7vkmuVOqxwfrmJOwqSLYQBEghercjQ/
            Gs+2ikIBXuyXNk4vWMm/Clw++M3l4nJxJzJiHxHSQKJ0t+g2xp9qWyK2WxuZivd8
            l4ZRyvZbMItW7nBEGLFhHKTixnAqllDMowHPBZYqG9xFObY848U=
            -----END X509 CRL-----
            """;

    private static final String REVOKED_CRL_PEM = """
            -----BEGIN X509 CRL-----
            MIIBiTBzAgEBMA0GCSqGSIb3DQEBCwUAMBkxFzAVBgNVBAMMDkhSTTAxMyBUZXN0
            IENBFw0yNjEwMjYxOTIyMTRaFw0zNjEwMjMxOTIyMTRaMBUwEwICEAAXDTI2MTAy
            NjE5MjIxNFqgDzANMAsGA1UdFAQEAgIQATANBgkqhkiG9w0BAQsFAAOCAQEAkJwl
            Wa8OJxcAoXTnuEwzVCsrvU9QNl/TJlA7bE2+QpWt6Vnu+gOEVYbbXjCpnpUnFem/
            4pamFcMvwU585TFTVv+iLbeI7805A1EFUEm4A68u14RHrAkS+IEuSO3+/SDbJSRd
            NnA2kf+tLzVFOV2l+s+Qq1g1blxMQVNAt+li/xHnZpzsFv7CFO8YNDOSVf+hwz6u
            gMBl+LEslmPOJcyaU8pn6+nm3BzPzbKtfKW4H24N6c/LWyUvrfTZ1dr+4istK1QU
            w5+pcCt6ZIEtAHGNPkUVzWtJid857eInMMtD0atvfZCfj6/8Idy8mHMIjQ/ZhSt9
            xwD6xrM7vGVnrCTCBA==
            -----END X509 CRL-----
            """;

    private static final String UNTRUSTED_CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDBzCCAe+gAwIBAgIUd7ZaqKFexxGYaCd48GME0kqRgawwDQYJKoZIhvcNAQEL
            BQAwGjEYMBYGA1UEAwwPSFJNMDEzIE90aGVyIENBMB4XDTI2MTAyNjE5MjIxNFoX
            DTM2MTAyMzE5MjIxNFowGjEYMBYGA1UEAwwPSFJNMDEzIE90aGVyIENBMIIBIjAN
            BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnKaKwBRBKjMw4oVnEsYDTcQxHMD3
            86JwfXCXNwNaKfVLyhQX2QJjytGpQzzzEaoQrCBYrloBsT9wqjD/shtUFIn2WdWE
            iSDd+7AK2Qziiu4eVIaOzFfCRMh6SWOvRGGnlU2hQACDiYQdSd/+0ZROG7/LLWVh
            KLjzXL82CYux5CfDbPFrIH+QLlmcGGbESUhZgvDlcokPCtrctfMZlxL9zgUcpjTM
            N54OlG/ZNwQrMDXGdksFgafKlrR2SjynpQR1SNGandnkkaYsJuAp/VKT7UN9f8sj
            Bh2dMSFbUfGxhgk4JPHIJD1BJloCn7NU5QCFao4y56tsUECqNjGmevp27wIDAQAB
            o0UwQzASBgNVHRMBAf8ECDAGAQH/AgEAMA4GA1UdDwEB/wQEAwIBBjAdBgNVHQ4E
            FgQU9GOKOU+ffjrb4O1nZmRz9q7gq8swDQYJKoZIhvcNAQELBQADggEBAA2FDK97
            K6NWo0i4v4cTdvlsWpF0OuH2t786c1YUQktHS6PNATIso6LoNqxePRi8mLpdZy9A
            bUyovXlBXA3Fm264w+bkt859t+UIIbAVVpSxijDlAmibTMJzgYPYI9fzxUx6BIzS
            8lUohyUYfyEsCGKFLXb0WFRdtdpvNedsE/R8RT9wBOnnxBqrXr+BSLkc1/MU+0NZ
            lJJ3gB9ctj7oTE/LwzCV2SoucttS2pJUd7aKkKlHYk4nh0tJwu9fWM6I0x+UPnhR
            AwONQxtc/Ol/XFUkaBVlQAKZbYUiOen97r9/7VseIoyFjBfMbJxaNUpqQ4Oz4ZTm
            73l/IVpb1IwS1UA=
            -----END CERTIFICATE-----
            """;

    private static final String UNTRUSTED_CLIENT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDdzCCAl+gAwIBAgIUVEsZLXZBTopVwOuyaQtsgrVDzVcwDQYJKoZIhvcNAQEL
            BQAwGjEYMBYGA1UEAwwPSFJNMDEzIE90aGVyIENBMB4XDTI2MTAyNjE5MjIxNFoX
            DTM2MTAyMzE5MjIxNFowIjEgMB4GA1UEAwwXSFJNMDEzIFVudHJ1c3RlZCBDbGll
            bnQwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCdh2OiCFsE8D6rLpJF
            1oj7QqlnF2/pAeP7lAnWstt2uJuwRG7Awk5jcWzWy7WfsNeAT5a0883Lml/EOWc6
            1zZOv0MR2U7ifOJVleEjpNB6okqkQnfVOIfc49kDhmmOEg0GpmWn5eHHEfYk/Bxx
            PwtnFOJnSyhtZW1l0sRyFvCMSgo5MbhHTuKh1zuvc2VpA6hUGtCbDPhE9e5bk7W8
            NY+hV2tmHf3ZnEMk9LAA04IkyYbX6I9K6ZmTxjYc2KWa06aEX14yZKzYx6aKyjPl
            Uwgwt9GzDYpR1VbWUQqLTdDQbMs993DnwQaRhrFuhhQ1fPXRjOxvkkFRtAPLxNQC
            fd/HAgMBAAGjgawwgakwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwFgYD
            VR0lAQH/BAwwCgYIKwYBBQUHAwIwMQYDVR0RBCowKIYmc3BpZmZlOi8vZHdwL3Nl
            cnZpY2UvZHdwLXBlb3BsZS1zZXJ2ZXIwHQYDVR0OBBYEFJtzOwoeNONlGiIfRhzV
            5W1tTRiZMB8GA1UdIwQYMBaAFPRjijlPn3462+DtZ2Zkc/au4KvLMA0GCSqGSIb3
            DQEBCwUAA4IBAQBKzwo962oEmbh2SiydemSDF4VVaOIiY+PLC/iXVYlXXkF9FIJZ
            6XY/qU+doGe3Fu1SCyfY+butpjY2tCvuMTNXanjem4biZc1cRgicZyrrVc3J+FSn
            W+QeRH3qW4xdgCmlmXB4Qa9T883KqlaGBaTqyS3bc5WcVJyaSvrhk7w68fVIxSjR
            kZmAvIjlfdZy6bcwfLP5VISF8UPe1BAindA77EcpnlUpOB6nL6zhAG0oPtEWQaSy
            x9Rm7U/dbtALCwjwVnQad9lxS9qL+DNpoqWYMMesxHFpgbWevqdChHs4W0ojJEFF
            Jh7JdULQeXutUz+DqD1X2QjMq3fPfuqjFZzc
            -----END CERTIFICATE-----
            """;
}
