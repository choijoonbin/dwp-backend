package com.dwp.services.payroll.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** TLS1.3, exact-five-header and cryptographic proof ingress for Payroll. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(prefix = "dwp.payroll.hrm013-task-dispatch", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class Hrm013PayrollTaskDispatchIngressFilter extends OncePerRequestFilter {
    static final String VERIFIED_ATTRIBUTE = Hrm013PayrollTaskDispatchIngressFilter.class
            .getName() + ".VERIFIED";
    private static final String PATH = Hrm013WorkloadProofV1.TaskOwner.PAYROLL.dispatchPath();
    private static final String PEOPLE_SPIFFE = "spiffe://dwp/service/dwp-people-server";
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
    private final AuthHrm013ServiceAuthorityClient authority;
    private final Hrm013PayrollTaskDispatchStore replays;
    private final ClientCertificateValidator certificates;

    @Autowired
    Hrm013PayrollTaskDispatchIngressFilter(ObjectMapper mapper,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollTaskDispatchStore replays,
            ObjectProvider<ClientCertificateValidator> certificates) {
        this(mapper, Clock.systemUTC(), authority, replays,
                required(certificates.getIfAvailable()));
    }

    Hrm013PayrollTaskDispatchIngressFilter(ObjectMapper mapper, Clock clock,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollTaskDispatchStore replays,
            ClientCertificateValidator certificates) {
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.authority = Objects.requireNonNull(authority);
        this.replays = Objects.requireNonNull(replays);
        this.certificates = Objects.requireNonNull(certificates);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
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
            requirePeopleIdentity(leaf, headers);
            byte[] body = boundedBody(request);
            Hrm013WorkloadProofV1.TransportIdentity transport =
                    new Hrm013WorkloadProofV1.TransportIdentity(
                            "dwp-people-server", certificateThumbprint(leaf));
            Hrm013WorkloadProofV1.Profile profile = Hrm013WorkloadProofV1.taskDispatch(
                    Hrm013WorkloadProofV1.TaskOwner.PAYROLL);
            AtomicReference<AuthHrm013ServiceAuthorityClient.Authority> resolved =
                    new AtomicReference<>();
            Hrm013WorkloadProofV1.VerifiedProof proof = Hrm013WorkloadProofV1.verify(
                    mapper, clock, profile, request.getMethod(), request.getRequestURI(),
                    headers, body, transport,
                    (requestedProfile, selector, keyId) -> {
                        var current = authority.resolve(requestedProfile, selector, keyId,
                                Objects.requireNonNull(correlationId));
                        resolved.set(current);
                        return new Hrm013WorkloadProofV1.VerifiedSigningKey(
                                current.peerSigningKey(), keyId,
                                requestedProfile.callerWorkloadId());
                    }, replays,
                    (requestedProfile, key, claims) -> {
                        var current = resolved.get();
                        return current != null && authority.remainsCurrent(current,
                                requestedProfile,
                                new Hrm013WorkloadProofV1.UntrustedSelector(
                                        claims.tenantId(), claims.authorityMode(),
                                        claims.drainClaims()),
                                key.keyId(), Objects.requireNonNull(correlationId));
                    });
            var current = resolved.get();
            if (current == null) throw Hrm013SafeErrorV1.authorityDenied();
            if (proof.claims().grantUseMode()
                    == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING) {
                if (current.drain() == null || proof.claims().expiresAt().isAfter(
                        current.drain().drainAuthorizationExpiresAt())) {
                    throw Hrm013SafeErrorV1.authorityDenied();
                }
            } else if (current.drain() != null) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            BufferedRequest wrapped = new BufferedRequest(request, body);
            wrapped.setAttribute(VERIFIED_ATTRIBUTE,
                    new VerifiedDispatch(proof, current, body));
            chain.doFilter(wrapped, response);
        } catch (RuntimeException failure) {
            writeSafe(response, failure, correlationId);
        }
    }

    private static void requireRequestLine(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI())
                || request.getQueryString() != null) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static List<Hrm013WorkloadProofV1.Header> exactHeaders(
            HttpServletRequest request) {
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

    private static void requirePeopleIdentity(X509Certificate leaf,
            List<Hrm013WorkloadProofV1.Header> headers) {
        try {
            if (!List.of(PEOPLE_SPIFFE).equals(sans(leaf, 6))) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            long matches = headers.stream()
                    .filter(header -> "X-DWP-Service-Identity"
                            .equalsIgnoreCase(header.name()))
                    .filter(header -> "dwp-people-server".equals(header.value()))
                    .count();
            if (matches != 1) throw Hrm013SafeErrorV1.authorityDenied();
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static boolean forbidden(String name) {
        return FORBIDDEN_HEADERS.contains(name) || name.startsWith("x-forwarded-")
                || name.startsWith("sec-") || name.startsWith("x-tenant-")
                || name.startsWith("x-dwp-")
                && !Set.of("x-dwp-service-identity", "x-dwp-workload-proof")
                        .contains(name);
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

    private static List<String> sans(X509Certificate certificate, int type) throws Exception {
        Collection<List<?>> values = certificate.getSubjectAlternativeNames();
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (List<?> value : values) {
            if (value != null && value.size() >= 2 && Integer.valueOf(type).equals(value.get(0))
                    && value.get(1) instanceof String text) result.add(text);
        }
        return List.copyOf(result);
    }

    private static void writeSafe(HttpServletResponse response, Throwable failure,
            UUID correlationId) throws IOException {
        Throwable safeFailure = failure instanceof Hrm013SafeErrorV1.Failure
                ? failure
                : failure instanceof Hrm013WorkloadProofV1.VerificationException
                        ? Hrm013SafeErrorV1.authorityDenied()
                        : Hrm013SafeErrorV1.failure(
                                Hrm013SafeErrorV1.ErrorCode.INTERNAL_ERROR);
        UUID safeCorrelation = correlationId == null ? UUID.randomUUID() : correlationId;
        Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(
                safeFailure, safeCorrelation);
        response.reset();
        response.setStatus(safe.httpStatus());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (safe.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", safe.retryAfterSeconds().toString());
        }
        if (!safe.emptyBody()) {
            response.setContentType("application/json");
            response.getOutputStream().write(safe.bodyBytes());
        }
    }

    private static ClientCertificateValidator required(ClientCertificateValidator value) {
        if (value == null) throw new IllegalStateException(
                "Enabled HRM-013 Payroll runtime requires a certificate validator.");
        return value;
    }

    @FunctionalInterface
    public interface ClientCertificateValidator {
        X509Certificate validate(X509Certificate[] presentedChain, Clock clock) throws Exception;
    }

    record VerifiedDispatch(Hrm013WorkloadProofV1.VerifiedProof proof,
            AuthHrm013ServiceAuthorityClient.Authority authority, byte[] exactBody) {
        VerifiedDispatch {
            Objects.requireNonNull(proof); Objects.requireNonNull(authority);
            exactBody = Objects.requireNonNull(exactBody).clone();
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
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
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException(
                            "synchronous internal request");
                }
            };
        }
    }
}
