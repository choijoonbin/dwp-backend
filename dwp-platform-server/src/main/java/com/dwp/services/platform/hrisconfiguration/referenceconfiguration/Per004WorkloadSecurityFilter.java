package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;

/** Only a verified server attribute admits the exact two Platform internal endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class Per004WorkloadSecurityFilter extends OncePerRequestFilter {
    private static final String ADMISSION = Per004WorkloadSecurityFilter.class.getName() + ".admission";
    private final ObjectMapper mapper;
    private final SigningKeyResolver keys;
    private final TenantAdmissionVerifier tenants;
    private final ReplayStore replays;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public Per004WorkloadSecurityFilter(ObjectMapper mapper, ObjectProvider<SigningKeyResolver> keys,
            ObjectProvider<TenantAdmissionVerifier> tenants, Per004WorkloadReplayStore replays) {
        this(mapper, keys.getIfAvailable(), tenants.getIfAvailable(), replays, Clock.systemUTC());
    }
    Per004WorkloadSecurityFilter(ObjectMapper mapper, SigningKeyResolver keys,
            TenantAdmissionVerifier tenants, ReplayStore replays, Clock clock) {
        this.mapper = mapper; this.keys = keys; this.tenants = tenants; this.replays = replays; this.clock = clock;
    }

    public static boolean admitted(HttpServletRequest request) {
        return request.getAttribute(ADMISSION) instanceof VerifiedAdmission admission
                && "POST".equals(request.getMethod()) && request.getQueryString() == null
                && endpoint(request.getRequestURI()) != null
                && admission.claims().path().equals(request.getRequestURI())
                && endpoint(request.getRequestURI()).bindingId().equals(admission.claims().bindingId());
    }
    static VerifiedAdmission admission(HttpServletRequest request) {
        if (!admitted(request)) throw new IllegalStateException("PER004_WORKLOAD_ADMISSION_REQUIRED");
        return (VerifiedAdmission) request.getAttribute(ADMISSION);
    }
    static Endpoint endpoint(String path) {
        if (Endpoint.PROJECTION.matchesPath(path)) return Endpoint.PROJECTION;
        if (Endpoint.CREATE_LOOKUP.matchesPath(path)) return Endpoint.CREATE_LOOKUP;
        return null;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return endpoint(request.getRequestURI()) == null; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        if (keys == null || tenants == null || !"POST".equals(request.getMethod()) || request.getQueryString() != null) {
            response.sendError(403, "PER004_WORKLOAD_ADMISSION_REQUIRED"); return;
        }
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length > 65536) { response.sendError(413, "PER004_INTERNAL_REQUEST_TOO_LARGE"); return; }
        List<Header> headers = new ArrayList<>();
        try {
            for (String name : Collections.list(request.getHeaderNames())) {
                for (String value : Collections.list(request.getHeaders(name))) headers.add(new Header(name, value));
            }
            Class<?> bodyType = endpoint(request.getRequestURI()) == Endpoint.PROJECTION
                    ? com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.OwnerProjectionRequest.class
                    : com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.CreateLookupRequest.class;
            mapper.readerFor(bodyType).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(bytes);
            VerifiedAdmission admitted = HrisReferenceConfigurationWorkloadV1.verify(mapper, clock,
                    endpoint(request.getRequestURI()), request.getRequestURI(), headers, bytes, keys, tenants, replays);
            request.setAttribute(ADMISSION, admitted);
        } catch (IOException | RuntimeException exception) {
            response.sendError(403, "PER004_WORKLOAD_ADMISSION_REQUIRED"); return;
        }
        chain.doFilter(new BodyRequest(request, bytes), response);
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        private BodyRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
