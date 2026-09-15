package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HomeDeviceClasses {
    public static final String DESKTOP_WIDE = "DESKTOP_WIDE";
    public static final String DESKTOP_STANDARD = "DESKTOP_STANDARD";
    public static final String MOBILE_STANDARD = "MOBILE_STANDARD";
    public static final String MOBILE_COMPACT = "MOBILE_COMPACT";
    public static final Set<String> CANONICAL = Set.of(
            DESKTOP_WIDE, DESKTOP_STANDARD, MOBILE_STANDARD, MOBILE_COMPACT);

    private static final Map<String, String> ALIASES = Map.of(
            "DESKTOP", DESKTOP_STANDARD,
            "MOBILE", MOBILE_STANDARD);

    private HomeDeviceClasses() {
    }

    public static String canonical(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        String canonical = ALIASES.getOrDefault(normalized, normalized);
        if (!CANONICAL.contains(canonical)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported device class.");
        }
        return canonical;
    }
}
