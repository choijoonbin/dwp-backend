package com.dwp.services.approval.document;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Set;

public final class ApprovalDocumentEndpointPolicy {
    private ApprovalDocumentEndpointPolicy() { }
    private static final String UUID_PATH = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public record Endpoint(String method, String path, String routeKey, String permission, boolean management, boolean publish) { }
    public static final List<Endpoint> ENDPOINTS = List.of(
            work("GET", "/requests/{id}/document-tools", "request-document-tools.data", "REQUEST:VIEW"),
            work("GET", "/tasks/{id}/document-tools", "task-document-tools.data", "TASK:VIEW"),
            work("GET", "/requests/{id}/comments", "request-comments.data", "REQUEST:VIEW"),
            work("GET", "/tasks/{id}/comments", "task-comments.data", "TASK:VIEW"),
            work("POST", "/requests/{id}/comments", "request-comment.action", "REQUEST:UPDATE"),
            work("POST", "/tasks/{id}/comments", "task-comment.action", "TASK:UPDATE"),
            work("POST", "/requests/{id}/document-exports", "request-document-export.action", "REQUEST:EXPORT"),
            work("POST", "/tasks/{id}/document-exports", "task-document-export.action", "TASK:EXPORT"),
            work("POST", "/requests/archive/document-exports", "archive-document-export.action", "REQUEST:EXPORT"),
            admin("GET", "/policy", "document-policy.data", "VIEW", false),
            admin("PUT", "/policies/{policyId}/draft", "document-policy-draft.action", "UPDATE", false),
            admin("POST", "/policies/{policyId}/publish", "document-policy-publish.action", "PUBLISH", true),
            admin("GET", "/holds/{id}", "document-hold.data", "VIEW", false),
            admin("POST", "/holds/{id}/proposals", "document-hold-proposal.action", "UPDATE", false),
            admin("POST", "/holds/{id}/publish", "document-hold-publish.action", "PUBLISH", true));

    public static boolean matches(HttpServletRequest request) {
        try { var path = PathContainer.parsePath(request.getRequestURI());
            return ENDPOINTS.stream().anyMatch(e -> PathPatternParser.defaultInstance.parse(e.path()).matches(path));
        } catch (IllegalArgumentException e) { return request.getRequestURI().contains("document-tools") || request.getRequestURI().contains("document-exports"); }
    }
    public static Endpoint exact(HttpServletRequest request) {
        return ENDPOINTS.stream().filter(e -> e.method().equals(request.getMethod())
                && request.getRequestURI().matches(e.path().replace("{id}", UUID_PATH).replace("{policyId}", UUID_PATH))).findFirst().orElse(null);
    }
    public static boolean legacyAuthorized(HttpServletRequest request, Set<String> roles, Set<String> permissions) {
        Endpoint endpoint = exact(request);
        if (endpoint == null || endpoint.publish() || roles.stream().anyMatch(r -> r.startsWith("PROVIDER_"))) return false;
        if (!permissions.contains("APP.APPROVALS:VIEW")) return false;
        if (endpoint.management()) return permissions.contains(endpoint.permission()) || permissions.contains("ADMIN.APPROVAL_POLICY:MANAGE");
        String view = endpoint.permission().substring(0, endpoint.permission().lastIndexOf(':')) + ":VIEW";
        return permissions.contains(view) && permissions.contains(endpoint.permission());
    }
    private static Endpoint work(String method, String path, String key, String permission) {
        return new Endpoint(method, "/v1" + path, "route.approvals.work." + key, "ACTION.APPROVAL_" + permission, false, false);
    }
    private static Endpoint admin(String method, String path, String key, String permission, boolean publish) {
        return new Endpoint(method, "/v1/admin/document-tools" + path, "route.approvals.admin." + key, "ADMIN.APPROVAL_POLICY:" + permission, true, publish);
    }
}
