package com.dwp.services.approval.connectors;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class ConnectorRejected extends BaseException {
    private static final long serialVersionUID = 1L;

    private ConnectorRejected(ErrorCode code, String message) {
        super(code, message);
    }

    static ConnectorRejected invalid(String message) {
        return new ConnectorRejected(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static ConnectorRejected conflict(String message) {
        return new ConnectorRejected(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static ConnectorRejected forbidden(String message) {
        return new ConnectorRejected(ErrorCode.FORBIDDEN, message);
    }

    static ConnectorRejected unavailable(String message) {
        return new ConnectorRejected(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
