package com.dwp.services.synapsex.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

/**
 * Tenant Scope 입력값 검증.
 */
public final class TenantScopeValidator {

    private TenantScopeValidator() {}

    public static void validateBukrs(String bukrs) {
        if (bukrs == null || bukrs.length() != 4 || !bukrs.toUpperCase().matches("[A-Z0-9]{4}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "bukrs는 4자리 대문자 영숫자여야 합니다");
        }
    }

    public static void validateWaers(String waers) {
        if (waers == null || waers.length() < 3 || waers.length() > 5 || !waers.toUpperCase().matches("[A-Z]{3,5}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "waers는 3~5자리 대문자여야 합니다");
        }
    }

    public static void validateRuleKey(String ruleKey) {
        if (ruleKey == null || ruleKey.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "ruleKey는 필수입니다");
        }
        if (!ruleKey.toUpperCase().replace(" ", "_").matches("[A-Z0-9_]+")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "ruleKey는 대문자, 숫자, 언더스코어만 허용됩니다");
        }
    }
}
