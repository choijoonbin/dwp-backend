package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import org.springframework.web.filter.OncePerRequestFilter;

/** No-database TLS/SPIFFE/envelope gate for the identity task dispatch endpoint. */
public final class Hrm013IdentityTaskDispatchIngressFilter
        extends OncePerRequestFilter {
    public static final String ATTRIBUTE =
            Hrm013IdentityTaskDispatchIngressFilter.class.getName() + ".verified";
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
    private static final String EXPECTED_SPIFFE =
            "spiffe://dwp/service/dwp-people-server";

    private final Clock clock;
    private final ClientCertificatePolicy certificates;

    public Hrm013IdentityTaskDispatchIngressFilter(
            Clock clock, ClientCertificatePolicy certificates) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.certificates = Objects.requireNonNull(certificates, "certificates");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Hrm013TaskDispatchV1.IDENTITY.path().equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        UUID correlationId = correlationOrNull(request);
        try {
            requireRequestLine(request);
            List<Hrm013WorkloadProofV1.Header> headers = exactHeaders(request);
            X509Certificate leaf = requireTlsLeaf(request);
            requireSingleSpiffe(leaf, EXPECTED_SPIFFE);
            byte[] body = boundedBody(request,
                    Hrm013TaskDispatchV1.MAXIMUM_REQUEST_BYTES);
            String compactProof = requiredHeader(
                    headers, Hrm013WorkloadProofV1.WORKLOAD_PROOF_HEADER);
            correlationId = UUID.fromString(requiredHeader(
                    headers, Hrm013WorkloadProofV1.CORRELATION_ID_HEADER));
            var identity = new Hrm013WorkloadProofV1.TransportIdentity(
                    Hrm013TaskDispatchV1.IDENTITY.callerWorkloadId(),
                    certificateThumbprint(leaf));
            var context = new VerifiedTransportRequest(
                    body, headers, compactProof, correlationId, identity);
            BufferedRequest wrapped = new BufferedRequest(request, body);
            wrapped.setAttribute(ATTRIBUTE, context);
            chain.doFilter(wrapped, response);
        } catch (MissingCertificate failure) {
            writeProblem(response, Hrm013SafeErrorV1.authenticationRequired(),
                    correlationId);
        } catch (RuntimeException failure) {
            writeProblem(response, Hrm013SafeErrorV1.authorityDenied(), correlationId);
        }
    }

    static void requireRequestLine(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())
                || !Hrm013TaskDispatchV1.IDENTITY.path().equals(request.getRequestURI())
                || request.getQueryString() != null) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static List<Hrm013WorkloadProofV1.Header> exactHeaders(
            HttpServletRequest request) {
        if (request.getHeaderNames() == null) throw Hrm013SafeErrorV1.authorityDenied();
        List<Hrm013WorkloadProofV1.Header> result = new ArrayList<>(5);
        Set<String> seen = new HashSet<>();
        int aggregate = 0;
        for (String rawName : Collections.list(request.getHeaderNames())) {
            String name = rawName.toLowerCase(Locale.ROOT);
            boolean application = APPLICATION_HEADERS.contains(name);
            if (forbidden(name) || !application && !AUXILIARY_HEADERS.contains(name)) {
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
            int bytes = rawName.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length
                    + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > Hrm013WorkloadProofV1.MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            if (application) {
                aggregate = Math.addExact(aggregate, bytes);
                result.add(new Hrm013WorkloadProofV1.Header(rawName, value));
            }
        }
        if (!seen.containsAll(APPLICATION_HEADERS)
                || seen.stream().filter(APPLICATION_HEADERS::contains).count() != 5
                || aggregate > Hrm013WorkloadProofV1.MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return List.copyOf(result);
    }

    static byte[] boundedBody(HttpServletRequest request, int maximum)
            throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
        if (bytes.length == 0 || bytes.length > maximum) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return bytes;
    }

    private X509Certificate requireTlsLeaf(HttpServletRequest request) {
        if (!request.isSecure() || !"TLSv1.3".equals(tlsProtocol(request))) {
            throw new MissingCertificate();
        }
        Object raw = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(raw instanceof X509Certificate[] chain) || chain.length == 0) {
            throw new MissingCertificate();
        }
        try {
            return certificates.validate(chain, clock);
        } catch (Exception invalid) {
            throw new MissingCertificate();
        }
    }

    static void requireSingleSpiffe(X509Certificate leaf, String expected) {
        try {
            Collection<List<?>> alternatives = leaf.getSubjectAlternativeNames();
            List<String> uris = new ArrayList<>();
            if (alternatives != null) {
                for (List<?> alternative : alternatives) {
                    if (alternative != null && alternative.size() == 2
                            && Integer.valueOf(6).equals(alternative.getFirst())
                            && alternative.get(1) instanceof String uri) uris.add(uri);
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
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        } catch (Exception unavailable) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static String tlsProtocol(HttpServletRequest request) {
        Object explicit = request.getAttribute(
                "org.apache.tomcat.util.net.secure_protocol_version");
        if (explicit instanceof String value) return value;
        Object session = request.getAttribute("jakarta.servlet.request.ssl_session");
        return session instanceof SSLSession ssl ? ssl.getProtocol() : null;
    }

    private static String requiredHeader(
            List<Hrm013WorkloadProofV1.Header> headers, String expected) {
        return headers.stream().filter(header -> header.name().equalsIgnoreCase(expected))
                .map(Hrm013WorkloadProofV1.Header::value).findFirst()
                .orElseThrow(Hrm013SafeErrorV1::authorityDenied);
    }

    private static boolean forbidden(String name) {
        return FORBIDDEN_HEADERS.contains(name) || name.startsWith("x-forwarded-")
                || name.startsWith("sec-fetch-") || name.startsWith("sec-ch-ua")
                || name.startsWith("x-dwp-")
                && !Set.of("x-dwp-service-identity", "x-dwp-workload-proof")
                        .contains(name);
    }

    private static UUID correlationOrNull(HttpServletRequest request) {
        try {
            List<String> values = Collections.list(
                    request.getHeaders(Hrm013WorkloadProofV1.CORRELATION_ID_HEADER));
            return values.size() == 1 ? UUID.fromString(values.getFirst()) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static void writeProblem(HttpServletResponse response, Throwable failure,
            UUID correlationId) throws IOException {
        Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(
                failure, correlationId == null ? UUID.randomUUID() : correlationId);
        response.reset();
        response.setStatus(safe.httpStatus());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        if (safe.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", safe.retryAfterSeconds().toString());
        }
        byte[] body = safe.bodyBytes();
        if (body.length > 0) {
            response.setContentType("application/problem+json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
    }

    public record VerifiedTransportRequest(
            byte[] exactBody, List<Hrm013WorkloadProofV1.Header> headers,
            String compactProof, UUID correlationId,
            Hrm013WorkloadProofV1.TransportIdentity transportIdentity) {
        public VerifiedTransportRequest {
            exactBody = Objects.requireNonNull(exactBody).clone();
            headers = List.copyOf(headers);
            Objects.requireNonNull(compactProof); Objects.requireNonNull(correlationId);
            Objects.requireNonNull(transportIdentity);
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    @FunctionalInterface
    public interface ClientCertificatePolicy {
        X509Certificate validate(X509Certificate[] presented, Clock clock)
                throws Exception;
    }

    static final class PkixClientCertificatePolicy implements ClientCertificatePolicy {
        private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
        private final Set<TrustAnchor> anchors;
        private final List<X509CRL> crls;
        private final URI ocsp;

        PkixClientCertificatePolicy(Set<TrustAnchor> anchors,
                Collection<X509CRL> crls, URI ocsp) {
            this.anchors = Set.copyOf(anchors);
            this.crls = List.copyOf(crls);
            this.ocsp = ocsp;
            if (this.anchors.isEmpty() || this.crls.isEmpty() == (ocsp == null)) {
                throw new IllegalStateException(
                        "exactly one complete revocation policy is required");
            }
        }

        @Override public X509Certificate validate(
                X509Certificate[] presented, Clock clock) throws Exception {
            if (presented == null || presented.length == 0 || presented.length > 8) {
                throw new java.security.cert.CertificateException("invalid client chain");
            }
            X509Certificate leaf = presented[0];
            leaf.checkValidity(Date.from(clock.instant()));
            boolean[] usage = leaf.getKeyUsage();
            List<String> extended = leaf.getExtendedKeyUsage();
            if (!(leaf.getPublicKey() instanceof java.security.interfaces.RSAPublicKey rsa)
                    || rsa.getModulus().bitLength()
                    < Hrm013WorkloadProofV1.MINIMUM_RSA_BITS
                    || leaf.getBasicConstraints() >= 0 || usage == null
                    || usage.length == 0 || !usage[0] || extended == null
                    || !extended.contains(CLIENT_AUTH_EKU)) {
                throw new java.security.cert.CertificateException(
                        "client certificate profile rejected");
            }
            List<X509Certificate> pathCertificates = new ArrayList<>(List.of(presented));
            if (anchors.stream().map(TrustAnchor::getTrustedCert)
                    .anyMatch(pathCertificates.getLast()::equals)) {
                pathCertificates.removeLast();
            }
            if (pathCertificates.isEmpty()) {
                throw new java.security.cert.CertificateException(
                        "leaf cannot be trust anchor");
            }
            CertPath path = CertificateFactory.getInstance("X.509")
                    .generateCertPath(pathCertificates);
            PKIXParameters parameters = new PKIXParameters(anchors);
            parameters.setDate(Date.from(clock.instant()));
            parameters.setRevocationEnabled(true);
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXRevocationChecker checker =
                    (PKIXRevocationChecker) validator.getRevocationChecker();
            if (!crls.isEmpty()) {
                parameters.addCertStore(CertStore.getInstance("Collection",
                        new CollectionCertStoreParameters(crls)));
                checker.setOptions(Set.of(PKIXRevocationChecker.Option.PREFER_CRLS,
                        PKIXRevocationChecker.Option.NO_FALLBACK));
            } else {
                checker.setOcspResponder(ocsp);
                checker.setOptions(Set.of(PKIXRevocationChecker.Option.NO_FALLBACK));
            }
            parameters.addCertPathChecker(checker);
            validator.validate(path, parameters);
            return leaf;
        }
    }

    static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request); this.body = body.clone();
        }
        @Override public ServletInputStream getInputStream() {
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

    static final class MissingCertificate extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
