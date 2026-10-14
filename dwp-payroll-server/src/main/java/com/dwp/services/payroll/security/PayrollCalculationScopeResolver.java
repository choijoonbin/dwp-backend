package com.dwp.services.payroll.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.UUID;

public interface PayrollCalculationScopeResolver {

    PayrollCalculationAuthority.Scope resolve(PayrollCalculationAuthority.Subject subject);

    default PayrollCalculationAuthority.Scope resolveReceipt(
            PayrollCalculationAuthority.Subject subject, UUID receiptId) {
        throw new BaseException(ErrorCode.NOT_FOUND,
                "The payroll resource was not found.");
    }
}
