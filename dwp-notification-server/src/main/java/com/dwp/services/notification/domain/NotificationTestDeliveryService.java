package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDelivery;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryRequest;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryStage;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationTestDeliveryService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationTestDeliveryRepository repository;
    private final int minuteLimit;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public NotificationTestDeliveryService(
            NotificationDatabaseScope databaseScope,
            NotificationTestDeliveryRepository repository,
            @Value("${dwp.notification.test-delivery.minute-limit:1}") int minuteLimit,
            @Value("${dwp.notification.test-delivery.daily-limit:10}") int dailyLimit) {
        this(databaseScope, repository, minuteLimit, dailyLimit, Clock.systemUTC());
    }

    NotificationTestDeliveryService(
            NotificationDatabaseScope databaseScope,
            NotificationTestDeliveryRepository repository,
            int minuteLimit,
            int dailyLimit,
            Clock clock) {
        if (minuteLimit < 1 || dailyLimit < minuteLimit) {
            throw new IllegalArgumentException("Test delivery rate limits are invalid.");
        }
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.minuteLimit = minuteLimit;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    @Transactional
    public TestDelivery create(
            NotificationRequestContext.Actor actor,
            TestDeliveryRequest request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        List<String> channels = canonicalChannels(request.channels());
        Set<String> activeEndpoints = repository.activePushEndpoints(actor);
        Instant now = Instant.now(clock);
        boolean inApp = channels.contains("IN_APP");
        boolean external = channels.stream().anyMatch(channel -> !"IN_APP".equals(channel));
        TestDelivery delivery = new TestDelivery(
                UUID.randomUUID(),
                external ? "PARTIAL" : "COMPLETED",
                channels,
                stages(now, inApp, external, channels, activeEndpoints),
                now,
                now.plus(24, ChronoUnit.HOURS),
                null);
        return repository.create(
                actor, delivery, minuteLimit, dailyLimit, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public TestDelivery get(
            NotificationRequestContext.Actor actor,
            UUID testId) {
        databaseScope.applyUser(actor);
        return repository.get(actor, testId, Instant.now(clock));
    }

    private List<String> canonicalChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("At least one test delivery channel is required.");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(channels);
        if (unique.size() > 6
                || !NotificationAttentionScope.CHANNELS.containsAll(unique)) {
            throw new IllegalArgumentException("Unsupported test delivery channel.");
        }
        return List.copyOf(unique);
    }

    private List<TestDeliveryStage> stages(
            Instant now,
            boolean inApp,
            boolean external,
            List<String> channels,
            Set<String> activeEndpoints) {
        return List.of(
                new TestDeliveryStage(
                        "REQUEST_VALIDATION", "SUCCEEDED",
                        "The diagnostic request is valid and rate-limit eligible.", now),
                new TestDeliveryStage(
                        "PRIVACY_FILTER", "SUCCEEDED",
                        "No notification content or business action was accepted.", now),
                new TestDeliveryStage(
                        "IN_APP_PREVIEW",
                        inApp ? "SUCCEEDED" : "DISABLED",
                        inApp
                                ? "An in-app preview was simulated without inbox projection."
                                : "IN_APP was not requested.",
                        now),
                new TestDeliveryStage(
                        "ENDPOINT_DELIVERY",
                        "DISABLED",
                        endpointDetail(external, channels, activeEndpoints),
                        now));
    }

    private String endpointDetail(
            boolean external,
            List<String> channels,
            Set<String> activeEndpoints) {
        if (!external) return "No external endpoint channel was requested.";
        boolean registeredPushEndpoint = channels.stream()
                .filter(channel -> Set.of("WEB_PUSH", "MOBILE_PUSH").contains(channel))
                .anyMatch(activeEndpoints::contains);
        return registeredPushEndpoint
                ? "A compatible endpoint exists, but external delivery is disabled."
                : "External delivery is disabled and no compatible active endpoint was found.";
    }
}
