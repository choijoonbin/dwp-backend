package com.dwp.services.notification.domain;

import com.dwp.services.notification.realtime.NotificationChangeCause;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class NotificationTargetLifecycleService {

    private static final Set<String> STATES = Set.of("DELETED", "FORBIDDEN");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{2,79}");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationProducerOwnershipPolicy ownershipPolicy;
    private final NotificationTargetLifecycleRepository repository;
    private final NotificationChangePublisher changePublisher;

    public NotificationTargetLifecycleService(
            NotificationDatabaseScope databaseScope,
            NotificationProducerOwnershipPolicy ownershipPolicy,
            NotificationTargetLifecycleRepository repository,
            NotificationChangePublisher changePublisher) {
        this.databaseScope = databaseScope;
        this.ownershipPolicy = ownershipPolicy;
        this.repository = repository;
        this.changePublisher = changePublisher;
    }

    @Transactional
    public void apply(
            NotificationRequestContext.Actor actor,
            TargetChange change) {
        databaseScope.applyWorker(actor.tenantId());
        String ownerAppKey = required(change.ownerAppKey(), "ownerAppKey", 80)
                .toLowerCase(Locale.ROOT);
        String targetReference = required(change.targetReference(), "targetReference", 300);
        String state = required(change.state(), "state", 20).toUpperCase(Locale.ROOT);
        String reason = required(change.reason(), "reason", 80).toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) {
            throw new IllegalArgumentException("Notification target state is invalid.");
        }
        if (!REASON_CODE.matcher(reason).matches()) {
            throw new IllegalArgumentException("Notification target reason code is invalid.");
        }
        ownershipPolicy.requireAppOwnership(actor, ownerAppKey);
        changePublisher.publishAfterCommit(
                repository.markUnavailable(
                        actor.tenantId(), ownerAppKey, targetReference, state, reason),
                NotificationChangeCause.TARGET_LIFECYCLE);
    }

    private String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    "Notification target " + field + " is invalid.");
        }
        return value.trim();
    }

    public record TargetChange(
            String ownerAppKey,
            String targetReference,
            String state,
            String reason) {
    }
}
