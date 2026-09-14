package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Binding;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Operation;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Verified;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** This guard accepts only the sealed source10 SIGN purpose; read/source evaluation never consumes MFA. */
final class ApprovalSignatureHighRiskGuard {
    private final ApprovalSignatureInstalledSource installed;
    private final Supplier<HttpServletRequest> requests;
    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    private final Clock clock;
    ApprovalSignatureHighRiskGuard(ApprovalSignatureInstalledSource installed, Supplier<HttpServletRequest> requests,
            ApprovalStepUpVerifier verifier, ApprovalStepUpReplayRepository replay, Clock clock) {
        this.installed = installed; this.requests = requests; this.verifier = verifier; this.replay = replay; this.clock = clock;
    }
    Reservation verify(Verified proof, Binding binding) {
        transaction();
        if (binding.operation() != Operation.SIGN || proof.binding().operation() != Operation.SIGN
                || !binding.equals(proof.binding()) || binding.expectedVersion() == null || !proof.highRiskVerified()) throw denied();
        var request = requests.get(); var seal = installed.capture(request, binding);
        if (!Objects.equals(proof.actorId(), seal.actor().userId()) || !Objects.equals(proof.tenantId(), seal.actor().tenantId())
                || !Objects.equals(proof.contextKey(), seal.evidence().contextKey()) || !Objects.equals(proof.contextScopeKey(), seal.evidence().contextScopeKey())
                || !Objects.equals(proof.revision(), seal.evidence().revision()) || !Objects.equals(proof.registrySha256(), seal.registrySha256())) throw conflict();
        var command = new ApprovalStepUpVerifier.CommandBinding(proof.actorId(), proof.tenantId(), Operation.SIGN.route(),
                proof.contextKey(), "STEPUP-MGMT-HIGH-V1", "approvals.work.signature.sign", proof.contextScopeKey(),
                "APPROVAL_SIGNATURE_REQUEST", binding.objectId().toString(), binding.expectedVersion(), "POST", "/api/approvals" + Operation.SIGN.path(binding.objectId()),
                binding.idempotencyKey(), binding.bodySha256(), proof.revision());
        var challenge = verifier.verify(ApprovalSignatureInstalledSource.single(request, "X-DWP-Step-Up-Challenge"), command);
        return new Reservation(binding, proof.digest(), seal, challenge);
    }
    void consume(Verified proof, Reservation original) {
        transaction();
        var current = verify(proof, original.binding());
        if (!original.equals(current) || !original.challenge().expiresAt().isAfter(clock.instant())) throw conflict();
        // The unique nonce insert is atomic with evidence/events/command receipt; any late failure rolls it back.
        replay.consume(original.challenge());
    }
    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
    }
    record Reservation(Binding binding, String authorityDigest, ApprovalSignatureInstalledSource.Seal seal,
            ApprovalStepUpVerifier.VerifiedChallenge challenge) { }
}
