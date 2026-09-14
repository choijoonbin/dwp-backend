package com.dwp.services.approval.attachment;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import java.util.*;

public final class ApprovalAttachmentEndpointPolicy {
    private ApprovalAttachmentEndpointPolicy(){ }
    private static final String UUID_PATH="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public record Endpoint(String method,String path,String routeKey,String permission,boolean management,boolean publish){ }
    public static final List<Endpoint> ENDPOINTS=List.of(
            admin("GET","/policy","attachment-policy.data","VIEW",false),
            admin("POST","/policies","attachment-policy-initialize.action","UPDATE",false),
            admin("PUT","/policies/{policyId}/draft","attachment-policy-draft.action","UPDATE",false),
            admin("POST","/policies/{policyId}/publish","attachment-policy-publish.action","PUBLISH",true),
            work("POST","/requests/{requestId}/attachment-uploads","request-attachment-reserve.action","ACTION.APPROVAL_REQUEST:UPDATE"),
            work("PUT","/attachment-uploads/{uploadId}/content","attachment-upload-content.action","ACTION.APPROVAL_REQUEST:UPDATE"),
            work("GET","/attachment-uploads/{uploadId}","attachment-upload.data","ACTION.APPROVAL_REQUEST:VIEW"),
            work("POST","/attachment-uploads/{uploadId}/reconcile","attachment-upload-reconcile.action","ACTION.APPROVAL_REQUEST:UPDATE"),
            work("POST","/attachment-uploads/{uploadId}/cancel","attachment-upload-cancel.action","ACTION.APPROVAL_REQUEST:UPDATE"),
            work("PUT","/requests/{requestId}/attachments","request-attachment-selection.action","ACTION.APPROVAL_REQUEST:UPDATE"),
            work("GET","/requests/{requestId}/attachments","request-attachments.data","ACTION.APPROVAL_REQUEST:VIEW"),
            work("GET","/tasks/{taskId}/attachments","task-attachments.data","ACTION.APPROVAL_TASK:VIEW"),
            work("POST","/requests/{requestId}/attachments/{attachmentId}/downloads","request-attachment-download.action","ACTION.APPROVAL_REQUEST:EXPORT"),
            work("POST","/tasks/{taskId}/attachments/{attachmentId}/downloads","task-attachment-download.action","ACTION.APPROVAL_TASK:EXPORT"),
            // The grant's DB-pinned owner type determines REQUEST:EXPORT or TASK:EXPORT at both materialization boundaries.
            work("GET","/attachment-downloads/{grantId}/content","attachment-download-content.data","APP.APPROVALS:VIEW"));
    public static Endpoint exact(HttpServletRequest request){return ENDPOINTS.stream().filter(endpoint->endpoint.method().equals(request.getMethod()) && request.getRequestURI().matches(endpoint.path().replaceAll("\\{[^}]+}",UUID_PATH))).findFirst().orElse(null);}
    public static boolean matches(HttpServletRequest request){
        try {var path=PathContainer.parsePath(request.getRequestURI());return ENDPOINTS.stream().anyMatch(endpoint->PathPatternParser.defaultInstance.parse(endpoint.path()).matches(path));}
        catch(IllegalArgumentException malformed){return request.getRequestURI().contains("attachment");}
    }
    public static boolean legacyAuthorized(HttpServletRequest request,Set<String> roles,Set<String> permissions){
        var endpoint=exact(request);if(endpoint==null || endpoint.publish() || roles.stream().anyMatch(role->role.startsWith("PROVIDER_")) || !permissions.contains("APP.APPROVALS:VIEW")) return false;
        if(endpoint.management()) return permissions.contains(endpoint.permission()) || permissions.contains("ADMIN.APPROVAL_POLICY:MANAGE");
        String view=endpoint.permission().substring(0,endpoint.permission().lastIndexOf(':'))+":VIEW";return permissions.contains(view) && permissions.contains(endpoint.permission());
    }
    private static Endpoint work(String method,String path,String key,String permission){return new Endpoint(method,"/v1"+path,"route.approvals.work."+key,permission,false,false);}
    private static Endpoint admin(String method,String path,String key,String permission,boolean publish){return new Endpoint(method,"/v1/admin/attachments"+path,"route.approvals.admin."+key,"ADMIN.APPROVAL_POLICY:"+permission,true,publish);}
}
