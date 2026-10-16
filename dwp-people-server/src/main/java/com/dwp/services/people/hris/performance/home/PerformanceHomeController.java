package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Exact workload-only endpoint processed before the browser-oriented People
 * security filter. It never accepts browser identity or caller-selected tenant
 * evidence; the signed proof and owner re-evaluation are the only authority.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeController extends OncePerRequestFilter {

    private static final String FAMILY =
            "/internal/hris-performance/v1/home-contributions";
    private static final String PREFIX = FAMILY + "/";

    private final PerformanceHomeAdmission admission;
    private final PerformanceHomeService service;
    private final ObjectMapper mapper;

    PerformanceHomeController(
            PerformanceHomeAdmission admission,
            PerformanceHomeService service,
            ObjectMapper mapper) {
        this.admission = admission;
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.equals(FAMILY)
                || path.startsWith(PREFIX) || path.startsWith(FAMILY + ";"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            String exactPath = request.getRequestURI();
            if (!"GET".equals(request.getMethod())
                    || request.getQueryString() != null
                    || request.getContentLengthLong() > 0
                    || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null
                    || exactPath == null
                    || !exactPath.startsWith(PREFIX)) {
                throw new PerformanceHomeRequestRejected();
            }
            String payloadRef = exactPath.substring(PREFIX.length());
            PerformanceHomeAdmission.Admission admitted = admission.admit(
                    exactPath, headers(request));
            mapper.writeValue(response.getOutputStream(),
                    service.materialize(payloadRef, admitted));
        } catch (HomeMaterializationWorkloadProofV1.AdmissionDenied
                | PerformanceHomeReferenceCodec.ReferenceFailure
                | PerformanceHomeRequestRejected denied) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            mapper.writeValue(response.getOutputStream(),
                    Map.of("code", "PERFORMANCE_HOME_CONTRIBUTION_NOT_FOUND"));
        }
    }

    private static List<HomeMaterializationWorkloadProofV1.Header> headers(
            HttpServletRequest request) {
        if (request.getHeaderNames() == null) throw new PerformanceHomeRequestRejected();
        List<HomeMaterializationWorkloadProofV1.Header> result = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                result.add(new HomeMaterializationWorkloadProofV1.Header(name, value));
            }
        }
        return List.copyOf(result);
    }

    private static final class PerformanceHomeRequestRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
