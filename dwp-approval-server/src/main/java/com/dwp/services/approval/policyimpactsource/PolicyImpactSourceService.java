package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;

import com.dwp.services.approval.policyimpact.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class PolicyImpactSourceService {
    private final PolicyImpactInstalledContext installed;
    private final PolicyImpactSourceHeadReader heads;
    private final Supplier<PolicyImpactSourceProofIssuer> issuer;
    private final Supplier<AuthApprovalPolicyImpactAuthorityClient> client;
    private final ApprovalPolicyImpactRepository repository;
    private final ApprovalPolicyImpactRuntimePort runtime;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;
    public PolicyImpactSourceService(PolicyImpactInstalledContext installed, PolicyImpactSourceHeadReader heads,
            Supplier<PolicyImpactSourceProofIssuer> issuer, Supplier<AuthApprovalPolicyImpactAuthorityClient> client,
            ApprovalPolicyImpactRepository repository, ApprovalPolicyImpactRuntimePort runtime, TransactionTemplate transactions, Clock clock, boolean enabled) {
        this.installed = installed; this.heads = heads; this.issuer = issuer; this.client = client;
        this.repository = repository; this.runtime = runtime; this.transactions = transactions; this.clock = clock; this.enabled = enabled;
    }
    public ApprovalPolicyImpactDtos.Result preview(HttpServletRequest request, UUID policyId, long expectedVersion) {
        if (!enabled) throw unavailable();
        // Resolve all required keys/configuration before any source DB read.
        final PolicyImpactSourceProofIssuer signer; final AuthApprovalPolicyImpactAuthorityClient transport;
        try { signer = issuer.get(); transport = client.get(); } catch (RuntimeException invalid) { throw unavailable(); }
        var owner = installed.capture(request, policyId, expectedVersion);
        var head = heads.capture(owner);
        var session = new Session(request, owner, head, signer, transport);
        var authority = new ApprovalPolicyImpactAuthority(session::requireCurrent, clock);
        return new ApprovalPolicyImpactFacade(repository, authority, runtime, transactions).preview(policyId, expectedVersion);
    }
    private final class Session {
        private final HttpServletRequest request;
        private final PolicyImpactInstalledContext.Seal owner;
        private final PolicyImpactSourceHeadReader.Seal head;
        private final PolicyImpactSourceProofIssuer signer;
        private final AuthApprovalPolicyImpactAuthorityClient transport;
        private Instant deadline;
        private PolicyImpactSourceAttestationVerifier.Verified first;
        private Session(HttpServletRequest request, PolicyImpactInstalledContext.Seal owner, PolicyImpactSourceHeadReader.Seal head,
                PolicyImpactSourceProofIssuer signer, AuthApprovalPolicyImpactAuthorityClient transport) {
            this.request = request; this.owner = owner; this.head = head; this.signer = signer; this.transport = transport;
            Instant max = clock.instant().plusSeconds(30); deadline = owner.validUntil().isBefore(max) ? owner.validUntil() : max;
            deadline = Instant.ofEpochSecond(deadline.getEpochSecond());
        }
        private ApprovalPolicyImpactAuthority.Window requireCurrent() {
            installed.unchanged(owner, request); heads.unchanged(head);
            var verified = transport.evaluate(signer.issue(head, deadline));
            installed.unchanged(owner, request); heads.unchanged(head);
            if (first == null) { first = verified; deadline = verified.window().validUntil(); }
            else if (!first.sameSource(verified)) throw changed();
            return verified.window();
        }
    }
}
