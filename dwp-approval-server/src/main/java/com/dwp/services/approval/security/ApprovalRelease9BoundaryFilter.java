package com.dwp.services.approval.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Canonicality only: trusted identity, exact PEP and all body/CAS/native checks remain mandatory downstream. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public final class ApprovalRelease9BoundaryFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    public ApprovalRelease9BoundaryFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !ApprovalRelease9EndpointPolicy.recognizes(request); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var endpoint = ApprovalRelease9EndpointPolicy.exact(request);
        if (endpoint == null) { deny(response, ErrorCode.FORBIDDEN); return; }
        for (var header : List.of("X-DWP-Service-Token", "X-DWP-User-ID", "X-DWP-Tenant-ID", "X-DWP-Identity-Plane")) {
            if (single(request, header) == null) { deny(response, ErrorCode.UNAUTHORIZED); return; }
        }
        if (!positive(single(request, "X-DWP-User-ID")) || !positive(single(request, "X-DWP-Tenant-ID"))
                || !tokens(request, "X-DWP-Roles", 64, "[A-Z][A-Z0-9_.:-]{0,159}")
                || !tokens(request, "X-DWP-Permissions", 256, "[A-Z][A-Z0-9_.:-]{0,159}")
                || !"TENANT".equals(single(request, "X-DWP-Identity-Plane"))
                || java.util.Arrays.stream(single(request, "X-DWP-Roles").split(",")).anyMatch(role -> role.startsWith("PROVIDER_"))) {
            deny(response, ErrorCode.FORBIDDEN); return;
        }
        for (var header : List.of("X-DWP-Support-Session-ID", "X-DWP-Support-Revision", "X-DWP-Support-Scopes", "X-DWP-Provider-Tenant-ID")) {
            if (request.getHeader(header) != null) { deny(response, ErrorCode.FORBIDDEN); return; }
        }
        for (var header : List.of("X-DWP-Active-Access-Mode", "X-DWP-Route-Contract-Key", "X-DWP-Context-Key", "X-DWP-Context-Scope-Key",
                "X-DWP-Current-Decision-Revision", "X-DWP-Current-Revalidate-At", "X-DWP-Expected-Decision-Revision",
                "X-DWP-Rollout-State", "X-DWP-Rollout-Cohort", "X-DWP-Rollout-Revision", "X-DWP-Step-Up-Challenge",
                "Idempotency-Key", "X-DWP-Expected-Object-Version", "X-DWP-Person-Public-ID", "X-DWP-Display-Name-B64")) {
            if (request.getHeader(header) != null && single(request, header) == null) { deny(response, ErrorCode.FORBIDDEN); return; }
        }
        String mode = single(request, "X-DWP-Active-Access-Mode");
        String rollout = single(request, "X-DWP-Rollout-State");
        String person = single(request, "X-DWP-Person-Public-ID");
        if (mode != null && !List.of("NORMAL", "ELEVATED").contains(mode)
                || rollout != null && !List.of("000", "100", "110", "111").contains(rollout)
                || person != null && !person.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            deny(response, ErrorCode.FORBIDDEN); return;
        }
        if (request.getHeader("X-DWP-Resource-Roles") != null
                && !tokens(request, "X-DWP-Resource-Roles", 128, "[A-Z][A-Z0-9_.:@-]{0,199}")) { deny(response, ErrorCode.FORBIDDEN); return; }
        if (endpoint.sealedRequired() && rollout != null && List.of("000", "100").contains(rollout)) {
            deny(response, endpoint.routeKey().equals("route.approvals.work.information-command-receipt.data")
                    ? ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE : ErrorCode.FORBIDDEN); return;
        }
        if (rollout != null && List.of("110", "111").contains(rollout) && !endpoint.routeKey().equals(single(request, "X-DWP-Route-Contract-Key"))) {
            deny(response, ErrorCode.FORBIDDEN); return;
        }
        if (!endpoint.queryKeys().containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)
                || endpoint.queryKeys().isEmpty() && request.getQueryString() != null
                || request.getRequestURI().matches(".*/information-commands/\\.\\.?/receipt")) { deny(response, ErrorCode.FORBIDDEN); return; }
        chain.doFilter(request, response);
    }
    private String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && !value.isBlank() && value.length() <= 16384 && value.equals(value.trim())
                && value.chars().noneMatch(Character::isISOControl) ? value : null;
    }
    private boolean tokens(HttpServletRequest request, String name, int maximum, String pattern) {
        String value = single(request, name); if (value == null) return false;
        var tokens = List.of(value.split(",", -1));
        return tokens.size() <= maximum && new HashSet<>(tokens).size() == tokens.size() && tokens.stream().allMatch(token -> token.matches(pattern));
    }
    private boolean positive(String value) {
        try { return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0; }
        catch (NumberFormatException malformed) { return false; }
    }
    private void deny(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value()); response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Canonical installed Approval contract authority is required."));
    }
}
