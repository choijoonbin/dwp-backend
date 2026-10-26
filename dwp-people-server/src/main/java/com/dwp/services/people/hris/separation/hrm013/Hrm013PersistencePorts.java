package com.dwp.services.people.hris.separation.hrm013;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityKey;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityRecord;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackIdentity;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackReceiptRecord;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.ReplayReservation;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.ReplayDigestReservation;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.TaskDispatchAcceptance;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.TaskDispatchClaim;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.TaskDispatchDisposition;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.TaskDispatchFailure;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.TaskDispatchFence;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryAcceptance;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryClaim;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryClaimRequest;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryDisposition;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryFailure;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverFinalClaimRecovery;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverRequestFence;

/** Persistence-only ports; this package deliberately exposes no transport runtime. */
public final class Hrm013PersistencePorts {
    private Hrm013PersistencePorts() {
    }

    public interface ProofReplayStore {
        boolean reserve(ReplayReservation reservation);
    }

    @FunctionalInterface
    public interface DigestProofReplayStore {
        boolean reserve(ReplayDigestReservation reservation);
    }

    public interface WaiverRequestOutboxStore {
        WaiverRequestFence insert(WaiverRequestFence fence);

        Optional<WaiverRequestFence> find(long tenantId, java.util.UUID approvalRequestId);

        Optional<WaiverDeliveryClaim> claimNext(WaiverDeliveryClaimRequest request);

        WaiverDeliveryDisposition accept(WaiverDeliveryAcceptance acceptance);

        WaiverDeliveryDisposition retryOrTerminal(WaiverDeliveryFailure failure);

        WaiverDeliveryDisposition resolveExpiredFinalClaim(
                WaiverFinalClaimRecovery recovery);
    }

    public interface CallbackAuthorityStore {
        CallbackAuthorityRecord append(CallbackAuthorityRecord authority);

        Optional<CallbackAuthorityRecord> latest(CallbackAuthorityKey key);

        Optional<CallbackAuthorityRecord> latestForUpdate(CallbackAuthorityKey key);
    }

    public interface CallbackReceiptStore {
        CallbackReceiptRecord append(CallbackReceiptRecord receipt);

        Optional<CallbackReceiptRecord> find(CallbackIdentity identity);

        Optional<CallbackReceiptRecord> findForUpdate(CallbackIdentity identity);
    }

    public interface TaskDispatchStore {
        TaskDispatchFence insert(TaskDispatchFence fence, CallbackAuthorityRecord callbackFence);

        Optional<TaskDispatchClaim> claimNext(long tenantId, Instant now);

        TaskDispatchFence requireIntegrity(UUID dispatchEventId, long tenantId);

        default TaskDispatchFence requireStoredIntegrity(
                UUID dispatchEventId, long tenantId) {
            return requireIntegrity(dispatchEventId, tenantId);
        }

        TaskDispatchDisposition accept(TaskDispatchAcceptance acceptance);

        TaskDispatchDisposition fail(TaskDispatchFailure failure);
    }
}
