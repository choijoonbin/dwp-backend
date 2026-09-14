package com.dwp.services.approval.systemslaauthority;

import com.dwp.services.approval.domain.ApprovalSystemSlaNativeSource;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumSlaRuntime;
import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** Real DB workload + real signed Auth response, rechecked in the same retention-fenced transaction. */
public final class SystemSlaCurrentSource {
    private final ApprovalSystemSlaNativeSource nativeSource;
    private final Supplier<SystemSlaSourceProofIssuer> issuer;
    private final Supplier<AuthApprovalSystemSlaAuthorityClient> client;
    private final Supplier<SystemSlaNotificationVerifier> notificationVerifier;
    private final Supplier<SystemSlaNotificationAttestationIssuer> notificationIssuer;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;
    public SystemSlaCurrentSource(ApprovalSystemSlaNativeSource nativeSource, Supplier<SystemSlaSourceProofIssuer> issuer,
            Supplier<AuthApprovalSystemSlaAuthorityClient> client, Supplier<SystemSlaNotificationVerifier> verifier,
            Supplier<SystemSlaNotificationAttestationIssuer> notificationIssuer, TransactionTemplate transactions, Clock clock, boolean enabled) {
        this.nativeSource = nativeSource; this.issuer = issuer; this.client = client; notificationVerifier = verifier;
        this.notificationIssuer = notificationIssuer; this.transactions = transactions; this.clock = clock; this.enabled = enabled;
    }
    public SystemSlaNotificationVerifier.Verified preverify(byte[] body, String transport) {
        requireEnabled(); return notificationVerifier.get().verify(body, transport);
    }
    public SystemSlaSourceAttestationVerifier.Verified produce(ApprovalWorkflowQuorumSlaRuntime.Lease lease) {
        requireEnabled(); return current(nativeSource.produce(lease));
    }
    public Map<String, String> recipients(SystemSlaNotificationVerifier.Verified proof) {
        requireEnabled(); if (proof == null || !proof.expiresAt().isAfter(clock.instant())) throw SystemSlaJson.denied();
        return transactions.execute(status -> {
            var seal = nativeSource.deliver(proof); var authority = current(seal);
            var response = notificationIssuer.get().issue(proof, authority);
            nativeSource.unchanged(seal); requireCurrent(authority); return response;
        });
    }
    private SystemSlaSourceAttestationVerifier.Verified current(ApprovalSystemSlaNativeSource.Seal seal) {
        var response = client.get().evaluate(issuer.get().issue(seal));
        if (response.exchange().seal() != seal) throw SystemSlaJson.denied();
        nativeSource.unchanged(seal); requireCurrent(response); return response;
    }
    private void requireCurrent(SystemSlaSourceAttestationVerifier.Verified response) {
        if (!response.expiresAt().isAfter(clock.instant()) || !response.exchange().seal().validUntil().isAfter(clock.instant())) throw SystemSlaJson.changed();
    }
    private void requireEnabled() { if (!enabled) throw SystemSlaJson.unavailable(); }
}
