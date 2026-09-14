package com.dwp.services.auth.service;

import com.dwp.services.auth.repository.RoleRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** ROLE_BINDINGS resolves only role IDs. Candidate enumeration is a separate sealed protocol. */
@Service
public final class ApprovalWorkflowRoleBindingService {
    private final ApprovalWorkflowRoleProofVerifier proofs;
    private final ApprovalWorkflowRoleCurrentAuthority authority;
    private final ApprovalWorkflowRoleReplayStore replay;
    private final RoleRepository roles;
    private final ApprovalWorkflowRoleAttestationIssuer issuer;
    private final Clock clock;

    @Autowired
    public ApprovalWorkflowRoleBindingService(ApprovalWorkflowRoleProofVerifier proofs, ApprovalWorkflowRoleCurrentAuthority authority,
            ApprovalWorkflowRoleReplayStore replay, RoleRepository roles, ApprovalWorkflowRoleAttestationIssuer issuer) {
        this(proofs, authority, replay, roles, issuer, Clock.systemUTC());
    }

    ApprovalWorkflowRoleBindingService(ApprovalWorkflowRoleProofVerifier proofs, ApprovalWorkflowRoleCurrentAuthority authority,
            ApprovalWorkflowRoleReplayStore replay, RoleRepository roles, ApprovalWorkflowRoleAttestationIssuer issuer, Clock clock) {
        this.proofs = proofs; this.authority = authority; this.replay = replay; this.roles = roles; this.issuer = issuer; this.clock = clock;
    }

    public ApprovalWorkflowRoleAttestationIssuer.Attestation bind(String transportToken, String rawBody) {
        var proof = proofs.verify(transportToken, rawBody);
        var before = authority.require(proof);
        replay.consume(proof);
        var mapping = mapping(proof.binding());
        var after = authority.require(proof);
        if (!before.authRevision().equals(after.authRevision()) || !before.policyRevision().equals(after.policyRevision())) {
            throw ApprovalWorkflowRoleCurrentAuthority.changed();
        }
        if (!mapping.equals(mapping(proof.binding()))) throw ApprovalWorkflowRoleCurrentAuthority.changed();
        if (!clock.instant().isBefore(proof.expiresAt()) || !clock.instant().isBefore(after.expiresAt())) {
            throw ApprovalWorkflowRoleBinding.denied();
        }
        return issuer.sign(proof, mapping, after);
    }

    private List<ApprovalWorkflowRoleMapping> mapping(ApprovalWorkflowRoleBinding binding) {
        var current = roles.findByTenantIdAndCodeIn(binding.tenantId(), binding.roleCodes());
        if (current == null || current.size() != binding.roleCodes().size()
                || current.stream().anyMatch(role -> role == null || !Long.valueOf(binding.tenantId()).equals(role.getTenantId())
                        || !"ACTIVE".equals(role.getStatus()) || role.getRoleId() == null || role.getVersion() == null)) {
            throw ApprovalWorkflowRoleBinding.denied();
        }
        var mapping = current.stream().map(role -> new ApprovalWorkflowRoleMapping(role.getCode(), role.getRoleId(), role.getVersion()))
                .sorted(java.util.Comparator.comparing(ApprovalWorkflowRoleMapping::roleCode)).toList();
        if (!mapping.stream().map(ApprovalWorkflowRoleMapping::roleCode).toList().equals(binding.roleCodes())
                || mapping.stream().map(ApprovalWorkflowRoleMapping::roleId).distinct().count() != mapping.size()) {
            throw ApprovalWorkflowRoleBinding.denied();
        }
        return mapping;
    }
}
