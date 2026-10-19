package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;

/** Separate workload surface; no browser principal, tenant header or route evidence is accepted. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "dwp.people.hris.per004", name = "enabled", havingValue = "true")
final class Per004WorkloadEndpoints extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = HrisReferenceConfigurationV1.MAX_OWNER_PAYLOAD_UTF8 + 4096;
    private static final String PREFIX = "/internal/hris-performance/v1/configuration-artifacts";
    private final Per004OwnerService service;
    private final ObjectMapper mapper;
    private final SigningKeyResolver keys;
    private final TenantAdmissionVerifier tenants;
    private final ReplayStore replay;

    Per004WorkloadEndpoints(Per004OwnerService service, ObjectMapper mapper,
            ObjectProvider<SigningKeyResolver> keys,
            ObjectProvider<TenantAdmissionVerifier> tenants,
            ObjectProvider<ReplayStore> replay) {
        this.service = service;
        this.mapper = mapper.copy().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        this.keys = keys.getIfAvailable();
        this.tenants = tenants.getIfAvailable();
        this.replay = replay.getIfAvailable();
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PREFIX);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            String path = request.getRequestURI();
            Endpoint endpoint = endpoint(path);
            if (!"POST".equals(request.getMethod()) || request.getQueryString() != null
                    || request.getContentType() == null
                    || !request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")
                    || keys == null || tenants == null || replay == null) throw denied();
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length == 0 || body.length > MAX_BODY_BYTES) throw denied();
            VerifiedAdmission admitted = HrisReferenceConfigurationWorkloadV1.verify(
                    mapper, Clock.systemUTC(), endpoint, path, headers(request), body,
                    keys, tenants, replay);
            OwnerReceipt result = switch (endpoint) {
                case PREPARE -> service.prepare(checked(
                        mapper.readValue(body, PrepareRequest.class), admitted.tenantId()));
                case FINALIZE -> service.finalizeBinding(checked(
                        mapper.readValue(body, FinalizeRequest.class), admitted.tenantId(), path));
                case VALIDATE -> service.validate(checked(
                        mapper.readValue(body, ValidateRequest.class), admitted.tenantId(), path));
                case SIMULATE -> service.simulate(checked(
                        mapper.readValue(body, SimulateRequest.class), admitted.tenantId(), path));
                default -> throw denied();
            };
            mapper.writeValue(response.getOutputStream(), result);
        } catch (RuntimeException | IOException failure) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            mapper.writeValue(response.getOutputStream(), Map.of("code", "PER004_OWNER_BOUNDARY_UNAVAILABLE"));
        }
    }

    private static Endpoint endpoint(String path) {
        for (Endpoint endpoint : List.of(Endpoint.PREPARE, Endpoint.FINALIZE,
                Endpoint.VALIDATE, Endpoint.SIMULATE)) {
            if (endpoint.matchesPath(path)) return endpoint;
        }
        throw denied();
    }
    private static PrepareRequest checked(PrepareRequest request, long tenant) {
        if (request == null || request.tenantId() != tenant) throw denied();
        return request;
    }
    private static FinalizeRequest checked(FinalizeRequest request, long tenant, String path) {
        if (request == null || !matching(request.tuple(), tenant, path)) throw denied();
        return request;
    }
    private static ValidateRequest checked(ValidateRequest request, long tenant, String path) {
        if (request == null || !matching(request.tuple(), tenant, path)) throw denied();
        return request;
    }
    private static SimulateRequest checked(SimulateRequest request, long tenant, String path) {
        if (request == null || !matching(request.tuple(), tenant, path)) throw denied();
        return request;
    }
    private static boolean matching(OwnerTuple tuple, long tenant, String path) {
        if (tuple == null || tuple.tenantId() != tenant || !ownerReference(tuple.payloadReference())) return false;
        String raw = tuple.payloadReference().substring(ARTIFACT_REFERENCE_PREFIX.length());
        UUID id = UUID.fromString(raw);
        return path.contains("/" + id + "/") || path.endsWith("/" + id + ":finalize");
    }
    private static List<Header> headers(HttpServletRequest request) {
        if (request.getHeaderNames() == null) throw denied();
        List<Header> result = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) result.add(new Header(name, value));
        }
        return List.copyOf(result);
    }
    private static IllegalStateException denied() {
        return new IllegalStateException("PER004_WORKLOAD_ADMISSION_DENIED");
    }
}
