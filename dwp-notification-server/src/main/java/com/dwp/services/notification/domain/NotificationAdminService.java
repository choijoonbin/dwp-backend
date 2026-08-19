package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAdminRepository.AdminSnapshot;
import com.dwp.services.notification.domain.NotificationAdminRepository.DeliveryQueueSnapshot;
import com.dwp.services.notification.domain.NotificationModels.AdminMetric;
import com.dwp.services.notification.domain.NotificationModels.AdminOverview;
import com.dwp.services.notification.domain.NotificationModels.DeliveryOperations;
import com.dwp.services.notification.domain.NotificationModels.OperationalFinding;
import com.dwp.services.notification.domain.NotificationModels.ProviderHealth;
import com.dwp.services.notification.domain.NotificationModels.TypeContract;
import com.dwp.services.notification.domain.NotificationModels.TypeContractPage;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class NotificationAdminService {

    private static final Set<String> CONTRACT_STATES = Set.of(
            "DRAFT", "IN_REVIEW", "ACTIVE", "DEPRECATED", "RETIRED", "QUARANTINED");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAdminRepository repository;

    public NotificationAdminService(
            NotificationDatabaseScope databaseScope,
            NotificationAdminRepository repository) {
        this.databaseScope = databaseScope;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AdminOverview overview(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        Instant generatedAt = Instant.now();
        AdminSnapshot snapshot = repository.snapshot(actor.tenantId());
        List<AdminMetric> metrics = List.of(
                metric(
                        "active-contracts",
                        "Active contracts",
                        snapshot.activeContracts(),
                        "contracts",
                        snapshot.brokenContracts() == 0 ? "HEALTHY" : "ATTENTION"),
                metric(
                        "notifications-24h",
                        "Notifications (24h)",
                        snapshot.notifications24Hours(),
                        "notifications",
                        "HEALTHY"),
                metric(
                        "queued-deliveries",
                        "Queued deliveries",
                        snapshot.queuedJobs(),
                        "jobs",
                        snapshot.queuedJobs() > 1000 ? "ATTENTION" : "HEALTHY"),
                metric(
                        "failed-deliveries",
                        "Failed deliveries",
                        snapshot.failedJobs(),
                        "jobs",
                        snapshot.failedJobs() > 0 ? "CRITICAL" : "HEALTHY"));
        return new AdminOverview(
                false,
                List.of(),
                null,
                generatedAt,
                metrics,
                repository.trend(actor.tenantId()),
                findings(snapshot, generatedAt));
    }

    @Transactional(readOnly = true)
    public TypeContractPage types(
            NotificationRequestContext.Actor actor,
            String cursor,
            int limit,
            String query,
            String state,
            String appKey) {
        databaseScope.applyWorker(actor.tenantId());
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int offset = decodeOffset(cursor);
        String normalizedState = normalizeState(state);
        List<TypeContract> rows = repository.typeContracts(
                actor.tenantId(),
                bounded(query, 200),
                normalizedState,
                bounded(appKey, 100),
                offset,
                boundedLimit + 1);
        boolean hasMore = rows.size() > boundedLimit;
        List<TypeContract> items = hasMore
                ? List.copyOf(rows.subList(0, boundedLimit))
                : List.copyOf(rows);
        return new TypeContractPage(
                false,
                List.of(),
                null,
                items,
                hasMore ? encodeOffset(offset + boundedLimit) : null,
                hasMore);
    }

    @Transactional(readOnly = true)
    public DeliveryOperations operations(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        Instant generatedAt = Instant.now();
        DeliveryQueueSnapshot queue = repository.deliveryQueue(actor.tenantId());
        List<ProviderHealth> providers = List.of(
                disabledProvider("email", "Email provider", "EMAIL", generatedAt),
                disabledProvider("web-push", "Web Push provider", "WEB_PUSH", generatedAt),
                disabledProvider("mobile-push", "Mobile Push provider", "MOBILE_PUSH", generatedAt),
                disabledProvider("teams", "Microsoft Teams provider", "TEAMS", generatedAt),
                disabledProvider("slack", "Slack provider", "SLACK", generatedAt));
        List<OperationalFinding> findings = new ArrayList<>();
        findings.add(new OperationalFinding(
                "external-delivery-disabled",
                "DELIVERY",
                "INFO",
                "External delivery adapters are disabled",
                "Direct-recipient in-app delivery is the only enabled foundation capability.",
                providers.size(),
                "Notification Platform",
                generatedAt,
                null));
        if (queue.deadLetterQueue() > 0) {
            findings.add(new OperationalFinding(
                    "dead-letter-queue",
                    "DELIVERY",
                    "CRITICAL",
                    "Dead-letter queue requires review",
                    "One or more delivery jobs exhausted the retry budget.",
                    queue.deadLetterQueue(),
                    "Notification Operations",
                    generatedAt,
                    null));
        }
        return new DeliveryOperations(
                false,
                List.of(),
                null,
                generatedAt,
                repository.deliveryLanes(actor.tenantId()),
                providers,
                queue.retryQueue(),
                queue.deadLetterQueue(),
                queue.unknownOutcomes(),
                List.copyOf(findings));
    }

    private List<OperationalFinding> findings(AdminSnapshot snapshot, Instant detectedAt) {
        List<OperationalFinding> findings = new ArrayList<>();
        if (snapshot.brokenContracts() > 0) {
            findings.add(new OperationalFinding(
                    "contracts-without-active-template",
                    "CONTRACT",
                    "WARNING",
                    "Contracts require an active in-app template",
                    "Active delivery requires a published in-app template for every type.",
                    snapshot.brokenContracts(),
                    "Notification Contract Owners",
                    detectedAt,
                    null));
        }
        if (snapshot.failedJobs() > 0) {
            findings.add(new OperationalFinding(
                    "failed-delivery-jobs",
                    "DELIVERY",
                    "CRITICAL",
                    "Delivery failures require attention",
                    "Review retry and dead-letter queues before enabling external channels.",
                    snapshot.failedJobs(),
                    "Notification Operations",
                    detectedAt,
                    null));
        }
        return List.copyOf(findings);
    }

    private AdminMetric metric(
            String key,
            String label,
            double value,
            String unit,
            String state) {
        return new AdminMetric(key, label, value, unit, null, state);
    }

    private ProviderHealth disabledProvider(
            String providerKey,
            String displayName,
            String channel,
            Instant generatedAt) {
        return new ProviderHealth(
                providerKey,
                displayName,
                channel,
                "DISABLED",
                0,
                0,
                "CLOSED",
                generatedAt);
    }

    private String normalizeState(String state) {
        if (state == null || state.isBlank()) return null;
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        if (!CONTRACT_STATES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported notification contract state.");
        }
        return normalized;
    }

    private String bounded(String value, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Notification admin filter is too long.");
        }
        return normalized;
    }

    private String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int offset = Integer.parseInt(value);
            if (offset < 0 || offset > 1_000_000) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Notification admin cursor is invalid.");
        }
    }
}
