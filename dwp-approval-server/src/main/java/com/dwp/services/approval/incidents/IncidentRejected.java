package com.dwp.services.approval.incidents;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class IncidentRejected extends BaseException {
    private static final long serialVersionUID = 1L;

    private IncidentRejected(ErrorCode code, String message) {
        super(code, message);
    }

    static IncidentRejected invalid(String message) {
        return new IncidentRejected(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static IncidentRejected conflict(String message) {
        return new IncidentRejected(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static IncidentRejected forbidden(String message) {
        return new IncidentRejected(ErrorCode.FORBIDDEN, message);
    }

    static IncidentRejected unavailable(String message) {
        return new IncidentRejected(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
