package com.dwp.services.auth.service;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.dwp.services.auth.workflowplanning.*;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Current Auth-owned management authority. The caller's Gateway revision is never an Auth revision. */
@Component
public final class PlanningIdentityAuthorityBridge implements PlanningAuthorityPort {
    private static final String SELECTION_ROUTE="route.approvals.admin.workflow-planning-selection.data";
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService surfaces;
    private final ProductAuthorizationContractRepository contracts;
    private final JdbcTemplate jdbc;
    private final PlanningJson json;
    private final StoredDescriptorSeal seals;
    private final Clock clock=Clock.systemUTC();
    public PlanningIdentityAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities, ProductSurfaceAuthorityService surfaces,
            ProductAuthorizationContractRepository contracts, JdbcTemplate jdbc, PlanningJson json,
            ProductAuthorizationContractValidator validator, ObjectMapper mapper) {
        this.identities=identities; this.surfaces=surfaces; this.contracts=contracts; this.jdbc=jdbc; this.json=json;
        this.seals=new StoredDescriptorSeal(jdbc,contracts,validator,mapper);
    }
    @Override public void requireRegistered() { registry(); }
    private Observation registry() {
        try {
            var bundle=contracts.findActive("product-surfaces").orElseThrow(PlanningProtocol::unavailable);
            var pointer=contracts.findActivePointer("product-surfaces").orElseThrow(PlanningProtocol::unavailable);
            var registry=new Registry(seals.loadActive(bundle,pointer));
            if (!Set.of(10L, 11L, 12L, 13L, 14L, 15L, 16L).contains(bundle.version()) || !"ACTIVE".equals(bundle.bundleStatus()) || !pointer.bundleId().equals(bundle.bundleId())) throw unavailable();
            var route=registry.routesByKey().get(ROUTE);
            if (route==null || route.accessProfiles()==null || route.accessProfiles().size()!=1) throw unavailable();
            var profile=route.accessProfiles().getFirst();
            if (profile.responseProjectionBindings()==null || profile.responseProjectionBindings().size()!=1
                    || !ProductAuthorizationRelease10ProjectionSchema.matches(route,"full-management",profile.responseProjectionBindings().getFirst())) throw unavailable();
            for (var entry : REQUIRED.entrySet()) {
                var capability=registry.capabilitiesByKey().get(entry.getKey());
                if (capability==null || !"ACTIVE".equals(capability.lifecycleState()) || !"approvals.admin".equals(capability.surfaceKey())
                        || !entry.getValue().equals(capability.resolvedCapabilityCode()) || !entry.getValue().endsWith(':'+capability.action())
                        || !"REQUIRED".equals(capability.responsibilityRequirement()) || !"APP_CONFIG_ADMIN".equals(capability.requiredResponsibilityCode())
                        || !"PERMISSION".equals(capability.authorityMode()) || !"LOW".equals(capability.riskTier())
                        || !"APP_RESOURCE_SET:RS_APPROVALS".equals(capability.scopeResolver()) || capability.requiresProductEntitlement()
                        || capability.mappingVersion()!=1 || capability.policyVersion()!=1 || capability.activationPolicy()!=null || capability.sodPolicyId()!=null
                        || !capability.routeContractKeys().equals(expectedCapabilityRoutes(bundle.version()))) throw unavailable();
            }
            return new Observation(bundle,pointer,registry);
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private static List<String> expectedCapabilityRoutes(long version) {
        return version==10L?List.of(ROUTE):List.of(SELECTION_ROUTE,ROUTE);
    }
    @Override public Owner requireCurrent(PlanningProofVerifier.Verified proof) {
        if (proof==null || !proof.expiresAt().isAfter(clock.instant())) throw denied();
        var binding=proof.bindings(); var beforeRegistry=registry(); var beforePrincipal=principal(binding);
        var before=identities.load(binding.tenantId(),binding.actorId());
        if (before.roles().stream().anyMatch(role->role.startsWith("PROVIDER_"))) throw denied();
        var mode=ProductSurfaceAuthorityDtos.AccessMode.valueOf(binding.accessMode());
        var result=surfaces.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(binding.tenantId(),binding.actorId(),"approvals","approvals.admin",mode,
                ROUTE,binding.contextKey(),binding.contextScopeKey(),null,null,List.of()));
        if (result==null || result.decision()==ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) throw unavailable();
        if (result.decision()!=ProductSurfaceAuthorityDtos.Decision.ALLOWED || result.accessSource()!=ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT
                || !"management".equals(result.plane()) || !"approvals".equals(result.productKey()) || !"approvals.admin".equals(result.surfaceKey())
                || !result.effectiveReadOnly() || result.accessMode()!=mode || !binding.contextKey().equals(result.contextKey())) throw denied();
        var scopes=result.scopes().stream().filter(scope->binding.contextScopeKey().equals(scope.key())).toList();
        if (scopes.size()!=1 || !scopes.getFirst().readOnly() || !before.revision().equals(result.authRevision())
                || !("policy-"+beforeRegistry.bundle.version()+'-'+beforeRegistry.pointer.revision()+'-'+beforeRegistry.bundle.checksum()).equals(result.policyRevision())) throw changed();
        Instant expiry=earliest(proof.expiresAt(),scopes.getFirst().validUntil());
        expiry=earliest(expiry,result.validUntil()); expiry=earliest(expiry,result.revalidateAt());
        var grants=new java.util.ArrayList<Object>();
        for (var entry : new java.util.TreeMap<>(REQUIRED).entrySet()) {
            var matches=result.effectiveGrants().stream().filter(grant->grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant cap
                    && entry.getKey().equals(cap.capabilityContractKey())).map(grant->(ProductSurfaceAuthorityDtos.CapabilityGrant)grant).toList();
            if (matches.size()!=1) throw denied(); var grant=matches.getFirst(); var capability=beforeRegistry.registry.capabilitiesByKey().get(entry.getKey());
            if (!entry.getValue().equals(grant.resolvedCapabilityCode()) || !grant.readOnly()
                    || grant.activationState()!=ProductSurfaceAuthorityDtos.ActivationState.ACTIVE
                    || grant.responsibilityRequirement()!=ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED
                    || grant.responsibility()==null || !binding.resourceSetKey().equals(grant.responsibility().resourceSetKey())
                    || !"APP_CONFIG_ADMIN".equals(grant.responsibility().code()) || !grant.scopeKeys().contains(binding.contextScopeKey())) throw denied();
            var duties=ScopedAdminDutyPolicy.matchingDuties(before,capability,"APP.APPROVALS").stream()
                    .filter(duty->binding.resourceSetKey().equals(duty.resourceSetKey()) && duty.containsResource(capability.resourceKey())
                            && duty.tenantId()==binding.tenantId() && duty.userId()==binding.actorId()).toList();
            var responsibilities=ScopedAdminDutyPolicy.matchingResponsibilities(before,capability,duties,"APP.APPROVALS");
            if (duties.isEmpty() || responsibilities.isEmpty()) throw denied();
            for(var duty:duties) expiry=earliest(expiry,duty.validTo());
            for(var responsibility:responsibilities) expiry=earliest(expiry,responsibility.validTo());
            expiry=earliest(expiry,grant.validUntil()); grants.add(grant);
        }
        var after=identities.load(binding.tenantId(),binding.actorId()); var afterPrincipal=principal(binding); var afterRegistry=registry();
        if (!before.equals(after) || !beforePrincipal.equals(afterPrincipal) || !beforeRegistry.bundle.equals(afterRegistry.bundle)
                || !beforeRegistry.pointer.equals(afterRegistry.pointer)) throw changed();
        expiry=Instant.ofEpochSecond(expiry.getEpochSecond()); if(!expiry.isAfter(clock.instant())) throw denied();
        var vector=json.tree(Map.of("principal",beforePrincipal,"authRevision",before.revision(),"policyRevision",result.policyRevision(),
                "bundle",beforeRegistry.bundle,"pointer",beforeRegistry.pointer,"selectedScope",scopes.getFirst(),"grants",grants,
                "contextKey",binding.contextKey(),"contextScopeKey",binding.contextScopeKey()));
        return new Owner(result.authRevision(),result.policyRevision(),expiry,vector);
    }
    private Map<String,Object> principal(PlanningBindings binding) {
        var rows=jdbc.query("SELECT u.user_id,u.tenant_id,u.person_public_id,u.status,u.identity_plane,t.status AS tenant_status "
                +"FROM com_users u JOIN com_tenants t ON t.tenant_id=u.tenant_id WHERE u.tenant_id=? AND u.user_id=?",(row,index)->{
            if(!"ACTIVE".equals(row.getString("status")) || !"TENANT".equals(row.getString("identity_plane")) || !"ACTIVE".equals(row.getString("tenant_status"))
                    || !binding.personPublicId().equals(row.getObject("person_public_id",java.util.UUID.class))) throw denied();
            return Map.<String,Object>of("actorId",row.getLong("user_id"),"tenantId",row.getLong("tenant_id"),"personPublicId",binding.personPublicId(),
                    "status",row.getString("status"),"identityPlane",row.getString("identity_plane"),"tenantStatus",row.getString("tenant_status"));
        },binding.tenantId(),binding.actorId()); if(rows.size()!=1) throw denied(); return rows.getFirst();
    }
    private static Instant earliest(Instant left,java.time.OffsetDateTime right) { return right!=null && right.toInstant().isBefore(left)?right.toInstant():left; }
    private record Observation(ProductAuthorizationContractRepository.StoredBundle bundle,ProductAuthorizationContractRepository.ActivePointer pointer,Registry registry) { }
}
