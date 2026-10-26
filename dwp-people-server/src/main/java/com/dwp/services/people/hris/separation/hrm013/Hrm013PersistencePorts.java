package com.dwp.services.people.hris.separation.hrm013;

import java.util.Optional;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityKey;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityRecord;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackIdentity;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackReceiptRecord;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.ReplayReservation;
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
    }

    public interface CallbackReceiptStore {
        CallbackReceiptRecord append(CallbackReceiptRecord receipt);

        Optional<CallbackReceiptRecord> find(CallbackIdentity identity);
    }
}
