package com.dwp.services.approval.routingdirectory;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class RoutingDirectoryRejected extends BaseException {
    private static final long serialVersionUID = 1L;

    private RoutingDirectoryRejected(ErrorCode code, String message) {
        super(code, message);
    }

    static RoutingDirectoryRejected invalid(String message) {
        return new RoutingDirectoryRejected(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static RoutingDirectoryRejected conflict(String message) {
        return new RoutingDirectoryRejected(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static RoutingDirectoryRejected forbidden(String message) {
        return new RoutingDirectoryRejected(ErrorCode.FORBIDDEN, message);
    }

    static RoutingDirectoryRejected unavailable(String message) {
        return new RoutingDirectoryRejected(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
