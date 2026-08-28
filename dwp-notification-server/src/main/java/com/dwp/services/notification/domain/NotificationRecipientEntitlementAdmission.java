package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.integration.NotificationRecipientEntitlementDirectory;
import com.dwp.services.notification.integration.NotificationRecipientEntitlementDirectory.Subject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class NotificationRecipientEntitlementAdmission {

    private static final Pattern OWNER_APP = Pattern.compile("[a-z][a-z0-9.-]{0,99}");
    private static final Pattern VIEW_PERMISSION =
            Pattern.compile("APP\\.[A-Z0-9_.-]+:VIEW");
    private static final Map<String, String> REQUIRED_VIEW_PERMISSIONS = Map.of(
            "approvals", "APP.APPROVALS:VIEW",
            "hcm", "APP.HCM:VIEW",
            "messaging", "APP.MESSAGING:VIEW",
            "space", "APP.SPACES:VIEW");

    private final NotificationRecipientEntitlementDirectory directory;
    private final Map<String, String> viewPermissions;

    public NotificationRecipientEntitlementAdmission(
            NotificationRecipientEntitlementDirectory directory,
            @Value("${dwp.notification.recipient-entitlements.app-view-bindings:"
                    + "approvals=APP.APPROVALS:VIEW,hcm=APP.HCM:VIEW,"
                    + "messaging=APP.MESSAGING:VIEW,space=APP.SPACES:VIEW}")
            String bindings) {
        this.directory = directory;
        this.viewPermissions = parseBindings(bindings);
    }

    public Set<Long> admittedRecipients(
            long tenantId,
            List<Long> recipientUserIds,
            String ownerAppKey) {
        String normalizedOwner = ownerAppKey == null
                ? ""
                : ownerAppKey.strip().toLowerCase(Locale.ROOT);
        String requiredPermission = viewPermissions.get(normalizedOwner);
        if (requiredPermission == null) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED,
                    "The notification owner app has no exact VIEW entitlement binding.");
        }

        Set<Long> admitted = new LinkedHashSet<>();
        for (Long userId : new LinkedHashSet<>(recipientUserIds)) {
            Optional<Subject> candidate;
            try {
                candidate = directory.find(tenantId, userId);
            } catch (RuntimeException exception) {
                throw new NotificationException(
                        NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE,
                        "Recipient entitlement validation is unavailable.",
                        exception);
            }
            if (candidate.isEmpty()) continue;
            Subject subject = candidate.get();
            if (!Long.valueOf(tenantId).equals(subject.tenantId())
                    || !userId.equals(subject.userId())) {
                throw new NotificationException(
                        NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE,
                        "Recipient identity binding could not be verified.");
            }
            if ("ACTIVE".equalsIgnoreCase(subject.status())
                    && "TENANT".equalsIgnoreCase(subject.identityPlane())
                    && subject.permissionKeys() != null
                    && subject.permissionKeys().stream().anyMatch(requiredPermission::equals)) {
                admitted.add(userId);
            }
        }
        return Set.copyOf(admitted);
    }

    static Map<String, String> parseBindings(String bindings) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : bindings == null ? new String[0] : bindings.split(",", -1)) {
            String[] parts = entry.strip().split("=", 2);
            String owner = parts.length == 2
                    ? parts[0].strip().toLowerCase(Locale.ROOT)
                    : "";
            String permission = parts.length == 2 ? parts[1].strip() : "";
            if (!OWNER_APP.matcher(owner).matches()
                    || !VIEW_PERMISSION.matcher(permission).matches()
                    || result.putIfAbsent(owner, permission) != null) {
                throw new IllegalArgumentException(
                        "Notification app VIEW entitlement bindings are invalid.");
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one notification app VIEW entitlement binding is required.");
        }
        if (!REQUIRED_VIEW_PERMISSIONS.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(result.get(entry.getKey())))) {
            throw new IllegalArgumentException(
                    "Active notification owners require their exact app VIEW entitlements.");
        }
        return Map.copyOf(result);
    }
}
