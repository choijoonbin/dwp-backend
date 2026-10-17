package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.ApprovalEvidence;
import com.dwp.services.time.close.TimeCloseModels.CloseCommand;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Owner ports whose live producers are deliberately fail-closed until configured. */
interface TimeCloseEvidenceSource {
    CloseEvidence freeze(
            ClosePeriod period, VerifiedCloseRequest authority, Instant asOf);
}
interface TimeCloseApprovalVerifier {
    ApprovalEvidence verify(CloseCommand command, ClosePeriod period);
}

interface TimeCloseStepUpVerifier {
    void verify(CloseCommand command, ClosePeriod period);
}

@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
@ConditionalOnMissingBean(TimeCloseApprovalVerifier.class)
final class UnavailableTimeCloseApprovalVerifier implements TimeCloseApprovalVerifier {
    @Override
    public ApprovalEvidence verify(CloseCommand command, ClosePeriod period) {
        throw new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The PDX-011 approval producer is not configured.");
    }
}
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
@ConditionalOnMissingBean(TimeCloseStepUpVerifier.class)
final class UnavailableTimeCloseStepUpVerifier implements TimeCloseStepUpVerifier {
    @Override
    public void verify(CloseCommand command, ClosePeriod period) {
        throw new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The TIM close step-up verifier is not configured.");
    }
}
