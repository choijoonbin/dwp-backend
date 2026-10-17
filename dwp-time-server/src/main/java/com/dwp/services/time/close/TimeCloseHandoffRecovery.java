package com.dwp.services.time.close;

import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.HandoffState;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffOutcomeV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;

/** Pure forward-only receiver-outcome lineage rules shared by persistence and tests. */
final class TimeCloseHandoffRecovery {

    private TimeCloseHandoffRecovery() {}

    static boolean accepts(
            HandoffRecord handoff, PayrollTimeHandoffOutcomeV1 outcome) {
        if (handoff == null || outcome == null) return false;
        PayrollTimeHandoffStatusV1 current = handoff.projection();
        if (!handoff.correlationId().equals(outcome.correlationId())
                || !current.closedTimeResultId().equals(outcome.closedTimeResultId())
                || current.closeRevision() != outcome.closeRevision()
                || !current.payloadDigest().equals(outcome.payloadDigest())) {
            return false;
        }
        if (current.status() == HandoffState.PENDING) {
            return outcome.receiverReceiptRevision() == 1;
        }
        return current.status() == HandoffState.RESULT_UNKNOWN
                && (outcome.outcome() == HandoffState.ACKNOWLEDGED
                    || outcome.outcome() == HandoffState.REJECTED)
                && current.receiverReceiptId().equals(outcome.receiverReceiptId())
                && outcome.receiverReceiptRevision()
                        == current.receiverReceiptRevision() + 1;
    }
}
