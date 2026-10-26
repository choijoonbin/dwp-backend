package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import org.springframework.web.filter.OncePerRequestFilter;

/** Strict TLS1.3/five-header ingress. No owner aggregate is read in this filter. */
public final class Hrm013CallbackIngressFilter extends OncePerRequestFilter {
    private static final String ROOT = "/internal/hris-people/v1/separation-cases/";
    private static final Set<String> APPLICATION_HEADERS = Set.of(
            "content-type", "accept", "x-dwp-service-identity",
            "x-dwp-workload-proof", "x-correlation-id");
    private static final Set<String> AUXILIARY_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "te", "user-agent");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "cookie", "origin", "referer", "forwarded",
            "x-real-ip", "x-client-cert", "x-forwarded-client-cert",
            "ssl-client-cert", "content-encoding", "transfer-encoding");

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Hrm013WorkloadProofVerifier verifier;
    private final Hrm013AdmissionControl admission;
    private final ClientCertificateValidator certificates;

    public Hrm013CallbackIngressFilter(
            ObjectMapper mapper, Clock clock, Hrm013WorkloadProofVerifier verifier,
            Hrm013AdmissionControl admission, ClientCertificateValidator certificates) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.verifier = java.util.Objects.requireNonNull(verifier, "verifier");
        this.admission = java.util.Objects.requireNonNull(admission, "admission");
        this.certificates = java.util.Objects.requireNonNull(certificates, "certificates");
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null || !request.getRequestURI().startsWith(ROOT);
    }

    @Override protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        UUID correlation = correlationOrNull(request);
        Hrm013CallbackRequestContext.clear(request);
        try {
            requireRequestLine(request);
            List<Hrm013WorkloadProofV1.Header> headers = exactHeaders(request);
            X509Certificate leaf = requireTlsLeaf(request);
            String caller = exactSpiffeCaller(leaf);
            requireServiceHeader(headers, caller);
            byte[] body = boundedBody(request);
            Selector selector = selector(request.getRequestURI(), caller, headers);
            Hrm013CallbackRequestContext.Route route = admission.route(
                    request.getMethod(), request.getRequestURI(), caller,
                    selector.purpose(), selector.taskType(), correlation,
                    selector.approvedWaiver());
            var verified = verifier.verify(
                    route.proofProfile(), request.getMethod(), request.getRequestURI(),
                    headers, body, new Hrm013WorkloadProofV1.TransportIdentity(
                            caller, certificateThumbprint(leaf)));
            var decoded = admission.decodeAndBind(route, verified);
            BufferedRequest wrapped = new BufferedRequest(request, body);
            Hrm013CallbackRequestContext.set(
                    wrapped, new Hrm013CallbackRequestContext(route, verified, decoded));
            try {
                chain.doFilter(wrapped, response);
            } finally {
                Hrm013CallbackRequestContext.clear(wrapped);
            }
        } catch (RuntimeException failure) {
            Hrm013RuntimeProblem.write(response,
                    failure instanceof Hrm013SafeErrorV1.Failure
                            ? failure : Hrm013SafeErrorV1.authorityDenied(),
                    correlation);
        }
    }

    public static boolean isCallbackPath(String path) {
        return path != null && path.startsWith(ROOT)
                && (path.endsWith("/receipts")
                || path.endsWith("/reconciliation-results")
                || path.endsWith("/waiver-decisions"));
    }

    private static void requireRequestLine(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !isCallbackPath(request.getRequestURI())
                || request.getQueryString() != null) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static List<Hrm013WorkloadProofV1.Header> exactHeaders(HttpServletRequest request) {
        List<Hrm013WorkloadProofV1.Header> result = new ArrayList<>(5);
        Set<String> seen = new HashSet<>();
        int aggregate = 0;
        var names = request.getHeaderNames();
        if (names == null) throw Hrm013SafeErrorV1.authorityDenied();
        for (String rawName : Collections.list(names)) {
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
            int size = rawName.getBytes(StandardCharsets.US_ASCII).length
                    + value.getBytes(StandardCharsets.UTF_8).length;
            if (size > Hrm013WorkloadProofV1.MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            if (application) {
                aggregate = Math.addExact(aggregate, size);
                result.add(new Hrm013WorkloadProofV1.Header(rawName, value));
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
                || name.startsWith("sec-") || name.startsWith("x-tenant-")
                || name.startsWith("x-dwp-")
                && !Set.of("x-dwp-service-identity", "x-dwp-workload-proof").contains(name);
    }

    private X509Certificate requireTlsLeaf(HttpServletRequest request) {
        if (!request.isSecure() || !"TLSv1.3".equals(tlsProtocol(request))) {
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

    private static String exactSpiffeCaller(X509Certificate leaf) {
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
            if (uris.size() != 1 || !uris.getFirst().startsWith("spiffe://dwp/service/")) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            String caller = uris.getFirst().substring("spiffe://dwp/service/".length());
            if (!Set.of("dwp-time-server", "dwp-payroll-server",
                    "dwp-auth-server", "dwp-approval-server").contains(caller)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            return caller;
        } catch (java.security.cert.CertificateParsingException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private Selector selector(
            String path, String caller, List<Hrm013WorkloadProofV1.Header> headers) {
        if (path.endsWith("/waiver-decisions")) {
            if (!"dwp-approval-server".equals(caller)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            return new Selector("HRIS_SEPARATION_TASK_WAIVER_DECISION", null,
                    claimsApproved(headers));
        }
        Hrm013WorkloadProofV1.TaskOwner owner = java.util.Arrays.stream(
                Hrm013WorkloadProofV1.TaskOwner.values())
                .filter(value -> value.workloadId().equals(caller))
                .findFirst().orElseThrow(Hrm013SafeErrorV1::authorityDenied);
        if (path.endsWith("/receipts")) {
            return new Selector(owner.receiptPurpose(), owner.taskType(), false);
        }
        if (owner == Hrm013WorkloadProofV1.TaskOwner.IDENTITY) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return new Selector(owner == Hrm013WorkloadProofV1.TaskOwner.TIME
                ? "HRIS_SEPARATION_TIME_RECONCILIATION"
                : "HRIS_SEPARATION_PAYROLL_RECONCILIATION", null, false);
    }

    private boolean claimsApproved(List<Hrm013WorkloadProofV1.Header> headers) {
        String compact = headers.stream()
                .filter(header -> "X-DWP-Workload-Proof".equalsIgnoreCase(header.name()))
                .map(Hrm013WorkloadProofV1.Header::value).findFirst()
                .orElseThrow(Hrm013SafeErrorV1::authorityDenied);
        if (compact.length() > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3) throw Hrm013SafeErrorV1.authorityDenied();
        try {
            byte[] claims = Base64.getUrlDecoder().decode(parts[1]);
            if (claims.length == 0 || claims.length > 14_336) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            JsonNode node = mapper.readTree(claims);
            return node != null && node.isObject() && node.has("stepUpEvidenceId");
        } catch (IOException | IllegalArgumentException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireServiceHeader(
            List<Hrm013WorkloadProofV1.Header> headers, String caller) {
        long matches = headers.stream()
                .filter(value -> "X-DWP-Service-Identity".equalsIgnoreCase(value.name()))
                .filter(value -> caller.equals(value.value())).count();
        if (matches != 1) throw Hrm013SafeErrorV1.authorityDenied();
    }

    private static byte[] boundedBody(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(
                Hrm013WorkloadProofV1.MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES + 1);
        if (bytes.length == 0 || bytes.length > 32_768) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return bytes;
    }

    private static UUID correlationOrNull(HttpServletRequest request) {
        try {
            List<String> values = Collections.list(request.getHeaders("X-Correlation-ID"));
            if (values.size() != 1) return null;
            UUID value = UUID.fromString(values.getFirst());
            return value.toString().equals(values.getFirst()) ? value : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String tlsProtocol(HttpServletRequest request) {
        Object explicit = request.getAttribute(
                "org.apache.tomcat.util.net.secure_protocol_version");
        if (explicit instanceof String value) return value;
        Object session = request.getAttribute("jakarta.servlet.request.ssl_session");
        return session instanceof SSLSession value ? value.getProtocol() : null;
    }

    private static String certificateThumbprint(X509Certificate leaf) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    @FunctionalInterface
    public interface ClientCertificateValidator {
        X509Certificate validate(X509Certificate[] presentedChain, Clock clock) throws Exception;
    }

    private record Selector(String purpose, String taskType, boolean approvedWaiver) { }

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
}
