package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.approvalpolicyimpact.*;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The only package bridge: current Auth evidence and the installed registry, not caller capability headers. */
@Component
public final class ApprovalPolicyImpactIdentityAuthorityBridge implements PolicyImpactAuthorityPort {
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService surfaces;
    private final ProductAuthorizationContractRepository contracts;
    private final JdbcTemplate jdbc;
    private final PolicyImpactJson json;
    private final StoredDescriptorSeal seals;
    private final Clock clock;
    @Autowired
    public ApprovalPolicyImpactIdentityAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, ProductAuthorizationContractRepository contracts,
            JdbcTemplate jdbc, PolicyImpactJson json, ProductAuthorizationContractValidator validator, ObjectMapper mapper) {
        this(identities, surfaces, contracts, jdbc, json, validator, mapper, Clock.systemUTC());
    }
    public ApprovalPolicyImpactIdentityAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, ProductAuthorizationContractRepository contracts,
            JdbcTemplate jdbc, PolicyImpactJson json, ProductAuthorizationContractValidator validator, ObjectMapper mapper, Clock clock) {
        this.identities = identities; this.surfaces = surfaces; this.contracts = contracts;
        this.jdbc = jdbc; this.json = json; this.seals = new StoredDescriptorSeal(jdbc, contracts, validator, mapper); this.clock = clock;
    }
    @Override public void requireRegistered() { registry(); }
    private RegistryObservation registry() {
        try { return validatedRegistry(); }
        catch (RuntimeException invalid) { throw PolicyImpactJson.unavailable(); }
    }
    private RegistryObservation validatedRegistry() {
        var bundle = contracts.findActive("product-surfaces").orElseThrow(PolicyImpactJson::unavailable);
        var pointer = contracts.findActivePointer("product-surfaces").orElseThrow(PolicyImpactJson::unavailable);
        var registry = new Registry(seals.loadActive(bundle, pointer));
        if (bundle.version() != 9 || !"ACTIVE".equals(bundle.bundleStatus()) || !pointer.bundleId().equals(bundle.bundleId()))
            throw PolicyImpactJson.unavailable();
        var route = registry.routesByKey().get(PolicyImpactProtocol.ROUTE);
        if (route == null || !"ACTIVE".equals(route.lifecycleState()) || !"DATA".equals(route.routeKind())
                || !Boolean.TRUE.equals(route.sideEffectFree()) || route.subject() == null
                || !"PRODUCT".equals(route.subject().type()) || !"approvals.admin".equals(route.navigationContextId())
                || route.uiRouteId() != null || route.uiRoutePattern() != null
                || !"approvals".equals(route.subject().productKey()) || !"approvals.admin".equals(route.subject().surfaceKey())
                || route.accessProfiles().size() != 1) throw PolicyImpactJson.unavailable();
        var profile = route.accessProfiles().getFirst(); var required = profile.requiredAccess();
        if (!profile.readOnly() || !"full-management".equals(profile.profileKey()) || profile.precedence() != 300
                || !profile.targetBindingKinds().equals(List.of("OBJECT"))
                || required == null || !"CAPABILITY_EXPRESSION".equals(required.type())
                || required.capabilityContractKey() != null || required.accessPolicyKey() != null || !"ALL".equals(required.mode())
                || required.capabilityContractKeys() == null || required.capabilityContractKeys().size() != 3
                || !Set.copyOf(required.capabilityContractKeys()).equals(PolicyImpactProtocol.REQUIRED.keySet())
                || !Set.copyOf(profile.activeAccessModes()).equals(Set.of("NORMAL", "ELEVATED"))) throw PolicyImpactJson.unavailable();
        if (route.gatewayApiBindings().size() != 1 || route.servicePepBindings().size() != 1
                || profile.responseProjectionBindings().size() != 1) throw PolicyImpactJson.unavailable();
        var gateway = route.gatewayApiBindings().getFirst(); var service = route.servicePepBindings().getFirst();
        var projection = profile.responseProjectionBindings().getFirst();
        String bindingKey = PolicyImpactProtocol.ROUTE + ".binding.01";
        if (!bindingKey.equals(gateway.bindingKey()) || !bindingKey.equals(service.bindingKey())
                || !"GET".equals(gateway.method()) || !"GET".equals(service.method()) || !"approval".equals(service.serviceKey())
                || !"/api/approvals/v1/admin/policies/{policyId}/impact".equals(gateway.path())
                || !"/v1/admin/policies/{policyId}/impact".equals(service.path())
                || !gateway.pathParameterConstraints().equals(service.pathParameterConstraints())
                || !java.util.Objects.equals(gateway.queryParameterConstraints(), service.queryParameterConstraints())
                || !bindingKey.equals(projection.apiBindingKey()) || !Integer.valueOf(1).equals(projection.schemaVersion())
                || !Boolean.FALSE.equals(projection.additionalProperties())
                || projection.openApiSchemaSha256() == null || !projection.openApiSchemaSha256().matches("[a-f0-9]{64}"))
            throw PolicyImpactJson.unavailable();
        for (var entry : PolicyImpactProtocol.REQUIRED.entrySet()) {
            var capability = registry.capabilitiesByKey().get(entry.getKey());
            if (capability == null || !"ACTIVE".equals(capability.lifecycleState()) || !"VIEW".equals(capability.action())
                    || !"approvals.admin".equals(capability.surfaceKey()) || !entry.getValue().equals(capability.resolvedCapabilityCode())
                    || !"REQUIRED".equals(capability.responsibilityRequirement()) || !"APP_CONFIG_ADMIN".equals(capability.requiredResponsibilityCode())
                    || !"PERMISSION".equals(capability.authorityMode()) || !"LOW".equals(capability.riskTier())
                    || !"APP_RESOURCE_SET:RS_APPROVALS".equals(capability.scopeResolver())
                    || capability.mappingVersion() != 1 || capability.policyVersion() != 1
                    || capability.activationPolicy() != null || capability.sodPolicyId() != null || capability.requiresProductEntitlement()
                    || !capability.routeContractKeys().contains(PolicyImpactProtocol.ROUTE)) throw PolicyImpactJson.unavailable();
        }
        return new RegistryObservation(bundle, pointer, registry);
    }
    @Override public Current requireCurrent(PolicyImpactProofVerifier.Verified proof) {
        if (!proof.expiresAt().isAfter(clock.instant())) throw PolicyImpactJson.denied();
        var binding = proof.bindings(); var beforeRegistry = registry();
        var beforePrincipal = principal(binding); var before = identities.load(binding.tenantId(), binding.actorId());
        if (before.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) denied();
        var result = surfaces.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(binding.tenantId(), binding.actorId(),
                "approvals", "approvals.admin", ProductSurfaceAuthorityDtos.AccessMode.valueOf(binding.accessMode()),
                PolicyImpactProtocol.ROUTE, binding.contextKey(), binding.contextScopeKey(), null, null, List.of()));
        if (result == null || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) throw PolicyImpactJson.unavailable();
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED
                || result.accessSource() != ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT
                || !"management".equals(result.plane()) || !"approvals".equals(result.productKey())
                || !"approvals.admin".equals(result.surfaceKey()) || !result.effectiveReadOnly()
                || result.accessMode() != ProductSurfaceAuthorityDtos.AccessMode.valueOf(binding.accessMode())
                || !binding.contextKey().equals(result.contextKey())) denied();
        var selected = result.scopes().stream().filter(scope -> binding.contextScopeKey().equals(scope.key())).toList();
        if (selected.size() != 1 || !selected.getFirst().readOnly() || !before.revision().equals(result.authRevision())
                || result.policyRevision() == null || !result.policyRevision().equals("policy-" + beforeRegistry.bundle().version()
                    + '-' + beforeRegistry.pointer().revision() + '-' + beforeRegistry.bundle().checksum())) changed();
        Instant expiry = earliest(proof.expiresAt(), selected.getFirst().validUntil());
        expiry = earliest(expiry, result.validUntil()); expiry = earliest(expiry, result.revalidateAt());
        var grants = new java.util.ArrayList<Grant>();
        for (var entry : new java.util.TreeMap<>(PolicyImpactProtocol.REQUIRED).entrySet()) {
            var matches = result.effectiveGrants().stream().filter(grant -> grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant cap
                    && entry.getKey().equals(cap.capabilityContractKey())).map(grant -> (ProductSurfaceAuthorityDtos.CapabilityGrant) grant).toList();
            if (matches.size() != 1) denied(); var grant = matches.getFirst();
            var capability = beforeRegistry.registry().capabilitiesByKey().get(entry.getKey());
            if (!entry.getValue().equals(grant.resolvedCapabilityCode()) || !grant.readOnly()
                    || grant.activationState() != ProductSurfaceAuthorityDtos.ActivationState.ACTIVE
                    || grant.responsibilityRequirement() != ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED
                    || grant.responsibility() == null || !binding.resourceSetKey().equals(grant.responsibility().resourceSetKey())
                    || !capability.requiredResponsibilityCode().equals(grant.responsibility().code())
                    || !grant.scopeKeys().contains(binding.contextScopeKey())) denied();
            var duties = ScopedAdminDutyPolicy.matchingDuties(before, capability, "APP.APPROVALS").stream()
                    .filter(duty -> binding.resourceSetKey().equals(duty.resourceSetKey())
                    && duty.containsResource(capability.resourceKey())
                    && duty.tenantId() == binding.tenantId() && duty.userId() == binding.actorId()).toList();
            var responsibilities = ScopedAdminDutyPolicy.matchingResponsibilities(before, capability, duties, "APP.APPROVALS");
            if (duties.isEmpty() || responsibilities.isEmpty()) denied();
            for (var duty : duties) expiry = earliest(expiry, duty.validTo());
            for (var responsibility : responsibilities) expiry = earliest(expiry, responsibility.validTo());
            expiry = earliest(expiry, grant.validUntil());
            grants.add(new Grant(entry.getKey(), entry.getValue(), binding.resourceSetKey(), binding.contextScopeKey(), proof.expiresAt()));
        }
        var after = identities.load(binding.tenantId(), binding.actorId()); var afterPrincipal = principal(binding); var afterRegistry = registry();
        if (!before.equals(after) || !beforePrincipal.equals(afterPrincipal)
                || !beforeRegistry.bundle().equals(afterRegistry.bundle()) || !beforeRegistry.pointer().equals(afterRegistry.pointer())) changed();
        expiry = Instant.ofEpochSecond(expiry.getEpochSecond());
        if (!expiry.isAfter(clock.instant())) denied();
        var finalExpiry = expiry;
        grants.replaceAll(grant -> new Grant(grant.capabilityContractKey(), grant.resolvedCapabilityCode(), grant.resourceSetKey(), grant.contextScopeKey(), finalExpiry));
        var identityVector = Map.of("revision", before.revision(), "permissions", before.permissions().stream().sorted().toList(),
                "roles", before.roles().stream().sorted().toList(), "responsibilities", before.responsibilities(),
                "scopedDuties", before.scopedDuties());
        String vector = json.evidenceDigest(Map.of("identity", identityVector, "principal", beforePrincipal,
                "bundle", beforeRegistry.bundle(), "pointer", beforeRegistry.pointer(), "grants", grants,
                "contextKey", result.contextKey(), "contextScopeKey", binding.contextScopeKey()));
        return new Current(result.authRevision(), result.policyRevision(), "apia-" + vector, vector, clock.instant(), expiry, grants);
    }
    private Map<String, Object> principal(PolicyImpactBindings binding) {
        var rows = jdbc.query("""
                SELECT u.user_id,u.tenant_id,u.person_public_id,u.status,u.identity_plane,t.status AS tenant_status
                  FROM com_users u JOIN com_tenants t ON t.tenant_id=u.tenant_id
                 WHERE u.tenant_id=? AND u.user_id=?
                """, (row, index) -> {
            if (!"ACTIVE".equals(row.getString("status")) || !"TENANT".equals(row.getString("identity_plane"))
                    || !"ACTIVE".equals(row.getString("tenant_status"))
                    || !binding.personPublicId().equals(row.getObject("person_public_id", java.util.UUID.class))) denied();
            return Map.<String, Object>of("actorId", row.getLong("user_id"), "tenantId", row.getLong("tenant_id"),
                    "personPublicId", binding.personPublicId(), "status", row.getString("status"),
                    "identityPlane", row.getString("identity_plane"), "tenantStatus", row.getString("tenant_status"));
        }, binding.tenantId(), binding.actorId());
        if (rows.size() != 1) denied(); return rows.getFirst();
    }
    private static Instant earliest(Instant left, java.time.OffsetDateTime right) {
        return right == null || !right.toInstant().isBefore(left) ? left : right.toInstant();
    }
    private static void denied() { throw PolicyImpactJson.denied(); }
    private static void changed() { throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Policy impact source authority changed."); }
    private record RegistryObservation(ProductAuthorizationContractRepository.StoredBundle bundle,
            ProductAuthorizationContractRepository.ActivePointer pointer, Registry registry) { }
}
