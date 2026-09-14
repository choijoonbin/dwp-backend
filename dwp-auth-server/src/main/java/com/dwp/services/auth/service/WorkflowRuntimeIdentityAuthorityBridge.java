package com.dwp.services.auth.service;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofVerifier.minimum;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimeIdentityPort;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only the NEW runtime protocol reaches this bridge; existing identity evidence stays package-private. */
@Component
public final class WorkflowRuntimeIdentityAuthorityBridge implements WorkflowRuntimeIdentityPort {
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService surfaces;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public WorkflowRuntimeIdentityAuthorityBridge(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.identities = identities; this.surfaces = surfaces; this.jdbc = jdbc; this.mapper = mapper;
    }
    @Override public OwnerEvidence requireOwner(WorkflowRuntimeProofVerifier.Verified proof) {
        var caller = proof.caller();
        if (!Instant.now().isBefore(proof.expiresAt())) throw denied();
        var subjects = jdbc.query("""
                SELECT subject.person_public_id,subject.status,subject.identity_plane,tenant.status AS tenant_status
                  FROM com_users subject JOIN com_tenants tenant ON tenant.tenant_id=subject.tenant_id
                 WHERE subject.tenant_id=? AND subject.user_id=?
                """, (row, number) -> new TreeMap<>(Map.of("personPublicId", row.getObject("person_public_id", java.util.UUID.class) == null ? "" : row.getObject("person_public_id").toString(),
                        "status", row.getString("status"), "identityPlane", row.getString("identity_plane"), "tenantStatus", row.getString("tenant_status"))),
                caller.tenantId(), caller.actorId());
        if (subjects.size() != 1 || !subjects.getFirst().get("personPublicId").equals(caller.personPublicId().toString())
                || !subjects.getFirst().get("status").equals("ACTIVE") || !subjects.getFirst().get("tenantStatus").equals("ACTIVE")
                || !subjects.getFirst().get("identityPlane").equals("TENANT")) throw denied();
        var identity = identities.load(caller.tenantId(), caller.actorId());
        String permission = caller.routeContractKey().equals("route.approvals.work.task-decision.action")
                ? "ACTION.APPROVAL_TASK:APPROVE" : "ACTION.APPROVAL_REQUEST:UPDATE";
        if (!identity.hasPermission("APP.APPROVALS:VIEW") || !identity.hasPermission(permission)
                || permission.endsWith("APPROVE") && !identity.hasPermission("ACTION.APPROVAL_TASK:VIEW")
                || proof.operation() != Operation.INFORMATION_ADMISSION && !identity.hasPermission("ACTION.APPROVAL_FORM:VIEW")
                || identity.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw denied();
        var mode = ProductSurfaceAuthorityDtos.AccessMode.valueOf(caller.accessMode());
        var result = surfaces.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(caller.tenantId(), caller.actorId(),
                "approvals", "approvals.work", mode, caller.routeContractKey(), caller.contextKey(), caller.contextScopeKey(), null, null, List.of()));
        if (result == null || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) throw unavailable();
        var scopes = result.scopes().stream().filter(scope -> scope.key().equals(caller.contextScopeKey())).toList();
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED || !"approvals".equals(result.productKey())
                || !"approvals.work".equals(result.surfaceKey()) || !"work".equals(result.plane()) || result.accessMode() != mode
                || result.accessSource() == ProductSurfaceAuthorityDtos.AccessSource.SUPPORT || result.effectiveReadOnly()
                || !caller.contextKey().equals(result.contextKey()) || scopes.size() != 1 || scopes.getFirst().readOnly()) throw denied();
        if (!identity.revision().equals(result.authRevision()) || result.policyRevision() == null || result.policyRevision().isBlank()
                || result.revalidateAt() == null) throw changed();
        Instant expiry = minimum(proof.expiresAt(), result.revalidateAt().toInstant());
        expiry = minimum(expiry, result.validUntil() == null ? null : result.validUntil().toInstant());
        expiry = minimum(expiry, scopes.getFirst().validUntil() == null ? null : scopes.getFirst().validUntil().toInstant());
        var sourceExpiry = com.dwp.services.auth.workflowruntime.WorkflowRuntimeOwnerExpiry.current(jdbc,
                new com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson(mapper), caller.tenantId(), caller.actorId());
        expiry = minimum(expiry, sourceExpiry.expiresAt());
        if (!Instant.now().isBefore(expiry)) throw changed();
        var stable = new TreeMap<String, Object>();
        stable.put("subject", subjects.getFirst()); stable.put("permissions", identity.permissions().stream().sorted().toList());
        stable.put("roles", identity.roles().stream().sorted().toList()); stable.put("responsibilities", identity.responsibilities());
        stable.put("duties", identity.scopedDuties()); stable.put("authRevision", result.authRevision()); stable.put("policyRevision", result.policyRevision());
        stable.put("contextKey", result.contextKey()); stable.put("selectedScope", scopes.getFirst());
        stable.put("accessMode", result.accessMode()); stable.put("accessSource", result.accessSource()); stable.put("readOnly", result.effectiveReadOnly());
        stable.put("routeGrantRef", result.routeGrantRef());
        stable.put("sourceTimeBounds", sourceExpiry.vector());
        return new OwnerEvidence(result.authRevision(), result.policyRevision(), expiry, mapper.valueToTree(stable));
    }
}
