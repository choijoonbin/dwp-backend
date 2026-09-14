package com.dwp.services.approval.security;

import com.dwp.services.approval.attachment.ApprovalAttachmentEndpointPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Closed final9 endpoints only. Recognition is not authorization and never substitutes a native owner guard. */
final class ApprovalRelease9EndpointPolicy {
    private static final String UUID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    record Endpoint(String method, String path, String routeKey, boolean sealedRequired, Set<String> queryKeys,
                    Pattern exactPath, PathPattern recognition) { }
    static final List<Endpoint> ENDPOINTS = endpoints();
    private ApprovalRelease9EndpointPolicy() { }

    private static List<Endpoint> endpoints() {
        var result = new ArrayList<Endpoint>();
        for (var attachment : ApprovalAttachmentEndpointPolicy.ENDPOINTS) result.add(endpoint(attachment.method(), attachment.path(),
                attachment.routeKey(), attachment.publish(), Set.of()));
        String form = "/v1/admin/forms/{formId}";
        result.add(form("GET", form + "/versions", "form-version-history.data", Set.of("size")));
        result.add(form("GET", form + "/versions/{formVersionId}", "form-version-detail.data", Set.of()));
        result.add(form("GET", form + "/diff", "form-version-diff.data", Set.of("fromVersionId", "toVersionId")));
        result.add(form("GET", form + "/working-draft", "form-working-draft.data", Set.of()));
        result.add(form("PUT", form + "/working-draft", "form-working-draft-update.action", Set.of()));
        result.add(form("POST", form + "/versions/{formVersionId}/branch", "form-version-branch.action", Set.of()));
        result.add(form("POST", form + "/retire", "form-retire.action", Set.of()));
        result.add(form("POST", form + "/reinstate", "form-reinstate.action", Set.of()));
        result.add(form("GET", form + "/publish-review", "form-publish-review.data", Set.of()));
        result.add(form("POST", form + "/publish-reviewed", "form-reviewed-publish.action", Set.of()));
        result.add(endpoint("POST", "/v1/requests/{requestId}/information-commands/{originalKey}/receipt",
                "route.approvals.work.information-command-receipt.data", true, Set.of()));
        if (result.size() != 26 || result.stream().map(Endpoint::routeKey).distinct().count() != 26)
            throw new IllegalStateException("Expected the closed final9 endpoint set.");
        return List.copyOf(result);
    }
    private static Endpoint form(String method, String path, String key, Set<String> queryKeys) {
        return endpoint(method, path, "route.approvals.admin." + key, true, queryKeys);
    }
    private static Endpoint endpoint(String method, String path, String key, boolean sealed, Set<String> queries) {
        String regex = path.replaceAll("\\{(?!originalKey})[^}]+}", UUID).replace("{originalKey}", "[A-Za-z0-9._:-]{1,120}");
        return new Endpoint(method, path, key, sealed, queries, Pattern.compile("^" + regex + "$"),
                PathPatternParser.defaultInstance.parse(path));
    }
    static Endpoint exact(HttpServletRequest request) {
        return ENDPOINTS.stream().filter(endpoint -> endpoint.method().equals(request.getMethod())
                && endpoint.exactPath().matcher(request.getRequestURI()).matches()).findFirst().orElse(null);
    }
    static boolean recognizes(HttpServletRequest request) {
        try {
            String normalized = request.getRequestURI().replaceAll("/+", "/").replaceAll("/$", "");
            var path = PathContainer.parsePath(normalized);
            return ENDPOINTS.stream().anyMatch(endpoint -> endpoint.recognition().matches(path));
        } catch (IllegalArgumentException malformed) { return false; }
    }
    static boolean installed(Endpoint endpoint, ApprovalPilotPepRegistry registry) {
        return registry.bindingContracts().stream().anyMatch(binding -> endpoint.routeKey().equals(binding.routeContractKey())
                && endpoint.method().equals(binding.method()) && endpoint.path().equals(binding.servicePath()));
    }
    static boolean legacyAuthorized(HttpServletRequest request, Set<String> roles, Set<String> permissions) {
        var endpoint = exact(request);
        return endpoint != null && !endpoint.sealedRequired()
                && ApprovalAttachmentEndpointPolicy.legacyAuthorized(request, roles, permissions);
    }
}
