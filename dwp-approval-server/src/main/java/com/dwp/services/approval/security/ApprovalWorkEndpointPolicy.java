package com.dwp.services.approval.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

final class ApprovalWorkEndpointPolicy {
    private ApprovalWorkEndpointPolicy() { }

    private static final List<PathPattern> READS = patterns("/v1/tasks/search", "/v1/requests/search",
            "/v1/requests/{requestId}/draft/revisions", "/v1/requests/{requestId}/draft/revisions/{revision}",
            "/v1/draft-commands/{idempotencyKey}");
    private static final List<PathPattern> POSTS = patterns("/v1/requests", "/v1/requests/{requestId}/draft/recover",
            "/v1/requests/{requestId}/draft/delete", "/v1/requests/{requestId}/draft/restore");
    private static final List<PathPattern> PUTS = patterns("/v1/requests/{requestId}/draft");

    static boolean matches(HttpServletRequest request) {
        var patterns = switch (request.getMethod()) {
            case "GET", "HEAD" -> READS;
            case "POST" -> POSTS;
            case "PUT" -> PUTS;
            default -> List.<PathPattern>of();
        };
        var path = PathContainer.parsePath(request.getRequestURI());
        return patterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static List<PathPattern> patterns(String... paths) {
        return java.util.Arrays.stream(paths).map(PathPatternParser.defaultInstance::parse).toList();
    }
}
