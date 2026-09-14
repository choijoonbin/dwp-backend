package com.dwp.services.approval.documentretention.management;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class ApprovalRetentionErrors {
    public static final String POLICY_NOT_CONFIGURED="RETENTION_POLICY_NOT_CONFIGURED";
    private ApprovalRetentionErrors() {}
    public static BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN,"Current retention authority prohibits this operation."); }
    public static BaseException hidden() { return new BaseException(ErrorCode.ENTITY_NOT_FOUND,"Retention resource is unavailable."); }
    public static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT,"Retention version, inventory or command changed."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Retention dependency is unavailable."); }
    public static NotConfigured notConfigured() { return new NotConfigured(); }
    public static BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE,"Retention rules exceed the explicit contract bounds."); }

    public static final class NotConfigured extends BaseException {
        private static final long serialVersionUID=1L;
        private NotConfigured() {super(ErrorCode.RESOURCE_CONFLICT,"Retention policy is not configured for the selected resource set.");}
    }
}
