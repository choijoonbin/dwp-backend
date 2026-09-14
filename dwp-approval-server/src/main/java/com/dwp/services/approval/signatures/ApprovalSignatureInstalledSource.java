package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Binding;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Installed PEP evidence is an owner-source prerequisite, never a fresh Auth capability. */
final class ApprovalSignatureInstalledSource {
    private final ApprovalSignatureCanonical canonical;
    private final Clock clock;
    ApprovalSignatureInstalledSource(ApprovalSignatureCanonical canonical, Clock clock) { this.canonical = canonical; this.clock = clock; }
    record Seal(ApprovalRequestContext.Actor actor, ApprovalDecisionRevisionContext.Evidence evidence, String mode, String registrySha256) { }
    Seal capture(HttpServletRequest request, Binding binding) {
        String registry;
        try (var stream = new org.springframework.core.io.ClassPathResource("product-authorization/approval-pilot-pep-v10.generated.json").getInputStream()) {
            byte[] bytes = stream.readNBytes(1048577); if (bytes.length > 1048576) throw unavailable();
            JsonNode projection = canonical.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), JsonNode.class);
            var ref = projection.path("registryRef");
            if (!ref.path("version").isIntegralNumber() || ref.path("version").intValue() != 10
                    || !"product-surfaces".equals(ref.path("bundleKey").asText()) || !ref.path("sha256").isTextual()
                    || !ref.path("sha256").textValue().matches("[a-f0-9]{64}")) throw unavailable();
            registry = ref.path("sha256").textValue();
        } catch (Exception missing) { throw unavailable(); }
        if (request == null || binding.commandBody() == null || !binding.bodySha256().equals(canonical.digest(binding.commandBody()))
                || !binding.operation().method().equals(request.getMethod()) || !binding.operation().path(binding.objectId()).equals(request.getRequestURI())) throw denied();
        var evidence = ApprovalDecisionRevisionContext.current().orElseThrow(ApprovalSignatureCanonical::unavailable);
        var installed = ApprovalPilotAuthorizationContext.current().orElseThrow(ApprovalSignatureCanonical::unavailable);
        boolean high = binding.operation() == Operation.SIGN;
        String capability = high ? "approvals.work.signature.sign" : "POST".equals(binding.operation().method())
                ? "approvals.work.signature.update" : "approvals.work.signature.read";
        String permission = high ? "ACTION.APPROVAL_SIGNATURE:SIGN" : "POST".equals(binding.operation().method())
                ? "ACTION.APPROVAL_SIGNATURE:UPDATE" : "ACTION.APPROVAL_REQUEST:VIEW";
        if (installed.size() != 1 || installed.stream().anyMatch(value -> !binding.operation().route().equals(value.routeContractKey())
                || !capability.equals(value.capabilityContractKey()) || !permission.equals(value.resolvedCapabilityCode())
                || !"full-work".equals(value.profileKey()) || value.readOnly() || value.highRisk() != high
                || !(high ? "STEPUP-MGMT-HIGH-V1".equals(value.activationPolicy()) : value.activationPolicy() == null)
                || !Integer.valueOf(1).equals(value.projectionSchemaVersion()) || !Boolean.FALSE.equals(value.projectionAdditionalProperties())
                || value.openApiSchemaSha256() == null || !value.openApiSchemaSha256().matches("[a-f0-9]{64}"))) throw unavailable();
        if (!binding.operation().route().equals(evidence.routeContractKey()) || evidence.revision() == null || !evidence.revision().matches("psr-[a-f0-9]{64}")
                || evidence.validUntil() == null || !evidence.validUntil().toInstant().isAfter(clock.instant())
                || !Set.of("110", "111").contains(evidence.rolloutState()) || evidence.contextKey() == null || evidence.contextKey().isBlank()
                || evidence.contextScopeKey() == null || evidence.contextScopeKey().isBlank()) throw unavailable();
        var actor = ApprovalRequestContext.require();
        if (actor.tenantId() == null || actor.tenantId() <= 0 || actor.userId() == null || actor.userId() <= 0 || actor.personPublicId() == null
                || actor.roles().stream().anyMatch(value -> value.startsWith("PROVIDER_")) || !actor.permissions().contains("APP.APPROVALS:VIEW")
                || !actor.permissions().contains("ACTION.APPROVAL_REQUEST:VIEW") || !actor.permissions().contains(permission)) throw denied();
        String mode = single(request, "X-DWP-Active-Access-Mode");
        if (!Set.of("NORMAL", "ELEVATED").contains(mode) || request.getHeader("X-DWP-Support-Session-ID") != null) throw denied();
        for (var entry : request.getParameterMap().entrySet()) {
            if (!Set.of("locale", "contextScopeKey").contains(entry.getKey()) || entry.getValue().length != 1) throw denied();
            if (entry.getKey().equals("contextScopeKey") && !evidence.contextScopeKey().equals(entry.getValue()[0])) throw denied();
            if (entry.getKey().equals("locale") && (binding.operation() != Operation.CONTEXT
                    || !binding.commandBody().path("locale").asText().equals(entry.getValue()[0]))) throw denied();
        }
        if ("POST".equals(binding.operation().method()) && (!binding.idempotencyKey().equals(single(request, "Idempotency-Key"))
                || !binding.idempotencyKey().equals(binding.commandBody().path("idempotencyKey").asText()))) throw denied();
        return new Seal(actor, evidence, mode, registry);
    }
    void unchanged(Seal original, HttpServletRequest request, Binding binding) { if (!original.equals(capture(request, binding))) throw conflict(); }
    static String single(HttpServletRequest request, String name) {
        var values = java.util.Collections.list(request.getHeaders(name)); if (values.size() != 1) throw denied(); return values.getFirst();
    }
}
