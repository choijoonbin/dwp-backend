package com.dwp.services.approval.signatureproviders;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

final class SignatureProviderErrors {
    private SignatureProviderErrors() { }

    static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN,
                "Current exact signature authority prohibits this operation.");
    }

    static BaseException hidden() {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                "The signature resource is unavailable.");
    }

    static BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The signature source, version, provider, or command changed.");
    }

    static BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The native signature dependency is not configured or current.");
    }

    static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "The signature command exceeds the closed contract bounds.");
    }
}
