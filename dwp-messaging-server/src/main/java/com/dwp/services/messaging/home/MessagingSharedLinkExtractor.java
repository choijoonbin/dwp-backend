package com.dwp.services.messaging.home;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class MessagingSharedLinkExtractor {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`]+", Pattern.CASE_INSENSITIVE);
    private MessagingSharedLinkExtractor() { }

    static List<URI> links(String body) {
        if (body == null || body.isBlank()) return List.of();
        var matches = URL.matcher(body);
        var links = new LinkedHashSet<URI>();
        while (matches.find() && links.size() < 20) {
            if (matches.end() - matches.start() > 2048) continue;
            String candidate = trimPunctuation(matches.group());
            try {
                URI uri = URI.create(candidate);
                if (uri.getHost() != null && uri.getRawUserInfo() == null
                        && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                    links.add(uri);
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed text remains in the message, never becomes a navigable shared link.
            }
        }
        return List.copyOf(links);
    }

    private static String trimPunctuation(String value) {
        long opening = value.chars().filter(c -> c == '(').count();
        long closing = value.chars().filter(c -> c == ')').count();
        int end = value.length();
        while (end > 0) {
            char last = value.charAt(end - 1);
            boolean unmatchedParenthesis = last == ')' && closing > opening;
            if (".,;!?:]".indexOf(last) < 0 && !unmatchedParenthesis) break;
            if (last == ')') closing--;
            end--;
        }
        return value.substring(0, end);
    }
}
