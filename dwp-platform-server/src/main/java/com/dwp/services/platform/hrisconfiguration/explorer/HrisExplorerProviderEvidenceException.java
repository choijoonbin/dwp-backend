package com.dwp.services.platform.hrisconfiguration.explorer;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

final class HrisExplorerProviderEvidenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final DegradedReason reason;

    HrisExplorerProviderEvidenceException(DegradedReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    DegradedReason reason() {
        return reason;
    }
}
