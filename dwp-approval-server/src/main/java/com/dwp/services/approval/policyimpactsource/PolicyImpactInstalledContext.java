package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;
import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceProtocol.*;

import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A private seal of installed DATA evidence. It is deliberately not a fresh-grant authority Window. */
public final class PolicyImpactInstalledContext {
    private final ApprovalIdentityDirectory identities;
    private final PolicyImpactSourceJson json;
    private final Clock clock;
    public PolicyImpactInstalledContext(ApprovalIdentityDirectory identities, PolicyImpactSourceJson json, Clock clock) {
        this.identities = identities; this.json = json; this.clock = clock;
    }
    public static final class Seal {
        private final ApprovalRequestContext.Actor actor;
        private final ApprovalDecisionRevisionContext.Evidence evidence;
        private final ApprovalManagementScopeContext.Evidence scope;
        private final String mode, identityVector;
        private final UUID policyId;
        private final long expectedVersion;
        private Seal(ApprovalRequestContext.Actor actor, ApprovalDecisionRevisionContext.Evidence evidence,
                ApprovalManagementScopeContext.Evidence scope, String mode, String identityVector, UUID policyId, long expectedVersion) {
            this.actor = actor; this.evidence = evidence; this.scope = scope; this.mode = mode;
            this.identityVector = identityVector; this.policyId = policyId; this.expectedVersion = expectedVersion;
        }
        public long tenantId() { return actor.tenantId(); }
        public long actorId() { return actor.userId(); }
        public UUID personPublicId() { return actor.personPublicId(); }
        public String resourceSetKey() { return scope.resourceSetKey(); }
        public String contextKey() { return evidence.contextKey(); }
        public String contextScopeKey() { return evidence.contextScopeKey(); }
        public String decisionRevision() { return evidence.revision(); }
        public String rolloutState() { return evidence.rolloutState(); }
        public Instant validUntil() { return evidence.validUntil().toInstant(); }
        public String accessMode() { return mode; }
        public UUID policyId() { return policyId; }
        public long expectedVersion() { return expectedVersion; }
        private boolean same(Seal other) {
            return actor.equals(other.actor) && evidence.equals(other.evidence) && scope.equals(other.scope)
                    && mode.equals(other.mode) && identityVector.equals(other.identityVector)
                    && policyId.equals(other.policyId) && expectedVersion == other.expectedVersion;
        }
    }
    public Seal capture(HttpServletRequest request, UUID policyId, long expectedVersion) {
        if (request == null || policyId == null || expectedVersion < 0 || expectedVersion > MAX_SAFE_INTEGER) throw denied();
        String path = "/v1/admin/policies/" + policyId + "/impact";
        if (!"GET".equals(request.getMethod()) || !path.equals(request.getRequestURI())) throw denied();
        var evidence = ApprovalDecisionRevisionContext.current().orElseThrow(PolicyImpactSourceJson::unavailable);
        var scope = ApprovalManagementScopeContext.current().orElseThrow(PolicyImpactSourceJson::unavailable);
        if (!queryMatches(request.getQueryString(), expectedVersion, scope.opaqueScopeKey())) throw denied();
        var installed = ApprovalPilotAuthorizationContext.current().orElseThrow(PolicyImpactSourceJson::unavailable);
        if (evidence.validUntil() == null || !evidence.validUntil().toInstant().isAfter(clock.instant())
                || !ROUTE.equals(evidence.routeContractKey()) || evidence.revision() == null || !evidence.revision().matches("psr-[a-f0-9]{64}")
                || evidence.contextKey() == null || evidence.contextKey().isBlank() || evidence.contextKey().length() > 512
                || evidence.contextScopeKey() == null || !scope.opaqueScopeKey().equals(evidence.contextScopeKey())
                || !Set.of("110", "111").contains(evidence.rolloutState())) throw unavailable();
        if (installed.size() != 3 || installed.stream().anyMatch(value -> !ROUTE.equals(value.routeContractKey())
                || !"DATA".equals(value.routeKind()) || !"full-management".equals(value.profileKey()) || !value.readOnly()
                || !REQUIRED.containsKey(value.capabilityContractKey())
                || !REQUIRED.get(value.capabilityContractKey()).equals(value.resolvedCapabilityCode())
                || !"APP_CONFIG_ADMIN".equals(value.requiredResponsibilityCode()) || value.highRisk()
                || value.activationPolicy() != null || value.sodPolicyId() != null
                || !Integer.valueOf(1).equals(value.projectionSchemaVersion()) || !Boolean.FALSE.equals(value.projectionAdditionalProperties())
                || value.openApiSchemaSha256() == null || !value.openApiSchemaSha256().matches("[a-f0-9]{64}"))
                || !installed.stream().map(ApprovalPilotPepRegistry.RouteAuthority::capabilityContractKey).collect(java.util.stream.Collectors.toSet())
                    .equals(REQUIRED.keySet())) throw unavailable();
        var modes = java.util.Collections.list(request.getHeaders("X-DWP-Active-Access-Mode"));
        if (modes.size() != 1 || !Set.of("NORMAL", "ELEVATED").contains(modes.getFirst())
                || request.getHeader("X-DWP-Support-Session-ID") != null) throw denied();
        final ApprovalRequestContext.Actor actor;
        try { actor = ApprovalRequestContext.require(); } catch (IllegalStateException missing) { throw unavailable(); }
        if (actor.tenantId() == null || actor.tenantId() <= 0 || actor.userId() == null || actor.userId() <= 0
                || actor.personPublicId() == null || actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))
                || !actor.permissions().contains("ADMIN.APPROVAL_POLICY:VIEW")) throw denied();
        var subject = identities.require(actor.tenantId(), actor.userId());
        if (subject == null || !subject.active() || !actor.tenantId().equals(subject.tenantId()) || !actor.userId().equals(subject.userId())
                || !actor.personPublicId().equals(subject.personPublicId())) throw denied();
        String vector = json.digest(Map.of("personPublicId", subject.personPublicId(), "status", subject.status(),
                "roles", subject.roles().stream().sorted().toList(), "permissions", subject.permissionKeys().stream().sorted().toList()));
        return new Seal(actor, evidence, scope, modes.getFirst(), vector, policyId, expectedVersion);
    }
    public void unchanged(Seal original, HttpServletRequest request) {
        if (!original.same(capture(request, original.policyId(), original.expectedVersion()))) throw changed();
    }
    static boolean queryMatches(String raw, long version, String scope) {
        if (version < 0 || version > MAX_SAFE_INTEGER || raw == null) return false;
        String required = "expectedVersion=" + version;
        if (raw.equals(required)) return true;
        if (scope == null || scope.isBlank()) return false;
        String prefix = required + "&contextScopeKey=";
        if (!raw.startsWith(prefix)) return false;
        String encoded = raw.substring(prefix.length());
        // Exact canonical UTF-8 encoding rejects aliases, malformed escapes, duplicates and arbitrary query order.
        return encoded.equals(org.springframework.web.util.UriUtils.encode(scope, java.nio.charset.StandardCharsets.UTF_8));
    }
}
