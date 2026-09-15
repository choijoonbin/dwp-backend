package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Locale;
import java.util.Set;

public final class HomeModeKeys {
    public static final String CLASSIC = "CLASSIC";
    public static final String FLOW_V1 = "FLOW_V1";
    public static final Set<String> CANONICAL = Set.of(CLASSIC, FLOW_V1);

    private HomeModeKeys() {
    }

    public static String canonical(String value) {
        String normalized = value == null || value.isBlank()
                ? CLASSIC
                : value.trim().toUpperCase(Locale.ROOT);
        if (!CANONICAL.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported Home mode.");
        }
        return normalized;
    }
}
