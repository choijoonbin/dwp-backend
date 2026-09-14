package com.dwp.services.approval.document;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** A canonicality guard only; the existing owner filter still verifies the token and resolves exact PEP. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class ApprovalDocumentTrustedHeaderFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    public ApprovalDocumentTrustedHeaderFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !ApprovalDocumentEndpointPolicy.matches(request); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var endpoint = ApprovalDocumentEndpointPolicy.exact(request);
        if (endpoint == null) { deny(response, ErrorCode.FORBIDDEN); return; }
        for (var header : List.of("X-DWP-Service-Token", "X-DWP-User-ID", "X-DWP-Tenant-ID", "X-DWP-Identity-Plane")) {
            if (single(request, header) == null) { deny(response, ErrorCode.UNAUTHORIZED); return; }
        }
        if (!positive(single(request, "X-DWP-User-ID")) || !positive(single(request, "X-DWP-Tenant-ID"))
                || !tokens(request, "X-DWP-Roles", 64, "[A-Z][A-Z0-9_.:-]{0,159}")
                || !tokens(request, "X-DWP-Permissions", 256, "[A-Z][A-Z0-9_.:-]{0,159}")) { deny(response, ErrorCode.FORBIDDEN); return; }
        if (!"TENANT".equals(single(request, "X-DWP-Identity-Plane"))
                || java.util.Arrays.stream(single(request, "X-DWP-Roles").split(",")).anyMatch(r -> r.startsWith("PROVIDER_"))) {
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
        if (mode != null && !List.of("NORMAL", "ELEVATED").contains(mode)) { deny(response, ErrorCode.FORBIDDEN); return; }
        String person = single(request, "X-DWP-Person-Public-ID");
        String display = single(request, "X-DWP-Display-Name-B64");
        String rollout = single(request, "X-DWP-Rollout-State");
        if ((person != null && !canonicalUuid(person)) || (display != null && !canonicalDisplay(display))
                || (rollout != null && !List.of("000", "100", "110", "111").contains(rollout))) {
            deny(response, ErrorCode.FORBIDDEN); return;
        }
        if (request.getHeader("X-DWP-Resource-Roles") != null
                && !tokens(request, "X-DWP-Resource-Roles", 128, "[A-Z][A-Z0-9_.:@-]{0,199}")) { deny(response, ErrorCode.FORBIDDEN); return; }
        if (endpoint.publish() && !List.of("110", "111").contains(single(request, "X-DWP-Rollout-State"))) { deny(response, ErrorCode.FORBIDDEN); return; }
        if ("110".equals(single(request, "X-DWP-Rollout-State")) || "111".equals(single(request, "X-DWP-Rollout-State"))) {
            if (!endpoint.routeKey().equals(single(request, "X-DWP-Route-Contract-Key"))) { deny(response, ErrorCode.FORBIDDEN); return; }
        }
        chain.doFilter(request, response);
    }
    private String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && !value.isBlank() && value.length() <= 16384 && value.equals(value.trim())
                && value.chars().noneMatch(Character::isISOControl) ? value : null;
    }
    private boolean tokens(HttpServletRequest request, String header, int maximum, String pattern) {
        String value = single(request, header); if (value == null) return false;
        var values = List.of(value.split(",", -1));
        return values.size() <= maximum && new HashSet<>(values).size() == values.size() && values.stream().allMatch(v -> v.matches(pattern));
    }
    private boolean positive(String value) { try { return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0; } catch (NumberFormatException e) { return false; } }
    private boolean canonicalUuid(String value) {
        try { return java.util.UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException e) { return false; }
    }
    private boolean canonicalDisplay(String value) {
        try {
            byte[] bytes = java.util.Base64.getUrlDecoder().decode(value);
            if (!java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) return false;
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return !text.isBlank() && text.length() <= 2048 && text.chars().noneMatch(Character::isISOControl);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) { return false; }
    }
    private void deny(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value()); response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Canonical trusted document authority is required."));
    }
}
