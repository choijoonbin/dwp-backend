package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

final class Pay003RequestContext {

    private static final ThreadLocal<Pay003Authority.Subject> CURRENT =
            new ThreadLocal<>();

    private Pay003RequestContext() {
    }

    static void set(Pay003Authority.Subject subject) {
        CURRENT.set(subject);
    }

    static Pay003Authority.Subject require() {
        Pay003Authority.Subject subject = CURRENT.get();
        if (subject == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "Verified PAY-003 context is required.");
        }
        return subject;
    }

    static void clear() {
        CURRENT.remove();
    }
}
