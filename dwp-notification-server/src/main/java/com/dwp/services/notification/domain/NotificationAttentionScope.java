package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class NotificationAttentionScope {

    static final Set<String> CHANNELS = Set.of(
            "IN_APP", "EMAIL", "WEB_PUSH", "MOBILE_PUSH", "TEAMS", "SLACK");

    private static final Set<String> KINDS = Set.of(
            "APP_TYPE", "ACTOR", "THREAD", "RESOURCE", "TOPIC_TOKEN");
    private static final Pattern OPAQUE_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,299}");
    private static final Pattern APP_TYPE_KEY = Pattern.compile(
            "[a-z0-9][a-z0-9-]{0,63}:[A-Z0-9][A-Z0-9._-]{0,159}");
    private static final Pattern TOPIC_KEY = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,159}");

    private NotificationAttentionScope() {
    }

    static AttentionScope canonical(String kind, String key) {
        if (!KINDS.contains(kind) || key == null || !key.equals(key.trim())) {
            throw new IllegalArgumentException("A canonical attention scope is required.");
        }
        Pattern pattern = switch (kind) {
            case "APP_TYPE" -> APP_TYPE_KEY;
            case "TOPIC_TOKEN" -> TOPIC_KEY;
            default -> OPAQUE_KEY;
        };
        if (!pattern.matcher(key).matches()) {
            throw new IllegalArgumentException("The attention scope key is not canonical.");
        }
        return new AttentionScope(kind, key, sha256(key));
    }

    static Map<String, Boolean> channels(Map<String, Boolean> values) {
        Map<String, Boolean> result = values == null ? Map.of() : Map.copyOf(values);
        if (!CHANNELS.containsAll(result.keySet())
                || result.values().stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("Unsupported attention rule channel.");
        }
        return result;
    }

    static String appTypeKey(String appKey, String typeKey) {
        return appKey + ":" + typeKey;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
