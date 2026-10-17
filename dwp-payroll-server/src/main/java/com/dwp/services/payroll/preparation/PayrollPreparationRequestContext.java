package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

final class PayrollPreparationRequestContext {

    private static final ThreadLocal<PayrollPreparationAuthority.Subject> CURRENT =
            new ThreadLocal<>();

    private PayrollPreparationRequestContext() {
    }

    static void set(PayrollPreparationAuthority.Subject subject) {
        CURRENT.set(subject);
    }

    static PayrollPreparationAuthority.Subject require() {
        PayrollPreparationAuthority.Subject subject = CURRENT.get();
        if (subject == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "Verified Payroll preparation context is required.");
        }
        return subject;
    }

    static void clear() {
        CURRENT.remove();
    }
}
