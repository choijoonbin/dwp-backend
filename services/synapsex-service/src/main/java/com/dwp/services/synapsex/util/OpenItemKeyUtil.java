package com.dwp.services.synapsex.util;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * openItemKey 파싱 유틸. 형식: bukrs-belnr-gjahr-buzei
 */
public final class OpenItemKeyUtil {

    private OpenItemKeyUtil() {}

    public static ParsedOpenItemKey parse(String openItemKey) {
        if (openItemKey == null || openItemKey.isBlank()) return null;
        String[] parts = openItemKey.trim().split("-", 4);
        if (parts.length < 4) return null;
        return new ParsedOpenItemKey(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim());
    }

    public static String format(String bukrs, String belnr, String gjahr, String buzei) {
        if (bukrs == null || belnr == null || gjahr == null || buzei == null) return null;
        return bukrs + "-" + belnr + "-" + gjahr + "-" + buzei;
    }

    @Getter
    @AllArgsConstructor
    public static class ParsedOpenItemKey {
        private final String bukrs;
        private final String belnr;
        private final String gjahr;
        private final String buzei;
    }
}
