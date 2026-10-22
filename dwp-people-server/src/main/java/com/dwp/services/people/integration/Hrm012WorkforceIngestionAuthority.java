package com.dwp.services.people.integration;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

/** Trusted identity, complete snapshot and post-receipt exact-equality boundary. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class Hrm012WorkforceIngestionAuthority {
    private final Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots;
    private final Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort postReceipt;
    private final Clock clock;

    @Autowired
    Hrm012WorkforceIngestionAuthority(
            Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots,
            Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort postReceipt) {
        this(snapshots, postReceipt, Clock.systemUTC());
    }

    Hrm012WorkforceIngestionAuthority(
            Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots,
            Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort postReceipt,
            Clock clock) {
        this.snapshots = snapshots;
        this.postReceipt = postReceipt;
        this.clock = Objects.requireNonNull(clock);
    }

    VerifiedIdentity verifyIdentity(
            PeopleRequestContext.Actor actor,
            Hrm012OwnerReceiptAuthorityVerifier.Evidence marker,
            Hrm012WorkforceIngestionContract.CommandHeaders headers) {
        if (actor == null || actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null || marker == null
                || marker.expiresAt() == null
                || !marker.expiresAt().isAfter(clock.instant())
                || marker.tenantId() != actor.tenantId()
                || !Long.toString(actor.userId()).equals(marker.onBehalfOfSubject())
                || !headers.correlationId().toString().equals(marker.correlationId())
                || !Hrm012CanonicalJson.sha256(headers.idempotencyKey())
                .equals(marker.idempotencyKeySha256())) {
            throw Hrm012Problem.denied(
                    "The numeric user and personPublicId receipt identity is not verified.");
        }
        return new VerifiedIdentity(actor, marker);
    }

    Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot preReceipt(
            VerifiedIdentity identity,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            String requestHash) {
        Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot snapshot;
        try {
            snapshot = snapshots.resolve(new Hrm012WorkforceIngestionPorts.SnapshotRequest(
                    identity.actor(), identity.marker(), command, headers, requestHash));
        } catch (Hrm012Problem problem) {
            throw problem;
        } catch (RuntimeException exception) {
            throw Hrm012Problem.unavailable(
                    "Trusted pre-receipt authority resolution failed.");
        }
        requireSnapshot(identity, headers, requestHash, snapshot, true);
        return snapshot;
    }

    void postReceipt(
            VerifiedIdentity identity,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            Hrm012WorkforceIngestionContract.Receipt receipt,
            String payloadSha256) {
        Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot frozen =
                receipt.frozenAuthority();
        requireSnapshot(identity, headers, receipt.requestHash(), frozen, false);
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityRequest request =
                new Hrm012WorkforceIngestionPorts.PostReceiptAuthorityRequest(
                        frozen.tenantId(), frozen.numericUserId(),
                        frozen.personPublicId(), Hrm012WorkforceIngestionContract.ROUTE,
                        Hrm012WorkforceIngestionContract.ORIGINATING_ACTION,
                        Hrm012WorkforceIngestionContract.METHOD,
                        Hrm012WorkforceIngestionContract.PUBLIC_PATH,
                        Hrm012WorkforceIngestionContract.OWNER_PATH,
                        frozen.contextKey(), frozen.scopeRef(),
                        frozen.populationScopeDigest(), frozen.authorizationRevision(),
                        frozen.populationRevision(), frozen.fieldPolicyRevision(),
                        frozen.postReceiptDecisionRevision(),
                        frozen.dutyEvidenceRevision(), frozen.featureDecisionRevision(),
                        frozen.purpose(), frozen.correlationId(),
                        headers.idempotencyKey(), frozen.idempotencyKeySha256(),
                        frozen.canonicalRequestHash(), receipt.receiptPublicId(),
                        command.objectRef(), headers.ifMatch(), payloadSha256,
                        identity.marker().opaqueStepUpChallengeRef());
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict response;
        try {
            response = postReceipt.verify(request);
        } catch (Hrm012Problem problem) {
            throw problem;
        } catch (RuntimeException exception) {
            throw Hrm012Problem.unavailable(
                    "Post-receipt authority verification is unavailable.");
        }
        if (response == null || !"ALLOWED".equals(response.decision())
                || response.tenantId() != frozen.tenantId()
                || response.numericUserId() != frozen.numericUserId()
                || !frozen.personPublicId().equals(response.personPublicId())
                || !Hrm012WorkforceIngestionContract.ROUTE.equals(
                response.routeContractKey())
                || !Hrm012WorkforceIngestionContract.ORIGINATING_ACTION.equals(
                response.originatingAction())
                || !Hrm012WorkforceIngestionContract.METHOD.equals(
                response.commandMethod())
                || !Hrm012WorkforceIngestionContract.PUBLIC_PATH.equals(
                response.publicCommandPath())
                || !Hrm012WorkforceIngestionContract.OWNER_PATH.equals(
                response.serviceCommandPath())
                || !frozen.contextKey().equals(response.contextKey())
                || !frozen.scopeRef().equals(response.scopeRef())
                || !frozen.populationScopeDigest().equals(
                response.populationScopeDigest())
                || response.authorizationRevision() != frozen.authorizationRevision()
                || response.populationRevision() != frozen.populationRevision()
                || response.fieldPolicyRevision() != frozen.fieldPolicyRevision()
                || !frozen.postReceiptDecisionRevision().equals(
                response.postReceiptDecisionRevision())
                || !frozen.dutyEvidenceRevision().equals(response.dutyEvidenceRevision())
                || !frozen.featureDecisionRevision().equals(
                response.featureDecisionRevision())
                || !frozen.purpose().equals(response.purpose())
                || !frozen.correlationId().equals(response.correlationId())
                || !frozen.idempotencyKeySha256().equals(
                response.idempotencyKeySha256())
                || !frozen.canonicalRequestHash().equals(
                response.canonicalRequestHash())
                || !receipt.receiptPublicId().equals(response.receiptPublicId())
                || !command.objectRef().equals(response.objectRef())
                || !headers.ifMatch().equals(response.ifMatch())
                || !Hrm012WorkforceIngestionContract.CAPABILITY.equals(
                response.capability())
                || !Hrm012WorkforceIngestionContract.DUTY.equals(response.duty())
                || !Hrm012WorkforceIngestionContract.STEP_UP_POLICY.equals(
                response.stepUpPolicy())
                || !Hrm012WorkforceIngestionContract.canonical(
                response.stepUpChallengeId(), 500)
                || response.stepUpExpiresAt() == null
                || !response.stepUpExpiresAt().isAfter(clock.instant())) {
            throw Hrm012Problem.denied(
                    "Post-receipt authority does not exactly equal the frozen snapshot.");
        }
    }

    private void requireSnapshot(
            VerifiedIdentity identity,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            String requestHash,
            Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot snapshot,
            boolean requireOriginMarker) {
        if (snapshot == null || snapshot.tenantId() != identity.actor().tenantId()
                || snapshot.numericUserId() != identity.actor().userId()
                || !identity.actor().personPublicId().equals(snapshot.personPublicId())
                || !headers.correlationId().equals(snapshot.correlationId())
                || !identity.marker().idempotencyKeySha256().equals(
                snapshot.idempotencyKeySha256())
                || !requestHash.equals(snapshot.canonicalRequestHash())
                || !Hrm012WorkforceIngestionContract.PURPOSE.equals(snapshot.purpose())
                || requireOriginMarker && (!identity.marker().jti().equals(snapshot.markerJti())
                || !identity.marker().markerSha256().equals(snapshot.markerSha256())
                || !identity.marker().featureDecisionRevision().equals(
                snapshot.featureDecisionRevision()))) {
            throw Hrm012Problem.denied(
                    "The trusted receipt authority snapshot is incomplete or mismatched.");
        }
    }

    record VerifiedIdentity(
            PeopleRequestContext.Actor actor,
            Hrm012OwnerReceiptAuthorityVerifier.Evidence marker) {
        VerifiedIdentity {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(marker);
        }
    }
}
