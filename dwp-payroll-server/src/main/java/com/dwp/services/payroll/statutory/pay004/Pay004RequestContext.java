package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

final class Pay004RequestContext {
    private static final ThreadLocal<Pay004Authority.Subject> CURRENT =
            new ThreadLocal<>();

    private Pay004RequestContext() {
    }

    static void set(Pay004Authority.Subject subject) { CURRENT.set(subject); }

    static Pay004Authority.Subject require() {
        Pay004Authority.Subject subject = CURRENT.get();
        if (subject == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "Verified PAY-004 context is required.");
        }
        return subject;
    }

    static void clear() { CURRENT.remove(); }
}
