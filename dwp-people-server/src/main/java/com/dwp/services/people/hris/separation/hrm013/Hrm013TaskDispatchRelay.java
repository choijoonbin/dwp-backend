package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded task dispatch attempt with independent dispatch/callback grant fences. */
public final class Hrm013TaskDispatchRelay {
    private final Hrm013PersistencePorts.TaskDispatchStore store;
    private final Hrm013TaskDispatchClient client;
    private final ProofFactory proofs;
    private final CurrentAuthorities authorities;
    private final Hrm013WaiverRequestRelay.RetryJitter jitter;
    private final Clock clock;

    public Hrm013TaskDispatchRelay(
            Hrm013PersistencePorts.TaskDispatchStore store,
            Hrm013TaskDispatchClient client, ProofFactory proofs,
            CurrentAuthorities authorities,
            Hrm013WaiverRequestRelay.RetryJitter jitter, Clock clock) {
        this.store = Objects.requireNonNull(store); this.client = Objects.requireNonNull(client);
        this.proofs = Objects.requireNonNull(proofs); this.authorities = Objects.requireNonNull(authorities);
        this.jitter = Objects.requireNonNull(jitter); this.clock = Objects.requireNonNull(clock);
    }

    public Optional<Hrm013PersistenceModels.TaskDispatchDisposition> relayOne(long tenantId) {
        Instant now = clock.instant();
        Optional<Hrm013PersistenceModels.TaskDispatchClaim> candidate =
                store.claimNext(tenantId, now);
        if (candidate.isEmpty()) return Optional.empty();
        var claim = candidate.orElseThrow();
        var fence = store.requireIntegrity(
                claim.fence().dispatchEventId(), claim.fence().tenantId());
        try {
            if (!authorities.dispatchCurrent(fence)
                    || !authorities.callbackCurrent(fence)) {
                throw new Hrm013TaskDispatchClient.DeliveryFailure(
                        "AUTHORITY_CHANGED", true, false, null);
            }
            List<Hrm013WorkloadProofV1.Header> fresh = proofs.create(fence, now);
            Hrm013PersistenceModels.TaskDispatchFence sendFence =
                    store.requireIntegrity(fence.dispatchEventId(), fence.tenantId());
            if (!authorities.dispatchCurrent(sendFence)
                    || !authorities.callbackCurrent(sendFence)) {
                throw new Hrm013TaskDispatchClient.DeliveryFailure(
                        "AUTHORITY_CHANGED", true, false, null);
            }
            var delivery = client.send(sendFence, fresh);
            return Optional.of(store.accept(
                    new Hrm013PersistenceModels.TaskDispatchAcceptance(
                            tenantId, sendFence.dispatchEventId(),
                            claim.attemptCount(), claim.claimUntil(), delivery.exactBody(),
                            delivery.responseBodySha256(), delivery.receiptSha256(),
                            delivery.admittedAt())));
        } catch (Hrm013TaskDispatchClient.DeliveryFailure failure) {
            return Optional.of(fail(claim, failure.code(), failure.retryable(),
                    failure.ambiguous(), now));
        } catch (RuntimeException failure) {
            return Optional.of(fail(claim, "RESULT_UNKNOWN", true, true, now));
        }
    }

    private Hrm013PersistenceModels.TaskDispatchDisposition fail(
            Hrm013PersistenceModels.TaskDispatchClaim claim, String code,
            boolean retryable, boolean ambiguous, Instant now) {
        boolean retry = retryable && claim.attemptCount() < 10;
        return store.fail(new Hrm013PersistenceModels.TaskDispatchFailure(
                claim.fence().tenantId(), claim.fence().dispatchEventId(),
                claim.attemptCount(), claim.claimUntil(), retry,
                ambiguous, retry ? now.plus(jitter.delay(claim.attemptCount())) : null,
                code));
    }

    @FunctionalInterface public interface ProofFactory {
        List<Hrm013WorkloadProofV1.Header> create(
                Hrm013PersistenceModels.TaskDispatchFence fence, Instant now);
    }
    public interface CurrentAuthorities {
        boolean dispatchCurrent(Hrm013PersistenceModels.TaskDispatchFence fence);
        boolean callbackCurrent(Hrm013PersistenceModels.TaskDispatchFence fence);
    }
}
