package com.dwp.services.auth.service;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.dwp.services.auth.approvalsignatures.*;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Single native-signature bridge into the package-private current Auth evidence services. */
public final class ApprovalSignatureCurrentAuthorityBridge implements SignatureCurrentAuthority {
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService surfaces;
    private final ProductAuthorizationContractRepository contracts;
    private final StoredDescriptorSeal seals;
    private final JdbcTemplate jdbc;
    private final SignatureAuthorityJson json;
    private final Supplier<SignatureHighRiskVerifier> highRisk;
    private final Clock clock;
    public ApprovalSignatureCurrentAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, ProductAuthorizationContractRepository contracts, JdbcTemplate jdbc,
            ProductAuthorizationContractValidator validator, ObjectMapper mapper, SignatureAuthorityJson json,
            Supplier<SignatureHighRiskVerifier> highRisk, Clock clock) {
        this.identities = identities; this.surfaces = surfaces; this.contracts = contracts; this.jdbc = jdbc;
        seals = new StoredDescriptorSeal(jdbc, contracts, validator, mapper); this.json = json; this.highRisk = highRisk; this.clock = clock;
    }
    @Override public void requireRegistered(SignatureAuthorityBindings binding) { registry(binding); }
    private Registered registry(SignatureAuthorityBindings b) {
        try {
            var bundle = contracts.findActive("product-surfaces").orElseThrow(SignatureAuthorityJson::unavailable);
            var pointer = contracts.findActivePointer("product-surfaces").orElseThrow(SignatureAuthorityJson::unavailable);
            if (bundle.version() != 10 || !"ACTIVE".equals(bundle.bundleStatus()) || !pointer.bundleId().equals(bundle.bundleId())
                    || !b.registrySha256().equals(bundle.checksum())) throw unavailable();
            var registry = new Registry(seals.loadActive(bundle, pointer));
            var route = registry.routesByKey().get(b.operation().route());
            var capability = registry.capabilitiesByKey().get(b.operation().capability());
            boolean high = b.operation() == SignatureAuthorityProtocol.Operation.SIGN;
            boolean receipt=b.operation()==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT;
            if (route == null || capability == null || !"ACTIVE".equals(route.lifecycleState())
                    || !"approvals.work".equals(route.navigationContextId()) || route.subject() == null
                    || !"PRODUCT".equals(route.subject().type()) || !"approvals".equals(route.subject().productKey())
                    || !"approvals.work".equals(route.subject().surfaceKey()) || route.uiRouteId() != null || route.uiRoutePattern() != null
                    || !(b.operation().mutation() ? "ACTION" : "DATA").equals(route.routeKind())
                    || !Boolean.valueOf(!b.operation().mutation()).equals(route.sideEffectFree()) || route.accessProfiles().size() != 1
                    || !"ACTIVE".equals(capability.lifecycleState()) || !"approvals.work".equals(capability.surfaceKey())
                    || !"PERMISSION".equals(capability.authorityMode()) || !b.operation().permission().equals(capability.resolvedCapabilityCode())
                    || !"NOT_REQUIRED".equals(capability.responsibilityRequirement()) || !capability.requiresProductEntitlement()
                    || capability.mappingVersion() != 1 || capability.policyVersion() != 1
                    || !(high ? "HIGH" : "LOW").equals(capability.riskTier())
                    || !(high ? "STEPUP-MGMT-HIGH-V1".equals(capability.activationPolicy()) : capability.activationPolicy() == null)
                    || !capability.routeContractKeys().contains(b.operation().route())) throw unavailable();
            var profile = route.accessProfiles().getFirst();
            if (!(receipt?"approval.signature.command-receipt.v1":"full-work").equals(profile.profileKey()) || profile.precedence() != 300 || profile.readOnly()!=receipt
                    || !Set.copyOf(profile.activeAccessModes()).equals(Set.of("NORMAL", "ELEVATED"))
                    || !Set.copyOf(profile.targetBindingKinds()).equals(Set.of("SELF", "OBJECT"))
                    || profile.requiredAccess() == null || !"CAPABILITY".equals(profile.requiredAccess().type())
                    || !b.operation().capability().equals(profile.requiredAccess().capabilityContractKey())
                    || route.gatewayApiBindings().size() != 1 || route.servicePepBindings().size() != 1) throw unavailable();
            requireProjectionCount(b.operation(),profile.responseProjectionBindings()==null?0:profile.responseProjectionBindings().size());
            var service = route.servicePepBindings().getFirst(); var gateway = route.gatewayApiBindings().getFirst();
            String id = b.operation() == SignatureAuthorityProtocol.Operation.CONTEXT || b.operation() == SignatureAuthorityProtocol.Operation.CREATE
                    ? "{requestId}" : "{signatureRequestId}";
            String path = receipt?"/v1/signature-command-receipts/{idempotencyKey}":b.path().replace(b.objectId().toString(), id);
            if (!b.operation().method().equals(service.method()) || !service.method().equals(gateway.method())
                    || !"approval".equals(service.serviceKey()) || !path.equals(service.path())
                    || !("/api/approvals" + path).equals(gateway.path()) || !service.bindingKey().equals(gateway.bindingKey())
                    || !service.pathParameterConstraints().equals(gateway.pathParameterConstraints())
                    || !java.util.Objects.equals(service.queryParameterConstraints(), gateway.queryParameterConstraints())) throw unavailable();
            if (!b.operation().mutation()) {
                var projection = profile.responseProjectionBindings().getFirst();
                if (!service.bindingKey().equals(projection.apiBindingKey()) || !Integer.valueOf(1).equals(projection.schemaVersion())
                        || !Boolean.FALSE.equals(projection.additionalProperties()) || projection.openApiSchemaSha256() == null
                        || !projection.openApiSchemaSha256().matches("[a-f0-9]{64}")) throw unavailable();
            }
            return new Registered(bundle, pointer);
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    static void requireProjectionCount(SignatureAuthorityProtocol.Operation operation,int count) {
        if (count!=(operation.mutation()?0:1)) throw unavailable();
    }
    @Override public Observation requireCurrent(SignatureAuthorityProofVerifier.Verified proof) {
        var b = proof.binding(); var registered = registry(b);
        if (!proof.expiresAt().isAfter(clock.instant())) throw denied();
        var beforePrincipal = principal(b); var before = identities.load(b.tenantId(), b.actorId());
        if (!before.hasPermission("APP.APPROVALS:VIEW") || !before.hasPermission("ACTION.APPROVAL_REQUEST:VIEW")
                || !before.hasPermission(b.operation().permission()) || before.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw denied();
        var result = surfaces.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(b.tenantId(), b.actorId(), "approvals", "approvals.work",
                ProductSurfaceAuthorityDtos.AccessMode.valueOf(b.accessMode()), b.operation().route(), b.contextKey(), b.contextScopeKey(), null, null, List.of()));
        boolean high = b.operation() == SignatureAuthorityProtocol.Operation.SIGN;
        boolean receipt=b.operation()==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT;
        if (result == null || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) throw unavailable();
        if (!(result.decision() == ProductSurfaceAuthorityDtos.Decision.ALLOWED || high && result.decision() == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED)
                || result.accessSource() != ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT || !"work".equals(result.plane())
                || !b.contextKey().equals(result.contextKey()) || !"approvals".equals(result.productKey()) || !"approvals.work".equals(result.surfaceKey())
                || result.accessMode() != ProductSurfaceAuthorityDtos.AccessMode.valueOf(b.accessMode()) || !receipt && result.effectiveReadOnly()) throw denied();
        var selected = result.scopes().stream().filter(value -> b.contextScopeKey().equals(value.key())).toList();
        var grants = result.effectiveGrants().stream().filter(value -> value instanceof ProductSurfaceAuthorityDtos.CapabilityGrant cap
                && b.operation().capability().equals(cap.capabilityContractKey())).map(value -> (ProductSurfaceAuthorityDtos.CapabilityGrant) value).toList();
        if (selected.size() != 1 || !receipt && selected.getFirst().readOnly() || grants.size() != 1) throw denied();
        var grant = grants.getFirst();
        if (!b.operation().permission().equals(grant.resolvedCapabilityCode()) || !receipt && grant.readOnly()
                || !grant.requiresProductEntitlement() || grant.responsibilityRequirement() != ProductSurfaceAuthorityDtos.ResponsibilityRequirement.NOT_REQUIRED
                || !grant.scopeKeys().contains(b.contextScopeKey()) || !(grant.activationState() == ProductSurfaceAuthorityDtos.ActivationState.ACTIVE
                    || high && grant.activationState() == ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE)) throw denied();
        String policyRevision = "policy-10-" + registered.pointer().revision() + '-' + registered.bundle().checksum();
        if (!before.revision().equals(result.authRevision()) || !policyRevision.equals(result.policyRevision()) || result.revalidateAt() == null) throw changed();
        Instant expiry = proof.expiresAt(); expiry = earliest(expiry, result.validUntil()); expiry = earliest(expiry, result.revalidateAt());
        expiry = earliest(expiry, selected.getFirst().validUntil()); expiry = earliest(expiry, grant.validUntil());
        if (high) { Instant challengeExpiry = highRisk.get().verify(b); if (challengeExpiry.isBefore(expiry)) expiry = challengeExpiry; }
        var after = identities.load(b.tenantId(), b.actorId()); var afterPrincipal = principal(b); var afterRegistry = registry(b);
        if (!before.equals(after) || !beforePrincipal.equals(afterPrincipal) || !registered.equals(afterRegistry)) throw changed();
        expiry = Instant.ofEpochSecond(expiry.getEpochSecond()); if (!expiry.isAfter(clock.instant())) throw denied();
        String vector = json.digest(Map.of("principal", beforePrincipal, "identity", before, "registry", registered,
                "grants", result.effectiveGrants(), "scopes", result.scopes(), "contextKey", result.contextKey(), "scope", b.contextScopeKey()));
        return new Observation(result.authRevision(), policyRevision, registered.bundle().checksum(), vector, expiry, high);
    }
    private Map<String, Object> principal(SignatureAuthorityBindings b) {
        var rows = jdbc.query("""
                SELECT u.person_public_id,u.status,u.identity_plane,t.status AS tenant_status
                  FROM com_users u JOIN com_tenants t ON t.tenant_id=u.tenant_id
                 WHERE u.tenant_id=? AND u.user_id=?
                """, (row, n) -> {
            if (!b.personPublicId().equals(row.getObject("person_public_id", java.util.UUID.class)) || !"ACTIVE".equals(row.getString("status"))
                    || !"TENANT".equals(row.getString("identity_plane")) || !"ACTIVE".equals(row.getString("tenant_status"))) throw denied();
            return Map.<String, Object>of("tenant", b.tenantId(), "actor", b.actorId(), "person", b.personPublicId(),
                    "status", row.getString("status"), "plane", row.getString("identity_plane"), "tenantStatus", row.getString("tenant_status"));
        }, b.tenantId(), b.actorId());
        if (rows.size() != 1) throw denied(); return rows.getFirst();
    }
    private static Instant earliest(Instant left, java.time.OffsetDateTime right) {
        return right == null || !right.toInstant().isBefore(left) ? left : right.toInstant();
    }
    private record Registered(ProductAuthorizationContractRepository.StoredBundle bundle, ProductAuthorizationContractRepository.ActivePointer pointer) { }
}
