package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.TemplateContract;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class NotificationProducerOwnershipPolicy {

    private final Map<String, Set<String>> ownedApps;

    public NotificationProducerOwnershipPolicy(
            @Value("${dwp.notification.producer-app-bindings:}") String bindings) {
        this.ownedApps = parse(bindings);
        if (ownedApps.isEmpty()) {
            throw new IllegalStateException(
                    "At least one notification producer app ownership binding is required.");
        }
    }

    public void requireOwnership(
            NotificationRequestContext.Actor actor,
            TemplateContract contract) {
        requireAppOwnership(actor, contract.ownerAppKey());
    }

    public void requireAppOwnership(
            NotificationRequestContext.Actor actor,
            String ownerAppKey) {
        Set<String> allowedApps = ownedApps.get(actor.sourceService());
        String ownerApp = normalize(ownerAppKey);
        if (!actor.internal()
                || allowedApps == null
                || ownerApp == null
                || !allowedApps.contains(ownerApp)) {
            throw new NotificationException(
                    NotificationErrorCode.FORBIDDEN,
                    "The notification producer does not own the requested app contract.");
        }
    }

    static Map<String, Set<String>> parse(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Producer app bindings must use source-service=app|app entries.");
            }
            String sourceService = parts[0].trim();
            Set<String> apps = Arrays.stream(parts[1].split("\\|"))
                    .map(NotificationProducerOwnershipPolicy::normalize)
                    .filter(item -> item != null)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (apps.isEmpty() || parsed.put(sourceService, Set.copyOf(apps)) != null) {
                throw new IllegalArgumentException(
                        "Notification producer app ownership must be unique and non-empty.");
            }
        }
        return Map.copyOf(parsed);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
