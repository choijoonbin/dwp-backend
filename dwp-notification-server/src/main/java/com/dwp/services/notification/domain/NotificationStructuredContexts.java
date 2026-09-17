package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContextKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Canonical, content-free references used for recipient filtering and attention rules. */
public final class NotificationStructuredContexts {

    public static final int MAXIMUM_CONTEXTS = 20;

    private static final Pattern OPAQUE_KEY = Pattern.compile(
            "[a-z][a-z0-9-]{0,63}:[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,298}");
    private static final Pattern PERSON_KEY = Pattern.compile(
            "(?:user:[1-9][0-9]{0,18}|person:(?:[0-9a-f]{64}|"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}))");
    private static final Pattern TOPIC_KEY = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,159}");
    private static final Pattern EMAIL_LIKE = Pattern.compile(
            "(?i).*\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b.*");
    private static final Pattern PHONE_LIKE = Pattern.compile(
            ".*(?:\\+?[0-9][0-9 ()-]{7,}[0-9]).*");

    private NotificationStructuredContexts() {
    }

    public static List<MaterializationContext> explicit(
            List<MaterializationContext> contexts) {
        List<MaterializationContext> source = contexts == null ? List.of() : contexts;
        if (source.size() > MAXIMUM_CONTEXTS) {
            throw new IllegalArgumentException("Notification contexts exceed the limit.");
        }
        Map<String, MaterializationContext> unique = new LinkedHashMap<>();
        for (MaterializationContext context : source) {
            MaterializationContext canonical = canonical(context);
            String identity = identity(canonical);
            if (unique.putIfAbsent(identity, canonical) != null) {
                throw new IllegalArgumentException("Notification contexts must be unique.");
            }
        }
        return sorted(unique.values().stream().toList());
    }

    public static List<MaterializationContext> withLegacy(
            DirectMaterializationRequest request,
            List<MaterializationContext> explicit) {
        Map<String, MaterializationContext> contexts = new LinkedHashMap<>();
        explicit.forEach(context -> contexts.put(identity(context), context));
        addIfRoom(contexts, legacy(
                MaterializationContextKind.PERSON, request.actorReference()));
        addIfRoom(contexts, legacyThread(request.threadKey()));
        addIfRoom(contexts, legacyResource(request.subjectReference()));
        addIfRoom(contexts, legacyResource(request.targetReference()));
        return sorted(contexts.values().stream().toList());
    }

    public static MaterializationContext canonical(MaterializationContext value) {
        if (value == null || value.kind() == null) {
            throw new IllegalArgumentException("A notification context kind is required.");
        }
        String key = value.key();
        if (key == null || key.isBlank() || key.length() > 300
                || !key.equals(key.trim()) || hasControl(key)) {
            throw new IllegalArgumentException("A canonical notification context key is required.");
        }
        Pattern keyPattern = value.kind() == MaterializationContextKind.TOPIC
                ? TOPIC_KEY : OPAQUE_KEY;
        if (!keyPattern.matcher(key).matches()
                || (value.kind() == MaterializationContextKind.PERSON
                    && !PERSON_KEY.matcher(key).matches())) {
            throw new IllegalArgumentException("The notification context key is not canonical.");
        }
        String displayHint = canonicalDisplayHint(value.kind(), value.displayHint());
        MaterializationContext canonical = new MaterializationContext(
                value.kind(), key, displayHint, value.matchable());
        if (canonical.matchable()) {
            NotificationAttentionScope.canonical(scopeKind(canonical.kind()), canonical.key());
        }
        return canonical;
    }

    public static String scopeKind(MaterializationContextKind kind) {
        return switch (kind) {
            case PERSON -> "ACTOR";
            case CONVERSATION, THREAD, CHANNEL -> "THREAD";
            case PROJECT, WORK_ITEM -> "RESOURCE";
            case TOPIC -> "TOPIC_TOKEN";
        };
    }

    public static String keyHash(MaterializationContext context) {
        return NotificationAttentionScope.sha256(context.key());
    }

    public static String attentionScopeHash(String scopeKind, String canonicalKey) {
        return NotificationAttentionScope.canonical(scopeKind, canonicalKey).hash();
    }

    private static MaterializationContext legacy(
            MaterializationContextKind kind,
            String key) {
        if (key == null) return null;
        try {
            return canonical(new MaterializationContext(kind, key, null, true));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static MaterializationContext legacyThread(String key) {
        if (key == null) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        MaterializationContextKind kind;
        if (lower.startsWith("conversation:")
                || lower.startsWith("messaging-conversation:")) {
            kind = MaterializationContextKind.CONVERSATION;
        } else if (lower.startsWith("thread:")
                || lower.startsWith("messaging-thread:")) {
            kind = MaterializationContextKind.THREAD;
        } else if (lower.startsWith("channel:")
                || lower.startsWith("messaging-channel:")) {
            kind = MaterializationContextKind.CHANNEL;
        } else {
            return null;
        }
        return legacy(kind, key);
    }

    private static MaterializationContext legacyResource(String key) {
        if (key == null) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("project:")) {
            return legacy(MaterializationContextKind.PROJECT, key);
        }
        if (lower.startsWith("work-item:")
                || lower.startsWith("approval-request:")
                || lower.startsWith("task:")) {
            return legacy(MaterializationContextKind.WORK_ITEM, key);
        }
        return null;
    }

    private static void addIfRoom(
            Map<String, MaterializationContext> contexts,
            MaterializationContext context) {
        if (context == null || contexts.size() >= MAXIMUM_CONTEXTS) return;
        contexts.putIfAbsent(identity(context), context);
    }

    private static String canonicalDisplayHint(
            MaterializationContextKind kind,
            String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.equals(value.trim()) || value.length() > 160 || hasControl(value)
                || EMAIL_LIKE.matcher(value).matches()
                || PHONE_LIKE.matcher(value).matches()
                || kind == MaterializationContextKind.PERSON) {
            throw new IllegalArgumentException(
                    "Notification context display hints must be content-free and non-personal.");
        }
        return value;
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private static String identity(MaterializationContext context) {
        return context.kind().name() + '\u0000' + context.key();
    }

    private static List<MaterializationContext> sorted(
            List<MaterializationContext> contexts) {
        List<MaterializationContext> result = new ArrayList<>(contexts);
        result.sort(Comparator.comparing((MaterializationContext context) -> context.kind().name())
                .thenComparing(MaterializationContext::key));
        return List.copyOf(result);
    }
}
