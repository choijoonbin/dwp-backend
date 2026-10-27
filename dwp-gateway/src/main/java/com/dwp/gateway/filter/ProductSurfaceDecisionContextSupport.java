package com.dwp.gateway.filter;

import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Canonical PRODUCT authority input and revision encodings. */
final class ProductSurfaceDecisionContextSupport {

    private ProductSurfaceDecisionContextSupport() {
    }

    private static final Pattern SYS006_IDEMPOTENCY_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
    private static final Pattern SYS008_IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{16,128}");
    private static final Pattern SYS008_IF_MATCH = Pattern.compile(
            "(?:W/)?\\\"[A-Za-z0-9._:-]+\\\"");
    private static final long SYS006_MAX_SAFE_OBJECT_REVISION = 9_007_199_254_740_991L;
    private static final Pattern COUNTRY_PACK_ACTIVATION_PATH = Pattern.compile(
            "^/api/payroll/v1/country-packs/"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}/activate$");
    static boolean validOptionalCanonicalPurpose(List<String> values, String expected) {
        return values.isEmpty()
                || (values.size() == 1 && expected.equals(values.getFirst()));
    }

    static boolean validSys006Purpose(List<String> values, boolean stateChanging) {
        return stateChanging
                ? values.equals(List.of(Sys006GatewayRoutes.PURPOSE))
                : validOptionalCanonicalPurpose(values, Sys006GatewayRoutes.PURPOSE);
    }

    static boolean canonicalCountryPackActivationPath(String path) {
        return path != null && COUNTRY_PACK_ACTIVATION_PATH.matcher(path).matches();
    }

    static String strongIfMatchVersion(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || !value.matches("\"(?:0|[1-9][0-9]*)\"")) return null;
        String version = value.substring(1, value.length() - 1);
        try {
            Long.parseLong(version);
            return version;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static String safeSys006IdempotencyKey(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && SYS006_IDEMPOTENCY_KEY.matcher(value).matches()
                ? value : null;
    }

    static String strongSys006IfMatchVersion(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || !value.matches("\"[1-9][0-9]{0,15}\"")) return null;
        String version = value.substring(1, value.length() - 1);
        try {
            long parsed = Long.parseLong(version);
            return parsed <= SYS006_MAX_SAFE_OBJECT_REVISION ? version : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static String safeSys008IdempotencyKey(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && SYS008_IDEMPOTENCY_KEY.matcher(value).matches()
                ? value : null;
    }

    static String canonicalSys008IfMatch(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && SYS008_IF_MATCH.matcher(value).matches() ? value : null;
    }

    static String expectedRevision(List<String> values) {
        if (values.isEmpty()) return null;
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.length() > 200
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) return null;
        return value.trim();
    }

    static List<String> splitScopes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .distinct()
                .toList();
    }

    static Long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String numericRevision(String domain, String sourceRevision) {
        if (blank(sourceRevision)) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain + ":" + sourceRevision).getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                value = (value << 8) | (digest[index] & 0xffL);
            }
            value &= Long.MAX_VALUE;
            return Long.toString(value == 0L ? 1L : value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String exactOpaqueHeader(
            List<String> values, int maximumUtf8Bytes, boolean allowComma) {
        if (values == null || values.size() != 1 || values.getFirst() == null) return null;
        String value = values.getFirst();
        if (value.isBlank() || !value.equals(value.strip())
                || value.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes
                || (!allowComma && value.indexOf(',') >= 0)
                || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) return null;
        return value;
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

}
