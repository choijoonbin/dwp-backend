package com.dwp.services.approval.systemslaauthority;

import com.dwp.core.common.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Isolated internal purpose dispatcher. It terminates here and never enters Gateway/end-user authorization. */
public final class SystemSlaNotificationFilter extends OncePerRequestFilter {
    private final SystemSlaCurrentSource source;
    private final SystemSlaNotificationController controller;
    private final ObjectMapper mapper;
    public SystemSlaNotificationFilter(SystemSlaCurrentSource source, SystemSlaNotificationController controller, ObjectMapper mapper) {
        this.source = source; this.controller = controller; this.mapper = mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null || !request.getRequestURI().contains("quorum-sla/recipient-authority");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain ignored) throws IOException {
        try {
            if (!"POST".equals(request.getMethod()) || !SystemSlaNotificationProtocol.PATH.equals(request.getRequestURI()) || request.getQueryString() != null
                    || request.getHeader("Authorization") != null || request.getHeader("Cookie") != null || !utf8Json(request)
                    || !"dwp-notification-server".equals(single(request, "X-DWP-Service-Identity")) || borrowed(request)
                    || request.getContentLengthLong() > 524288) throw SystemSlaJson.denied();
            var proof = source.preverify(request.getInputStream().readNBytes(524289), single(request, SystemSlaNotificationProtocol.HEADER));
            response.setStatus(200); response.setContentType(MediaType.APPLICATION_JSON_VALUE); mapper.writeValue(response.getOutputStream(), controller.evaluate(proof));
        } catch (BaseException failure) { error(response, failure.getErrorCode()); }
        catch (RuntimeException failure) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); }
    }
    private static boolean borrowed(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT)).anyMatch(name -> name.startsWith("x-dwp-")
                && !Set.of("x-dwp-service-identity", SystemSlaNotificationProtocol.HEADER.toLowerCase(Locale.ROOT)).contains(name));
    }
    private static String single(HttpServletRequest request, String name) { var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null; }
    private static boolean utf8Json(HttpServletRequest request) {
        try {
            var types = Collections.list(request.getHeaders("Content-Type")); if (types.size() != 1) return false;
            var type = MediaType.parseMediaType(types.getFirst()); return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)
                    && (type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset()));
        } catch (IllegalArgumentException failure) { return false; }
    }
    private void error(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Exact current SYSTEM_SLA recipient authority is required."));
    }
}
