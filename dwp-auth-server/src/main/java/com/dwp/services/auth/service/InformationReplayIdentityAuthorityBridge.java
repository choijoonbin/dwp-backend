package com.dwp.services.auth.service;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProofVerifier.earlier;
import static com.dwp.services.auth.informationreplay.InformationReplayProtocol.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.informationreplay.*;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimePublishedDefinition;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimeSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Dedicated receipt-only bridge. Current evidence is independent of historical stamp revisions and Gateway PSR. */
@Component
public final class InformationReplayIdentityAuthorityBridge implements InformationReplayAuthorityPort {
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService surfaces;
    private final ProductAuthorizationContractRepository contracts;
    private final StoredDescriptorSeal seals;
    private final WorkflowRuntimeSourceRepository sources;
    private final WorkflowRuntimeJson sourceJson;
    private final InformationReplayJson json;
    private final JdbcTemplate jdbc;
    public InformationReplayIdentityAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, ProductAuthorizationContractRepository contracts,
            ProductAuthorizationContractValidator validator, JdbcTemplate jdbc, RoleMemberRepository members, ObjectMapper mapper) {
        this.identities = identities; this.surfaces = surfaces; this.contracts = contracts;
        seals = new StoredDescriptorSeal(jdbc, contracts, validator, mapper);
        this.jdbc = jdbc;
        sourceJson = new WorkflowRuntimeJson(mapper); json = new InformationReplayJson(mapper);
        sources = new WorkflowRuntimeSourceRepository(new NamedParameterJdbcTemplate(jdbc), members, sourceJson);
    }

    private Observation registry() {
        try {
            var bundle = contracts.findActive("product-surfaces").orElseThrow(InformationReplayJson::unavailable);
            var pointer = contracts.findActivePointer("product-surfaces").orElseThrow(InformationReplayJson::unavailable);
            var sealed = seals.loadActive(bundle, pointer);
            if (bundle.version() != 9) throw unavailable();
            var registry = new Registry(sealed);
            var route = registry.routesByKey().get(ROUTE);
            if (route == null || !"ACTIVE".equals(route.lifecycleState()) || !"DATA".equals(route.routeKind())
                    || !Boolean.TRUE.equals(route.sideEffectFree()) || route.subject() == null || !"PRODUCT".equals(route.subject().type())
                    || !"approvals".equals(route.subject().productKey()) || !"approvals.work".equals(route.subject().surfaceKey())
                    || !"approvals.work".equals(route.navigationContextId()) || route.uiRouteId() != null || route.uiRoutePattern() != null
                    || route.servicePepBindings().size() != 1 || route.gatewayApiBindings().size() != 1 || route.accessProfiles().size() != 1) throw unavailable();
            var binding = route.servicePepBindings().getFirst(); var gateway = route.gatewayApiBindings().getFirst();
            String path = "/v1/requests/{requestId}/information-commands/{originalKey}/receipt";
            if (!"approval".equals(binding.serviceKey()) || !"POST".equals(binding.method()) || !path.equals(binding.path())
                    || !"POST".equals(gateway.method()) || !("/api/approvals" + path).equals(gateway.path())
                    || !(ROUTE + ".binding.01").equals(binding.bindingKey()) || !binding.bindingKey().equals(gateway.bindingKey())) throw unavailable();
            var profile = route.accessProfiles().getFirst();
            if (!profile.readOnly() || profile.requiredAccess() == null || !"CAPABILITY".equals(profile.requiredAccess().type())
                    || !CAPABILITY.equals(profile.requiredAccess().capabilityContractKey()) || !List.of(PREDICATE).equals(profile.predicatePolicyKeys())
                    || !Set.copyOf(profile.activeAccessModes()).equals(Set.of("NORMAL", "ELEVATED"))) throw unavailable();
            var capability = registry.capabilitiesByKey().get(CAPABILITY); var predicate = registry.predicatesByKey().get(PREDICATE);
            if (capability == null || !"ACTIVE".equals(capability.lifecycleState()) || !"PERMISSION".equals(capability.authorityMode())
                    || !"approvals".equals(capability.productKey()) || !"approvals.work".equals(capability.surfaceKey())
                    || !"ACTION.APPROVAL_REQUEST".equals(capability.resourceKey()) || !"VIEW".equals(capability.action())
                    || !"ACTION.APPROVAL_REQUEST:VIEW".equals(capability.resolvedCapabilityCode()) || !SCOPE_RESOLVER.equals(capability.scopeResolver())
                    || !capability.routeContractKeys().contains(ROUTE) || capability.activationPolicy() != null || capability.sodPolicyId() != null
                    || predicate == null || !"ACTIVE".equals(predicate.lifecycleState()) || !"approval".equals(predicate.ownerServiceKey())
                    || !predicate.routeContractKeys().contains(ROUTE)) throw unavailable();
            return new Observation(bundle, pointer);
        } catch (RuntimeException invalid) { throw unavailable(); }
    }

    @Override public Current requireCurrent(InformationReplayProofVerifier.Verified proof) {
        if (!proof.expiresAt().isAfter(Instant.now())) throw denied();
        var caller = proof.caller(); var bindings = caller.bindings(); var owner = bindings.get("owner"); var source = bindings.get("source");
        String roleCode = WorkflowRuntimePublishedDefinition.role(sourceJson, owner, source);
        var beforeRegistry = registry();
        var identity = identities.load(caller.tenantId(), caller.actorId());
        if (identity.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))
                || !identity.permissions().containsAll(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_FORM:VIEW"))
                || caller.historicalOperation().equals("REPLY") && !identity.hasPermission("ACTION.APPROVAL_REQUEST:UPDATE")) throw denied();
        var result = surfaces.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(caller.tenantId(), caller.actorId(), "approvals", "approvals.work",
                ProductSurfaceAuthorityDtos.AccessMode.valueOf(caller.accessMode()), ROUTE, caller.contextKey(), caller.contextScopeKey(), null, null, List.of()));
        if (result == null || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) throw unavailable();
        var scopes = result.scopes().stream().filter(scope -> caller.contextScopeKey().equals(scope.key())).toList();
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED || !"approvals".equals(result.productKey())
                || !"approvals.work".equals(result.surfaceKey()) || !"work".equals(result.plane())
                || result.accessSource() == ProductSurfaceAuthorityDtos.AccessSource.SUPPORT || !result.effectiveReadOnly()
                || !caller.contextKey().equals(result.contextKey()) || scopes.size() != 1 || !scopes.getFirst().readOnly()
                || !identity.revision().equals(result.authRevision()) || result.policyRevision() == null || result.revalidateAt() == null) throw denied();
        String policy = "policy-9-" + beforeRegistry.pointer().revision() + '-' + beforeRegistry.bundle().checksum();
        if (!policy.equals(result.policyRevision())) throw changed();
        var role = sources.currentRole(caller.tenantId(), roleCode);
        var users = new java.util.TreeSet<>(List.of(caller.actorId(), caller.requesterId(), caller.originalActorId(), caller.principalId()));
        var snapshot = sources.snapshot(caller.tenantId(), users);
        var currentCaller = snapshot.subjects().get(caller.actorId()); var requester = snapshot.subjects().get(caller.requesterId());
        var originalActor = snapshot.subjects().get(caller.originalActorId()); var principal = snapshot.subjects().get(caller.principalId());
        if (!currentCaller.subject().personPublicId().equals(caller.personPublicId()) || !requester.subject().personPublicId().equals(caller.requesterPersonPublicId())
                || !originalActor.subject().personPublicId().equals(caller.originalActorPersonPublicId()) || !principal.subject().personPublicId().equals(caller.principalPersonPublicId())
                || !originalActor.canApprove() || !principal.canApprove() || !principal.roles().contains(role.roleId())) throw denied();
        Instant expiry = earlier(proof.expiresAt(), result.revalidateAt().toInstant());
        expiry = earlier(expiry, result.validUntil() == null ? null : result.validUntil().toInstant());
        expiry = earlier(expiry, scopes.getFirst().validUntil() == null ? null : scopes.getFirst().validUntil().toInstant());
        for (var subject : snapshot.subjects().values()) expiry = earlier(expiry, subject.expiresAt());
        var ownerBounds = com.dwp.services.auth.workflowruntime.WorkflowRuntimeOwnerExpiry.current(jdbc, sourceJson, caller.tenantId(), caller.actorId());
        expiry = earlier(expiry, ownerBounds.expiresAt());
        var delegation = bindings.get("target").get("delegation");
        if (!delegation.isNull()) {
            long from = number(delegation, "startsAt", 1, SAFE_INTEGER), to = number(delegation, "endsAt", 1, SAFE_INTEGER), now = Instant.now().getEpochSecond();
            if (from > now || to <= now) throw denied();
            expiry = earlier(expiry, Instant.ofEpochSecond(to));
        }
        var after = identities.load(caller.tenantId(), caller.actorId()); var afterRegistry = registry();
        if (!identity.equals(after) || !beforeRegistry.equals(afterRegistry) || !role.equals(sources.currentRole(caller.tenantId(), roleCode))
                || !sourceJson.canonical(snapshot.vector()).equals(sourceJson.canonical(sources.snapshot(caller.tenantId(), users).vector()))
                || !sourceJson.canonical(ownerBounds.vector()).equals(sourceJson.canonical(com.dwp.services.auth.workflowruntime.WorkflowRuntimeOwnerExpiry
                    .current(jdbc, sourceJson, caller.tenantId(), caller.actorId()).vector()))) throw changed();
        expiry = Instant.ofEpochSecond(expiry.getEpochSecond()); if (!expiry.isAfter(Instant.now())) throw denied();
        var admission = bindings.get("admission");
        var outcome = json.tree(Map.of("receiptSha256", hash(admission, "receiptSha256"), "commandSha256", hash(admission, "commandSha256"),
                "admissionSha256", hash(admission, "admissionSha256"), "role", role,
                "originalActor", originalActor.minimal(sourceJson, caller.tenantId(), role.roleId(), false),
                "principal", principal.minimal(sourceJson, caller.tenantId(), role.roleId(), true)));
        var vector = new TreeMap<String, Object>(); vector.put("identity", identity); vector.put("source", snapshot.vector()); vector.put("role", role);
        vector.put("registry", beforeRegistry); vector.put("context", result.contextKey()); vector.put("scope", scopes.getFirst()); vector.put("routeGrantRef", result.routeGrantRef());
        vector.put("ownerBounds", ownerBounds.vector());
        return new Current(result.authRevision(), result.policyRevision(), expiry, json.tree(vector), outcome);
    }
    private static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Information replay authority changed."); }
    private record Observation(ProductAuthorizationContractRepository.StoredBundle bundle, ProductAuthorizationContractRepository.ActivePointer pointer) { }
}
