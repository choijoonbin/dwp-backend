package com.dwp.services.platform.workplace.workplaceservices;

import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.ProviderRequest;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.LineAdjustmentCommandResult;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

record PreparedInvocation(
        long tenantId,
        long actorUserId,
        boolean requesterProjection,
        boolean reconciliation,
        boolean recoveryLookup,
        boolean commandWasExisting,
        LineCancellationPreviewRow preview,
        LineRow line,
        LineAdjustmentRow adjustment,
        CommandRow activeCommand,
        CommandRow originCommand,
        UUID originCommandId,
        ProviderRequest providerRequest,
        LineAdjustmentCommandResult immediateResult) {

    PreparedInvocation {
        if (immediateResult == null) {
            Objects.requireNonNull(preview);
            Objects.requireNonNull(line);
            Objects.requireNonNull(adjustment);
            Objects.requireNonNull(activeCommand);
            Objects.requireNonNull(originCommand);
            Objects.requireNonNull(originCommandId);
            Objects.requireNonNull(providerRequest);
        }
    }

    static PreparedInvocation immediate(LineAdjustmentCommandResult result) {
        return new PreparedInvocation(0, 0, false, false, false, true,
                null, null, null, null, null, null, null,
                Objects.requireNonNull(result));
    }
}
