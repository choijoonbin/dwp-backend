package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ResolverCaller;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import org.springframework.web.filter.OncePerRequestFilter;

/** Exact TLS/JWS ingress gate. It performs no authority or business read. */
public final class HrisServiceGrantAuthorityIngressFilter extends OncePerRequestFilter {
    private static final Set<String> APPLICATION_HEADERS = Set.of(
            "content-type", "accept", "x-dwp-service-identity",
            "x-dwp-workload-proof", "x-correlation-id");
    private static final Set<String> AUXILIARY_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "te",
            "user-agent");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "cookie", "origin", "referer", "forwarded",
            "x-real-ip", "x-client-cert", "x-forwarded-client-cert",
            "ssl-client-cert", "x-tenant-id", "x-user-id", "x-roles",
            "x-permissions", "content-encoding", "transfer-encoding");

    private final ObjectMapper mapper;
    private final Clock clock;
    private final HrisServiceGrantAuthorityProofVerifier proofVerifier;
    private final ClientCertificateValidator certificateValidator;

    public HrisServiceGrantAuthorityIngressFilter(
            ObjectMapper mapper, Clock clock,
            HrisServiceGrantAuthorityProofVerifier proofVerifier,
            ClientCertificateValidator certificateValidator) {
        this.mapper = mapper;
        this.clock = clock;
        this.proofVerifier = proofVerifier;
        this.certificateValidator = certificateValidator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HrisServiceGrantAuthorityV1.PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        UUID correlation = correlationOrNull(request);
        try {
            requireExactRequestLine(request, HrisServiceGrantAuthorityV1.PATH);
            List<Header> headers = exactApplicationHeaders(request);
            X509Certificate leaf = requireTlsLeaf(request, clock, certificateValidator);
            byte[] body = boundedBody(request, HrisServiceGrantAuthorityV1.MAX_REQUEST_BYTES);
            HrisServiceGrantAuthorityV1.ResolveRequest decoded =
                    HrisServiceGrantAuthorityV1.decodeRequest(mapper, body);
            ResolverCaller caller = resolverCaller(decoded.requestingWorkloadId());
            String expectedSpiffe = "spiffe://dwp/service/" + caller.workloadId();
            requireSingleSpiffeUri(leaf, expectedSpiffe);
            TransportIdentity identity = new TransportIdentity(
                    caller.workloadId(), certificateThumbprint(leaf));
            HrisServiceGrantAuthorityRequestContext context = proofVerifier.verify(
                    decoded, body, caller, headers, identity, leaf.getPublicKey());
            BufferedRequest wrapped = new BufferedRequest(request, body);
            wrapped.setAttribute(HrisServiceGrantAuthorityRequestContext.ATTRIBUTE, context);
            chain.doFilter(wrapped, response);
        } catch (MissingCertificate failure) {
            HrisServiceGrantAuthorityProblem.write(
                    response, Hrm013SafeErrorV1.authenticationRequired(), correlation);
        } catch (RuntimeException failure) {
            HrisServiceGrantAuthorityProblem.write(
                    response, Hrm013SafeErrorV1.authorityDenied(), correlation);
        }
    }

    static void requireExactRequestLine(HttpServletRequest request, String path) {
        if (!"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI())
                || request.getQueryString() != null) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static List<Header> exactApplicationHeaders(HttpServletRequest request) {
        List<Header> result = new ArrayList<>(5);
        Set<String> seen = new HashSet<>();
        int aggregate = 0;
        for (String rawName : Collections.list(request.getHeaderNames())) {
            String name = rawName.toLowerCase(Locale.ROOT);
            boolean applicationHeader = APPLICATION_HEADERS.contains(name);
            if (forbidden(name) || !applicationHeader && !AUXILIARY_HEADERS.contains(name)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            List<String> values = Collections.list(request.getHeaders(rawName));
            if (values.size() != 1 || !seen.add(name)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            String value = values.getFirst();
            if (value == null || value.isEmpty() || !value.equals(value.strip())
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            int fieldBytes = rawName.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length
                    + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (fieldBytes > Hrm013WorkloadProofV1.MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            if (applicationHeader) {
                aggregate = Math.addExact(aggregate, fieldBytes);
                result.add(new Header(rawName, value));
            }
        }
        if (!seen.containsAll(APPLICATION_HEADERS)
                || aggregate > Hrm013WorkloadProofV1.MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return List.copyOf(result);
    }

    private static boolean forbidden(String name) {
        return FORBIDDEN_HEADERS.contains(name) || name.startsWith("x-forwarded-")
                || name.startsWith("sec-fetch-") || name.startsWith("sec-ch-ua")
                || name.startsWith("x-dwp-")
                && !Set.of("x-dwp-service-identity", "x-dwp-workload-proof").contains(name);
    }

    static byte[] boundedBody(HttpServletRequest request, int maximum) throws IOException {
        ServletInputStream input = request.getInputStream();
        byte[] body = input.readNBytes(maximum + 1);
        if (body.length == 0 || body.length > maximum) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return body;
    }

    static X509Certificate requireTlsLeaf(
            HttpServletRequest request, Clock clock,
            ClientCertificateValidator certificateValidator) {
        if (!request.isSecure() || !"TLSv1.3".equals(tlsProtocol(request))) {
            throw new MissingCertificate();
        }
        Object raw = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(raw instanceof X509Certificate[] chain) || chain.length == 0
                || chain[0] == null) {
            throw new MissingCertificate();
        }
        try {
            return certificateValidator.validate(chain, clock);
        } catch (Exception invalid) {
            throw new MissingCertificate();
        }
    }

    private static String tlsProtocol(HttpServletRequest request) {
        Object explicit = request.getAttribute(
                "org.apache.tomcat.util.net.secure_protocol_version");
        if (explicit instanceof String value) {
            return value;
        }
        Object session = request.getAttribute("jakarta.servlet.request.ssl_session");
        return session instanceof SSLSession ssl ? ssl.getProtocol() : null;
    }

    static void requireSingleSpiffeUri(X509Certificate leaf, String expected) {
        try {
            Collection<List<?>> alternatives = leaf.getSubjectAlternativeNames();
            List<String> uris = new ArrayList<>();
            if (alternatives != null) {
                for (List<?> alternative : alternatives) {
                    if (alternative != null && alternative.size() == 2
                            && Integer.valueOf(6).equals(alternative.getFirst())
                            && alternative.get(1) instanceof String uri) {
                        uris.add(uri);
                    }
                }
            }
            if (uris.size() != 1 || !expected.equals(uris.getFirst())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        } catch (java.security.cert.CertificateParsingException invalid) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static String certificateThumbprint(X509Certificate leaf) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception unavailable) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static ResolverCaller resolverCaller(String workloadId) {
        return EnumSet.allOf(ResolverCaller.class).stream()
                .filter(value -> value.workloadId().equals(workloadId))
                .findFirst().orElseThrow(Hrm013SafeErrorV1::authorityDenied);
    }

    static UUID correlationOrNull(HttpServletRequest request) {
        try {
            List<String> values = Collections.list(request.getHeaders("X-Correlation-ID"));
            return values.size() == 1 ? UUID.fromString(values.getFirst()) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous internal request");
                }
            };
        }
    }

    /** Immutable PKIX client-certificate policy created only when an endpoint is enabled. */
    public static final class ClientCertificateValidator {
        public enum RevocationMode { CRL, OCSP }

        private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
        private final Set<TrustAnchor> trustAnchors;
        private final RevocationMode revocationMode;
        private final List<X509CRL> crls;
        private final URI ocspResponder;

        public ClientCertificateValidator(
                Set<TrustAnchor> trustAnchors, RevocationMode revocationMode,
                Collection<X509CRL> crls, URI ocspResponder) {
            this.trustAnchors = Set.copyOf(trustAnchors);
            this.revocationMode = java.util.Objects.requireNonNull(revocationMode);
            this.crls = List.copyOf(crls);
            this.ocspResponder = ocspResponder;
            if (this.trustAnchors.isEmpty()
                    || revocationMode == RevocationMode.CRL && this.crls.isEmpty()
                    || revocationMode == RevocationMode.OCSP && ocspResponder == null) {
                throw new IllegalStateException("complete client-certificate trust is required");
            }
        }

        X509Certificate validate(X509Certificate[] presented, Clock clock) throws Exception {
            if (presented == null || presented.length == 0 || presented.length > 8) {
                throw new java.security.cert.CertificateException("invalid client chain");
            }
            X509Certificate leaf = presented[0];
            leaf.checkValidity(Date.from(clock.instant()));
            boolean[] keyUsage = leaf.getKeyUsage();
            List<String> extended = leaf.getExtendedKeyUsage();
            if (!(leaf.getPublicKey() instanceof java.security.interfaces.RSAPublicKey rsa)
                    || rsa.getModulus().bitLength()
                    < Hrm013WorkloadProofV1.MINIMUM_RSA_BITS
                    || leaf.getBasicConstraints() >= 0 || keyUsage == null
                    || keyUsage.length == 0 || !keyUsage[0] || extended == null
                    || !extended.contains(CLIENT_AUTH_EKU)) {
                throw new java.security.cert.CertificateException(
                        "client certificate profile rejected");
            }
            List<X509Certificate> pathCertificates = new ArrayList<>(List.of(presented));
            if (isTrustAnchor(pathCertificates.getLast())) {
                pathCertificates.removeLast();
            }
            if (pathCertificates.isEmpty()) {
                throw new java.security.cert.CertificateException("leaf cannot be trust anchor");
            }
            CertPath path = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertPath(pathCertificates);
            PKIXParameters parameters = new PKIXParameters(trustAnchors);
            parameters.setDate(Date.from(clock.instant()));
            parameters.setRevocationEnabled(true);
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXRevocationChecker checker =
                    (PKIXRevocationChecker) validator.getRevocationChecker();
            if (revocationMode == RevocationMode.CRL) {
                parameters.addCertStore(CertStore.getInstance("Collection",
                        new CollectionCertStoreParameters(crls)));
                checker.setOptions(Set.of(
                        PKIXRevocationChecker.Option.PREFER_CRLS,
                        PKIXRevocationChecker.Option.NO_FALLBACK));
            } else {
                checker.setOcspResponder(ocspResponder);
                checker.setOptions(Set.of(PKIXRevocationChecker.Option.NO_FALLBACK));
            }
            parameters.addCertPathChecker(checker);
            validator.validate(path, parameters);
            return leaf;
        }

        private boolean isTrustAnchor(X509Certificate certificate) {
            return trustAnchors.stream().map(TrustAnchor::getTrustedCert)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(anchor -> anchor.equals(certificate));
        }
    }

    static final class MissingCertificate extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
