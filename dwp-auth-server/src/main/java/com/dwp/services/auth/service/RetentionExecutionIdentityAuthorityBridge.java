package com.dwp.services.auth.service;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.dwp.services.auth.retentionexecutionauthority.*;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A dedicated managed NORMAL admission; an end-user STEP_UP result is never relabeled. */
@Component
public final class RetentionExecutionIdentityAuthorityBridge implements RetentionExecutionAuthorityPort {
    private final ProductAuthorizationIdentityEvidenceService identities;private final ProductSurfaceAuthorityService surfaces;
    private final ProductAuthorizationContractRepository contracts;private final StoredDescriptorSeal seals;
    private final JdbcTemplate jdbc;private final RetentionExecutionJson json;private final Clock clock=Clock.systemUTC();
    public RetentionExecutionIdentityAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities,ProductSurfaceAuthorityService surfaces,
            ProductAuthorizationContractRepository contracts,ProductAuthorizationContractValidator validator,JdbcTemplate jdbc,ObjectMapper mapper,RetentionExecutionJson json) {
        this.identities=identities;this.surfaces=surfaces;this.contracts=contracts;this.jdbc=jdbc;this.json=json;
        seals=new StoredDescriptorSeal(jdbc,contracts,validator,mapper);
    }
    private Installed installed() {
        try {
            var bundle=contracts.findActive("product-surfaces").orElseThrow(RetentionExecutionProtocol::unavailable);
            var pointer=contracts.findActivePointer("product-surfaces").orElseThrow(RetentionExecutionProtocol::unavailable);
            if(!Set.of(10L, 11L, 12L, 13L, 14L, 15L).contains(bundle.version()) || !"ACTIVE".equals(bundle.bundleStatus()) || !bundle.bundleId().equals(pointer.bundleId())) throw unavailable();
            var registry=new Registry(seals.loadActive(bundle,pointer));var route=registry.routesByKey().get(ROUTE);var cap=registry.capabilitiesByKey().get(CAPABILITY);
            if(route==null || !"ACTION".equals(route.routeKind()) || cap==null || !"ACTIVE".equals(cap.lifecycleState())
                    || !PERMISSION.equals(cap.resolvedCapabilityCode()) || !"EXECUTE".equals(cap.action()) || !"HIGH".equals(cap.riskTier())
                    || !"STEPUP-MGMT-HIGH-V1".equals(cap.activationPolicy()) || !"SOD-APR-OPS-AUDIT-V1".equals(cap.sodPolicyId())
                    || !"APP_CONFIG_ADMIN".equals(cap.requiredResponsibilityCode()) || !"REQUIRED".equals(cap.responsibilityRequirement())
                    || !"PERMISSION".equals(cap.authorityMode()) || !cap.routeContractKeys().contains(ROUTE)) throw unavailable();
            return new Installed(bundle,pointer,registry);
        } catch(RuntimeException invalid) {throw unavailable();}
    }
    @Override public Observation requireCurrent(RetentionExecutionProofVerifier.Verified proof) {
        if(proof==null || !proof.expiresAt().isAfter(clock.instant()) || !"NORMAL".equals(proof.bindings().context().accessMode())) throw denied();
        var target=proof.bindings().target();var beforeRegistry=installed();var beforePrincipal=principal(target);var before=identities.load(target.tenantId(),target.actorId());
        if(before.roles().stream().anyMatch(role->role.startsWith("PROVIDER_")) || !before.hasPermission(PERMISSION)) throw denied();
        var cap=beforeRegistry.registry.capabilitiesByKey().get(CAPABILITY);
        var duties=ScopedAdminDutyPolicy.matchingDuties(before,cap,"APP.APPROVALS").stream()
                .filter(duty->target.resourceSetKey().equals(duty.resourceSetKey()) && "APPROVAL_OPERATIONS_EXECUTE".equals(duty.dutyCode())
                        && duty.tenantId()==target.tenantId() && duty.userId()==target.actorId()).toList();
        if(duties.size()!=1 || ScopedAdminDutyPolicy.staticSodConflict(duties,before.scopedDuties())) throw denied();
        var responsibilities=ScopedAdminDutyPolicy.matchingResponsibilities(before,cap,duties,"APP.APPROVALS").stream()
                .filter(role->target.resourceSetKey().equals(role.resourceSetKey()) && "APP.APPROVALS".equals(role.resourceKey())).toList();
        if(responsibilities.size()!=1) throw denied();
        var request=new ProductSurfaceAuthorityDtos.EvaluateRequest(target.tenantId(),target.actorId(),"approvals","approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,ROUTE,null,null,null,null,List.of());
        var result=surfaces.evaluate(request);
        if(result==null || result.decision()==ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) throw unavailable();
        if(result.decision()!=ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED || result.accessMode()!=ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                || result.accessSource()!=ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT || !"management".equals(result.plane())
                || result.effectiveReadOnly() || !"STEPUP-MGMT-HIGH-V1".equals(result.requestPolicyRef())
                || result.requiredAssurance()==null || result.requiredAssurance().isBlank() || result.contextKey()==null) throw denied();
        var selected=ScopedAdminDutyPolicy.scopes(request,duties,responsibilities,false);
        if(selected.size()!=1) throw denied();String scopeKey=selected.getFirst().key();
        var scopes=result.scopes().stream().filter(scope->scopeKey.equals(scope.key())).toList();
        var grants=result.effectiveGrants().stream().filter(grant->grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant c
                && CAPABILITY.equals(c.capabilityContractKey()) && c.responsibility()!=null
                && target.resourceSetKey().equals(c.responsibility().resourceSetKey())).map(grant->(ProductSurfaceAuthorityDtos.CapabilityGrant)grant).toList();
        if(scopes.size()!=1 || scopes.getFirst().readOnly() || grants.size()!=1) throw denied();var grant=grants.getFirst();
        if(grant.readOnly() || !PERMISSION.equals(grant.resolvedCapabilityCode()) || !"APP_CONFIG_ADMIN".equals(grant.responsibility().code())
                || !grant.scopeKeys().equals(List.of(scopeKey)) || grant.activationState()!=ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE
                || !before.revision().equals(result.authRevision())
                || !("policy-"+beforeRegistry.bundle.version()+'-'+beforeRegistry.pointer.revision()+'-'+beforeRegistry.bundle.checksum()).equals(result.policyRevision())) throw changed();
        Instant expiry=proof.expiresAt();for(var deadline:Arrays.asList(result.validUntil(),result.revalidateAt(),scopes.getFirst().validUntil(),grant.validUntil(),
                duties.getFirst().validTo(),responsibilities.getFirst().validTo())) if(deadline!=null && deadline.toInstant().isBefore(expiry)) expiry=deadline.toInstant();
        var after=identities.load(target.tenantId(),target.actorId());var afterPrincipal=principal(target);var afterRegistry=installed();
        if(!before.equals(after) || !beforePrincipal.equals(afterPrincipal) || !beforeRegistry.bundle.equals(afterRegistry.bundle)
                || !beforeRegistry.pointer.equals(afterRegistry.pointer)) throw changed();
        if(!expiry.isAfter(clock.instant())) throw denied();
        var vector=json.tree(Map.of("principal",beforePrincipal,"identity",before,"bundle",beforeRegistry.bundle,"pointer",beforeRegistry.pointer,
                "contextKey",result.contextKey(),"selectedScope",scopes.getFirst(),"grant",grant,"duties",duties,"responsibilities",responsibilities));
        return new Observation(before.revision(),result.policyRevision(),vector,expiry);
    }
    private Map<String,Object> principal(RetentionExecutionBindings.Target target) {
        var rows=jdbc.query("SELECT u.person_public_id,u.status,u.identity_plane,t.status AS tenant_status FROM com_users u JOIN com_tenants t ON t.tenant_id=u.tenant_id "
                +"WHERE u.tenant_id=? AND u.user_id=?",(r,n)->{
            var person=r.getObject("person_public_id",UUID.class);
            if(person==null || !"ACTIVE".equals(r.getString("status")) || !"TENANT".equals(r.getString("identity_plane"))
                    || !"ACTIVE".equals(r.getString("tenant_status"))) throw denied();
            return Map.<String,Object>of("tenantId",target.tenantId(),"actorId",target.actorId(),"personPublicId",person,
                    "status",r.getString("status"),"identityPlane",r.getString("identity_plane"),"tenantStatus",r.getString("tenant_status"));
        },target.tenantId(),target.actorId());if(rows.size()!=1) throw denied();return rows.getFirst();
    }
    private record Installed(ProductAuthorizationContractRepository.StoredBundle bundle,ProductAuthorizationContractRepository.ActivePointer pointer,Registry registry) { }
}
