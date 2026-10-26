package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One bounded APR-002 relay step; scheduling is external and default-off. */
public final class Hrm013WaiverRequestRelay {
    private final Hrm013PersistencePorts.WaiverRequestOutboxStore store;
    private final Hrm013ApprovalClient client;
    private final ProofFactory proofs;
    private final CurrentAuthority authority;
    private final RetryJitter jitter;
    private final Clock clock;
    private final Duration lease;

    public Hrm013WaiverRequestRelay(
            Hrm013PersistencePorts.WaiverRequestOutboxStore store,
            Hrm013ApprovalClient client, ProofFactory proofs,
            CurrentAuthority authority, RetryJitter jitter,
            Clock clock, Duration lease) {
        this.store = Objects.requireNonNull(store); this.client = Objects.requireNonNull(client);
        this.proofs = Objects.requireNonNull(proofs); this.authority = Objects.requireNonNull(authority);
        this.jitter = Objects.requireNonNull(jitter); this.clock = Objects.requireNonNull(clock);
        this.lease = Objects.requireNonNull(lease);
    }

    public Optional<Hrm013PersistenceModels.WaiverDeliveryDisposition> relayOne(
            long tenantId, String workerId) {
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        var claim = store.claimNext(new Hrm013PersistenceModels.WaiverDeliveryClaimRequest(
                tenantId, leaseToken, workerId, now.plus(lease)));
        if (claim.isEmpty()) return Optional.empty();
        var delivery = claim.orElseThrow();
        try {
            if (!authority.current(delivery.fence())) {
                throw new Hrm013ApprovalClient.DeliveryFailure(
                        "AUTHORITY_CHANGED", true, false, null);
            }
            List<Hrm013WorkloadProofV1.Header> fresh = proofs.create(delivery, now);
            if (!authority.current(delivery.fence())) {
                throw new Hrm013ApprovalClient.DeliveryFailure(
                        "AUTHORITY_CHANGED", true, false, null);
            }
            Hrm013ApprovalClient.Delivery accepted = client.send(delivery, fresh);
            return Optional.of(store.accept(
                    new Hrm013PersistenceModels.WaiverDeliveryAcceptance(
                            tenantId, delivery.fence().approvalRequestId(), leaseToken,
                            accepted.exactBody(), accepted.responseBodySha256(),
                            accepted.acceptedAt())));
        } catch (Hrm013ApprovalClient.DeliveryFailure failure) {
            boolean retry = failure.retryable() && delivery.attemptCount() < 10;
            Instant retryAt = retry ? now.plus(jitter.delay(delivery.attemptCount())) : null;
            return Optional.of(store.retryOrTerminal(
                    new Hrm013PersistenceModels.WaiverDeliveryFailure(
                            tenantId, delivery.fence().approvalRequestId(), leaseToken,
                            retry, failure.ambiguous(), retryAt, failure.code())));
        } catch (RuntimeException failure) {
            boolean retry = delivery.attemptCount() < 10;
            return Optional.of(store.retryOrTerminal(
                    new Hrm013PersistenceModels.WaiverDeliveryFailure(
                            tenantId, delivery.fence().approvalRequestId(), leaseToken,
                            retry, true, retry ? now.plus(jitter.delay(
                            delivery.attemptCount())) : null, "RESULT_UNKNOWN")));
        }
    }

    @FunctionalInterface public interface ProofFactory {
        List<Hrm013WorkloadProofV1.Header> create(
                Hrm013PersistenceModels.WaiverDeliveryClaim claim, Instant now);
    }
    @FunctionalInterface public interface CurrentAuthority {
        boolean current(Hrm013PersistenceModels.WaiverRequestFence fence);
    }
    @FunctionalInterface public interface RetryJitter {
        Duration delay(int attempt);
    }
}
