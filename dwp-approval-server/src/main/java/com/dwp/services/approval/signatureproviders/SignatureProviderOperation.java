package com.dwp.services.approval.signatureproviders;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Proposed native operation vocabulary, not an installed registry or an authorization grant. */
public enum SignatureProviderOperation {
    DIAGNOSTICS("GET", "/v1/admin/signatures/diagnostics", "signature-diagnostics.data", "ADMIN.APPROVAL_SIGNATURE:VIEW", false),
    PROVIDER_DIAGNOSTICS("GET", "/v1/admin/signatures/providers/{providerId}/diagnostics", "signature-provider-diagnostics.data", "ADMIN.APPROVAL_SIGNATURE:VIEW", false),
    DIAGNOSTIC_HISTORY("GET", "/v1/admin/signatures/diagnostic-history", "signature-diagnostic-history.data", "ADMIN.APPROVAL_SIGNATURE:VIEW", false),
    PROBE("POST", "/v1/admin/signatures/probes", "signature-probe.action", "ADMIN.APPROVAL_SIGNATURE:MANAGE", false),
    KMS_PROBE("POST", "/v1/admin/signatures/kms/probes", "signature-kms-probe.action", "ADMIN.APPROVAL_SIGNATURE:MANAGE", false),
    WORM_INSPECTION("POST", "/v1/admin/signatures/worm-inspections", "signature-worm-inspection.action", "ADMIN.APPROVAL_SIGNATURE:MANAGE", false),
    POLICY("GET", "/v1/admin/signatures/policy", "signature-policy.data", "ADMIN.APPROVAL_SIGNATURE:VIEW", false),
    POLICY_INITIALIZE("POST", "/v1/admin/signatures/policies", "signature-policy-initialize.action", "ADMIN.APPROVAL_SIGNATURE:MANAGE", false),
    POLICY_DRAFT("PUT", "/v1/admin/signatures/policies/{policyId}/draft", "signature-policy-draft-update.action", "ADMIN.APPROVAL_SIGNATURE:MANAGE", false),
    POLICY_PUBLISH("POST", "/v1/admin/signatures/policies/{policyId}/publish", "signature-policy-publish.action", "ADMIN.APPROVAL_SIGNATURE:PUBLISH", true),
    POLICY_HISTORY("GET", "/v1/admin/signatures/policies/{policyId}/history", "signature-policy-history.data", "ADMIN.APPROVAL_SIGNATURE:VIEW", false),
    EXTERNAL_CONTEXT("GET", "/v1/requests/{requestId}/external-signature-context", "external-signature-context.data", "ACTION.APPROVAL_REQUEST:VIEW", false),
    EXTERNAL_CREATE("POST", "/v1/requests/{requestId}/external-signature-requests", "external-signature-request-create.action", "ACTION.APPROVAL_SIGNATURE:UPDATE", false),
    EXTERNAL_GET("GET", "/v1/external-signature-requests/{signatureRequestId}", "external-signature-request.data", "ACTION.APPROVAL_REQUEST:VIEW", false),
    EXTERNAL_HANDOVER("POST", "/v1/external-signature-requests/{signatureRequestId}/handovers", "external-signature-handover.action", "ACTION.APPROVAL_SIGNATURE:SIGN", true),
    EXTERNAL_REFRESH("POST", "/v1/external-signature-requests/{signatureRequestId}/refresh", "external-signature-refresh.action", "ACTION.APPROVAL_SIGNATURE:UPDATE", false),
    EXTERNAL_CANCEL("POST", "/v1/external-signature-requests/{signatureRequestId}/cancel", "external-signature-cancel.action", "ACTION.APPROVAL_SIGNATURE:UPDATE", false),
    EXTERNAL_AUDIT("GET", "/v1/external-signature-requests/{signatureRequestId}/audit", "external-signature-audit.data", "ACTION.APPROVAL_REQUEST:VIEW", false),
    EXTERNAL_ARTIFACT("GET", "/v1/external-signature-requests/{signatureRequestId}/artifacts/{artifactId}", "external-signature-artifact.data", "ACTION.APPROVAL_REQUEST:VIEW", false);

    private static final String UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private final String method, path, route, permission;
    private final boolean highRisk;

    SignatureProviderOperation(String method, String path, String leaf, String permission, boolean highRisk) {
        this.method = method; this.path = path; this.permission = permission; this.highRisk = highRisk;
        route = "route.approvals." + (path.startsWith("/v1/admin/") ? "admin." : "work.") + leaf;
    }

    public String method() { return method; }
    public String pathTemplate() { return path; }
    public String routeContractKey() { return route; }
    public String permission() { return permission; }
    public boolean highRisk() { return highRisk; }
    public boolean readOnly() { return method.equals("GET"); }
    public String routeKind() { return readOnly() ? "DATA" : "ACTION"; }

    public String path(UUID target, UUID artifact) {
        var variables = Pattern.compile("\\{[A-Za-z]+}").matcher(path); var result = new StringBuilder(); int count = 0;
        while (variables.find()) {
            UUID id = count++ == 0 ? target : artifact;
            variables.appendReplacement(result, SignatureProviderModel.required(id).toString());
        }
        variables.appendTail(result);
        if (count == 0 && target != null || count < 2 && artifact != null) throw SignatureProviderModel.invalid("Unexpected operation target");
        return result.toString();
    }

    public static Optional<SignatureProviderOperation> resolve(String method, String path) {
        if (method == null || path == null || path.length() > 512 || path.contains("%") || path.contains("?") || path.contains("#"))
            return Optional.empty();
        for (var operation : values()) {
            String regex = operation.path.replaceAll("\\{[A-Za-z]+}", UUID_PATTERN);
            if (operation.method.equals(method) && path.matches(regex)) return Optional.of(operation);
        }
        return Optional.empty();
    }
}
