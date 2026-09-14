package com.dwp.services.approval.documentretention.management;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

public final class ApprovalRetentionEndpointPolicy {
    private ApprovalRetentionEndpointPolicy() {}
    private static final String UUID_PATH="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public record Endpoint(String method,String path,String routeKey,String permission,boolean high) {}
    public static final List<Endpoint> ENDPOINTS=List.of(
            endpoint("GET","/policy","retention-policy.data","POLICY:VIEW",false),
            endpoint("POST","/policies","retention-policy-initialize.action","POLICY:UPDATE",false),
            endpoint("PUT","/policies/{id}/draft","retention-policy-draft.action","POLICY:UPDATE",false),
            endpoint("POST","/policies/{id}/publish","retention-policy-publish.action","POLICY:PUBLISH",true),
            endpoint("GET","/records/{id}","retention-record.data","OPERATIONS:VIEW",false),
            endpoint("POST","/records/{id}/claims","retention-record-claim.action","OPERATIONS:EXECUTE",true),
            endpoint("GET","/claims/{id}","retention-claim.data","OPERATIONS:VIEW",false));
    public static boolean matches(HttpServletRequest request) {
        try {var path=PathContainer.parsePath(request.getRequestURI());
            return ENDPOINTS.stream().anyMatch(e->PathPatternParser.defaultInstance.parse(e.path()).matches(path));
        } catch(IllegalArgumentException bad) {return request.getRequestURI().startsWith("/v1/admin/retention/");}
    }
    public static Endpoint exact(HttpServletRequest request) {
        return ENDPOINTS.stream().filter(e->e.method().equals(request.getMethod())
                && request.getRequestURI().matches(e.path().replace("{id}",UUID_PATH))).findFirst().orElse(null);
    }
    public static boolean legacyAuthorized(HttpServletRequest request,Set<String> roles,Set<String> permissions) {
        var endpoint=exact(request);
        return endpoint!=null && !endpoint.high() && roles.stream().noneMatch(r->r.startsWith("PROVIDER_"))
                && permissions.contains("APP.APPROVALS:VIEW") && permissions.contains(endpoint.permission());
    }
    private static Endpoint endpoint(String method,String path,String key,String permission,boolean high) {
        return new Endpoint(method,"/v1/admin/retention"+path,"route.approvals.admin."+key,"ADMIN.APPROVAL_"+permission,high);
    }
}
