package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

/** Internal marker whose public representation is always the shared safe error. */
public final class HrisSeparationProtocolException extends BaseException {
    private static final long serialVersionUID = 1L;
    private final Hrm013SafeErrorV1.Failure failure;

    private HrisSeparationProtocolException(Hrm013SafeErrorV1.Failure failure) {
        super(publicCode(failure.errorCode()),
                failure.getMessage());
        this.failure = failure;
    }

    Hrm013SafeErrorV1.Failure failure() {
        return failure;
    }

    /** Opaque classification used only by the browser/gateway command boundary. */
    public boolean authorityDenied() {
        return failure.errorCode()
                == Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED;
    }

    public static HrisSeparationProtocolException malformed() {
        return new HrisSeparationProtocolException(Hrm013SafeErrorV1.malformed());
    }

    public static HrisSeparationProtocolException authenticationRequired() {
        return new HrisSeparationProtocolException(
                Hrm013SafeErrorV1.authenticationRequired());
    }

    public static HrisSeparationProtocolException denied() {
        return new HrisSeparationProtocolException(Hrm013SafeErrorV1.authorityDenied());
    }

    public static HrisSeparationProtocolException conflict() {
        return new HrisSeparationProtocolException(Hrm013SafeErrorV1.digestConflict());
    }

    public static HrisSeparationProtocolException unavailable() {
        return new HrisSeparationProtocolException(
                Hrm013SafeErrorV1.authorityUnavailable(1));
    }

    private static ErrorCode publicCode(Hrm013SafeErrorV1.ErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST -> ErrorCode.INVALID_INPUT_VALUE;
            case AUTHENTICATION_REQUIRED -> ErrorCode.UNAUTHORIZED;
            case AUTHORITY_DENIED -> ErrorCode.FORBIDDEN;
            case TARGET_NOT_FOUND -> ErrorCode.NOT_FOUND;
            case DIGEST_CONFLICT, REVISION_CONFLICT -> ErrorCode.RESOURCE_CONFLICT;
            case INVALID_STATE -> ErrorCode.INVALID_STATE;
            case RATE_LIMITED -> ErrorCode.SERVICE_RATE_LIMITED;
            case AUTHORITY_UNAVAILABLE -> ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            case INTERNAL_ERROR -> ErrorCode.INTERNAL_SERVER_ERROR;
        };
    }
}
