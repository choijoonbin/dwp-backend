package com.dwp.services.approval.policyautomation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class PolicyAutomationRejected extends BaseException {
    private static final long serialVersionUID = 1L;

    private PolicyAutomationRejected(ErrorCode code, String message) {
        super(code, message);
    }

    static PolicyAutomationRejected invalid(String message) {
        return new PolicyAutomationRejected(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static PolicyAutomationRejected conflict(String message) {
        return new PolicyAutomationRejected(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static PolicyAutomationRejected forbidden(String message) {
        return new PolicyAutomationRejected(ErrorCode.FORBIDDEN, message);
    }

    static PolicyAutomationRejected unavailable(String message) {
        return new PolicyAutomationRejected(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
