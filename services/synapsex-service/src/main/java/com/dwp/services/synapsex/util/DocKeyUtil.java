package com.dwp.services.synapsex.util;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * docKey 파싱 유틸. 형식: bukrs-belnr-gjahr
 */
public final class DocKeyUtil {

    private DocKeyUtil() {}

    /**
     * docKey 파싱. 형식: bukrs-belnr-gjahr (예: 1000-4900000001-2024)
     */
    public static ParsedDocKey parse(String docKey) {
        if (docKey == null || docKey.isBlank()) return null;
        String[] parts = docKey.trim().split("-", 3);
        if (parts.length < 3) return null;
        return new ParsedDocKey(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    public static String format(String bukrs, String belnr, String gjahr) {
        if (bukrs == null || belnr == null || gjahr == null) return null;
        return bukrs + "-" + belnr + "-" + gjahr;
    }

    @Getter
    @AllArgsConstructor
    public static class ParsedDocKey {
        private final String bukrs;
        private final String belnr;
        private final String gjahr;
    }
}
