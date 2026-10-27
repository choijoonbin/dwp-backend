package com.dwp.services.time.hris.separation.hrm013;

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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Exact-path TLS1.3 and five-header admission boundary for Time dispatch. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class Hrm013TimeTaskDispatchIngressFilter extends OncePerRequestFilter {
    public static final String VERIFIED_ATTRIBUTE =
            Hrm013TimeTaskDispatchIngressFilter.class.getName() + ".verified";
    private static final String PATH = Hrm013TaskDispatchV1.TIME.path();
    private static final Set<String> APPLICATION_HEADERS = Set.of(
            "content-type", "accept", "x-dwp-service-identity",
            "x-dwp-workload-proof", "x-correlation-id");
    private static final Set<String> AUXILIARY_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "te", "user-agent");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "cookie", "origin", "referer", "forwarded",
            "x-real-ip", "x-client-cert", "x-forwarded-client-cert",
            "ssl-client-cert", "content-encoding", "transfer-encoding");

    @FunctionalInterface
    public interface ClientCertificateValidator {
        X509Certificate validate(X509Certificate[] chain, Clock clock) throws Exception;
    }

    private final Clock clock;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final ClientCertificateValidator certificates;

    public Hrm013TimeTaskDispatchIngressFilter(
            AuthHrm013ServiceAuthorityClient authority,
            ObjectProvider<ClientCertificateValidator> validators) {
        this(authority, onlyValidator(validators), Clock.systemUTC());
    }

    Hrm013TimeTaskDispatchIngressFilter(
            AuthHrm013ServiceAuthorityClient authority,
            ClientCertificateValidator certificates, Clock clock) {
        this.authority = java.util.Objects.requireNonNull(authority);
        this.certificates = java.util.Objects.requireNonNull(certificates);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        UUID correlation = correlationOrFallback(request);
        try {
            if (!"POST".equals(request.getMethod()) || request.getQueryString() != null) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            List<Hrm013WorkloadProofV1.Header> headers = exactHeaders(request);
            correlation = exactCorrelation(headers);
            X509Certificate leaf = requireTlsLeaf(request);
            String spiffe = exactSpiffe(leaf);
            if (!"spiffe://dwp/service/dwp-people-server".equals(spiffe)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            requireServiceHeader(headers, "dwp-people-server");
            byte[] body = boundedBody(request);
            var verified = authority.verifyInbound(
                    request.getMethod(), request.getRequestURI(), headers, body,
                    new Hrm013WorkloadProofV1.TransportIdentity(
                            "dwp-people-server", thumbprint(leaf)));
            long tenantId = verified.claims().tenantId();
            var decoded = Hrm013TaskDispatchV1.decode(
                    Hrm013TaskDispatchV1.TIME, tenantId, body);
            if (!(decoded instanceof Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            BufferedRequest wrapped = new BufferedRequest(request, body);
            wrapped.setAttribute(VERIFIED_ATTRIBUTE,
                    new Hrm013TimeTaskDispatchService.VerifiedDispatch(
                            dispatch, body, verified));
            chain.doFilter(wrapped, response);
        } catch (RuntimeException failure) {
            writeSafe(response, failure, correlation);
        }
    }

    static List<Hrm013WorkloadProofV1.Header> exactHeaders(HttpServletRequest request) {
        List<Hrm013WorkloadProofV1.Header> result = new ArrayList<>(5);
        Set<String> seen = new HashSet<>();
        int aggregate = 0;
        var names = request.getHeaderNames();
        if (names == null) throw Hrm013SafeErrorV1.authorityDenied();
        for (String raw : Collections.list(names)) {
            String name = raw.toLowerCase(Locale.ROOT);
            boolean application = APPLICATION_HEADERS.contains(name);
            if (FORBIDDEN_HEADERS.contains(name) || name.startsWith("x-forwarded-")
                    || name.startsWith("sec-") || name.startsWith("x-tenant-")
                    || name.startsWith("x-dwp-") && !APPLICATION_HEADERS.contains(name)
                    || !application && !AUXILIARY_HEADERS.contains(name)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            List<String> values = Collections.list(request.getHeaders(raw));
            if (values.size() != 1 || !seen.add(name)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            String value = values.getFirst();
            if (value == null || value.isEmpty() || !value.equals(value.strip())
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            if (application) {
                int size = raw.getBytes(StandardCharsets.US_ASCII).length
                        + value.getBytes(StandardCharsets.UTF_8).length;
                if (size > Hrm013WorkloadProofV1.MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES) {
                    throw Hrm013SafeErrorV1.authorityDenied();
                }
                aggregate = Math.addExact(aggregate, size);
                result.add(new Hrm013WorkloadProofV1.Header(raw, value));
            }
        }
        if (!seen.containsAll(APPLICATION_HEADERS) || result.size() != 5
                || aggregate > Hrm013WorkloadProofV1.MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return List.copyOf(result);
    }

    private X509Certificate requireTlsLeaf(HttpServletRequest request) {
        String protocol = protocol(request);
        if (!request.isSecure() || !"TLSv1.3".equals(protocol)) {
            throw Hrm013SafeErrorV1.authenticationRequired();
        }
        Object raw = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(raw instanceof X509Certificate[] chain) || chain.length == 0) {
            throw Hrm013SafeErrorV1.authenticationRequired();
        }
        try {
            X509Certificate leaf = certificates.validate(chain.clone(), clock);
            leaf.checkValidity(java.util.Date.from(clock.instant()));
            return leaf;
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authenticationRequired();
        }
    }

    static String protocol(HttpServletRequest request) {
        Object tomcat = request.getAttribute(
                "org.apache.tomcat.util.net.secure_protocol_version");
        if (tomcat instanceof String value) return value;
        Object session = request.getAttribute("jakarta.servlet.request.ssl_session");
        if (session instanceof SSLSession ssl) return ssl.getProtocol();
        Object servletProtocol = request.getAttribute("jakarta.servlet.request.ssl_session_protocol");
        return servletProtocol instanceof String value ? value : null;
    }

    private static ClientCertificateValidator onlyValidator(
            ObjectProvider<ClientCertificateValidator> validators) {
        List<ClientCertificateValidator> candidates = validators.orderedStream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "exactly one revocation-aware HRM-013 client certificate policy is required");
        }
        return candidates.getFirst();
    }

    private static String exactSpiffe(X509Certificate leaf) {
        try {
            Collection<List<?>> alternatives = leaf.getSubjectAlternativeNames();
            List<String> uris = new ArrayList<>();
            if (alternatives != null) for (List<?> item : alternatives) {
                if (item != null && item.size() == 2 && Integer.valueOf(6).equals(item.get(0))
                        && item.get(1) instanceof String value) uris.add(value);
            }
            if (uris.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
            return uris.getFirst();
        } catch (java.security.cert.CertificateParsingException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireServiceHeader(
            List<Hrm013WorkloadProofV1.Header> headers, String expected) {
        long matches = headers.stream()
                .filter(value -> "X-DWP-Service-Identity".equalsIgnoreCase(value.name()))
                .filter(value -> expected.equals(value.value())).count();
        if (matches != 1) throw Hrm013SafeErrorV1.authorityDenied();
    }

    private static UUID exactCorrelation(List<Hrm013WorkloadProofV1.Header> headers) {
        try {
            return UUID.fromString(headers.stream()
                    .filter(value -> "X-Correlation-ID".equalsIgnoreCase(value.name()))
                    .map(Hrm013WorkloadProofV1.Header::value).findFirst().orElseThrow());
        } catch (RuntimeException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static UUID correlationOrFallback(HttpServletRequest request) {
        try { return UUID.fromString(request.getHeader("X-Correlation-ID")); }
        catch (RuntimeException failure) { return UUID.randomUUID(); }
    }

    private static byte[] boundedBody(HttpServletRequest request) throws IOException {
        byte[] value = request.getInputStream().readNBytes(
                Hrm013TaskDispatchV1.MAXIMUM_REQUEST_BYTES + 1);
        if (value.length == 0 || value.length > Hrm013TaskDispatchV1.MAXIMUM_REQUEST_BYTES) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return value;
    }

    private static String thumbprint(X509Certificate leaf) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authenticationRequired();
        }
    }

    private static void writeSafe(
            HttpServletResponse response, Throwable failure, UUID correlation)
            throws IOException {
        var safe = Hrm013SafeErrorV1.map(failure, correlation);
        response.resetBuffer(); response.setStatus(safe.httpStatus());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Correlation-ID", correlation.toString());
        if (safe.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", safe.retryAfterSeconds().toString());
        }
        if (!safe.emptyBody()) {
            response.setContentType("application/json");
            response.getOutputStream().write(safe.bodyBytes());
        }
    }

    static final class PkixClientCertificateValidator
            implements ClientCertificateValidator {
        private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
        private final Set<TrustAnchor> anchors;
        private final List<X509CRL> crls;
        private final URI ocsp;

        PkixClientCertificateValidator(Set<TrustAnchor> anchors,
                Collection<X509CRL> crls, URI ocsp) {
            this.anchors = Set.copyOf(anchors);
            this.crls = List.copyOf(crls);
            this.ocsp = ocsp;
            if (this.anchors.isEmpty() || this.crls.isEmpty() == (ocsp == null)) {
                throw new IllegalStateException(
                        "exactly one complete revocation policy is required");
            }
        }

        @Override public X509Certificate validate(X509Certificate[] chain, Clock clock)
                throws Exception {
            if (chain == null || chain.length == 0 || chain.length > 8) {
                throw new java.security.cert.CertificateException("invalid client chain");
            }
            X509Certificate leaf = chain[0];
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
            List<X509Certificate> pathCertificates = new ArrayList<>(List.of(chain));
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

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request); this.body = body.clone();
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
            };
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}

@Configuration(proxyBeanMethods = false)
class Hrm013TimeTaskMtlsConfiguration {
    private static final String PREFIX = "dwp.time.hrm013-task-dispatch.mtls.";

    @Bean
    @ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch",
            name = "enabled", havingValue = "true")
    Hrm013TimeTaskDispatchIngressFilter.ClientCertificateValidator
            hrm013TimeClientCertificateValidator(
                    Environment environment, ResourceLoader resources) {
        if (!"NEED".equals(required(environment, "client-auth"))) {
            throw new IllegalStateException("HRM-013 Time TLS client-auth must be NEED");
        }
        char[] password = required(environment, "trust-store-password").toCharArray();
        try {
            String type = required(environment, "trust-store-type")
                    .toUpperCase(Locale.ROOT);
            if (!Set.of("PKCS12", "JKS").contains(type)) {
                throw new IllegalStateException("unsupported HRM-013 Time trust-store type");
            }
            KeyStore store = KeyStore.getInstance(type);
            var resource = resources.getResource(
                    required(environment, "trust-store-location"));
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalStateException("HRM-013 Time trust-store is unreadable");
            }
            try (InputStream input = resource.getInputStream()) {
                store.load(input, password);
            }
            Set<TrustAnchor> anchors = new HashSet<>();
            var aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                var value = store.getCertificate(aliases.nextElement());
                if (value instanceof X509Certificate certificate
                        && certificate.getBasicConstraints() >= 0) {
                    anchors.add(new TrustAnchor(certificate, null));
                }
            }
            String mode = required(environment, "revocation-mode")
                    .toUpperCase(Locale.ROOT);
            List<X509CRL> crls = new ArrayList<>();
            URI ocsp = null;
            if ("CRL".equals(mode)) {
                var crlResource = resources.getResource(
                        required(environment, "crl-location"));
                if (!crlResource.exists() || !crlResource.isReadable()) {
                    throw new IllegalStateException("HRM-013 Time CRL is unreadable");
                }
                try (InputStream input = crlResource.getInputStream()) {
                    for (var value : CertificateFactory.getInstance("X.509")
                            .generateCRLs(input)) {
                        if (value instanceof X509CRL crl) crls.add(crl);
                    }
                }
                Date now = new Date();
                for (X509CRL crl : crls) {
                    if (crl.getThisUpdate() == null || crl.getThisUpdate().after(now)
                            || crl.getNextUpdate() == null
                            || !now.before(crl.getNextUpdate())) {
                        throw new IllegalStateException("HRM-013 Time CRL validity rejected");
                    }
                }
            } else if ("OCSP".equals(mode)) {
                ocsp = URI.create(required(environment, "ocsp-responder"));
                if (!"https".equalsIgnoreCase(ocsp.getScheme())
                        || ocsp.getHost() == null || ocsp.getUserInfo() != null
                        || ocsp.getFragment() != null) {
                    throw new IllegalStateException(
                            "HRM-013 Time OCSP responder must be HTTPS");
                }
            } else {
                throw new IllegalStateException("HRM-013 Time revocation mode is invalid");
            }
            return new Hrm013TimeTaskDispatchIngressFilter
                    .PkixClientCertificateValidator(anchors, crls, ocsp);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("HRM-013 Time mTLS initialization failed", failure);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getProperty(PREFIX + suffix);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "missing HRM-013 Time mTLS property: " + suffix);
        }
        return value.trim();
    }
}
