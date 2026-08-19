package com.dwp.services.messaging.collaboration;

import java.util.Locale;

final class CollaborationText {

    private static final int MAX_SNIPPET_LENGTH = 220;

    private CollaborationText() {
    }

    static String escapedSnippet(String source, String query) {
        if (source == null || source.isBlank()) return null;
        String plain = source
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (plain.isEmpty()) return null;

        String lower = plain.toLowerCase(Locale.ROOT);
        int match = lower.indexOf(query.toLowerCase(Locale.ROOT));
        int start = match > 70 ? match - 70 : 0;
        String prefix = start > 0 ? "..." : "";
        String candidate = prefix + plain.substring(start);
        return escapeAndCap(candidate, MAX_SNIPPET_LENGTH);
    }

    private static String escapeAndCap(String value, int maxLength) {
        StringBuilder escaped = new StringBuilder(Math.min(value.length(), maxLength));
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            String replacement = switch (codePoint) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> new String(Character.toChars(codePoint));
            };
            if (escaped.length() + replacement.length() > maxLength) {
                appendEllipsis(escaped, maxLength);
                break;
            }
            escaped.append(replacement);
            offset += Character.charCount(codePoint);
        }
        return escaped.toString();
    }

    private static void appendEllipsis(StringBuilder value, int maxLength) {
        while (value.length() > maxLength - 3) {
            value.deleteCharAt(value.length() - 1);
        }
        value.append("...");
    }
}
