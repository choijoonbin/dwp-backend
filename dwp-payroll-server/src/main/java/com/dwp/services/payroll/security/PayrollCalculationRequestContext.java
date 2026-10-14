package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

public final class PayrollCalculationRequestContext {

    private static final ThreadLocal<PayrollCalculationAuthority.Subject> CURRENT =
            new ThreadLocal<>();

    private PayrollCalculationRequestContext() {
    }

    static void set(PayrollCalculationAuthority.Subject subject) {
        CURRENT.set(subject);
    }

    public static PayrollCalculationAuthority.Subject require() {
        PayrollCalculationAuthority.Subject subject = CURRENT.get();
        if (subject == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "Verified Payroll calculation request context is required.");
        }
        return subject;
    }

    static void clear() {
        CURRENT.remove();
    }
}
