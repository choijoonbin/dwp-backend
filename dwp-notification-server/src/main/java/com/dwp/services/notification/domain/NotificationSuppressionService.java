package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationSuppressionModels.Suppression;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionCommand;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionPage;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionPreview;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionRevokeCommand;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@Service
public class NotificationSuppressionService {

    private static final Duration MAXIMUM_TTL = Duration.ofDays(31);
    private static final Set<String> SCOPES = Set.of("TENANT", "APP", "TYPE");
    private static final Set<String> CHANNELS = Set.of(
            "ALL", "IN_APP", "EMAIL", "WEB_PUSH", "MOBILE_PUSH", "TEAMS", "SLACK");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationSuppressionRepository repository;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final AuditOutboxRecorder audit;

    public NotificationSuppressionService(
            NotificationDatabaseScope databaseScope,
            NotificationSuppressionRepository repository,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SuppressionPage list(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        return new SuppressionPage(repository.list(actor.tenantId()), Instant.now());
    }

    @Transactional(readOnly = true)
    public SuppressionPreview preview(
            NotificationRequestContext.Actor actor,
            SuppressionCommand request) {
        databaseScope.applyWorker(actor.tenantId());
        Normalized normalized = normalize(actor.tenantId(), request, Instant.now());
        return preview(actor.tenantId(), normalized);
    }

    @Transactional
    public Suppression create(
            NotificationRequestContext.Actor actor,
            SuppressionCommand request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Normalized normalized = normalize(actor.tenantId(), request, Instant.now());
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "NOTIFICATION_SUPPRESSION_CREATE",
                normalized.request());
        Suppression replay = idempotencyRepository.replay(receipt, Suppression.class);
        if (replay != null) return replay;
        SuppressionPreview preview = preview(actor.tenantId(), normalized);
        if (preview.overlappingSuppressionCount() > 0) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_STALE_VERSION,
                    "An overlapping notification suppression already exists.");
        }
        Suppression result = repository.create(
                actor.tenantId(), actor.userId(), normalized.request(), normalized.startsAt());
        record(actor, "notification.suppression.created", result, result.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    @Transactional
    public Suppression revoke(
            NotificationRequestContext.Actor actor,
            UUID suppressionId,
            SuppressionRevokeCommand request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        long expectedVersion = positive(request.expectedVersion(), "expectedVersion");
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "NOTIFICATION_SUPPRESSION_REVOKE",
                Map.of(
                        "suppressionId", suppressionId,
                        "expectedVersion", expectedVersion,
                        "reason", request.reason().trim()));
        Suppression replay = idempotencyRepository.replay(receipt, Suppression.class);
        if (replay != null) return replay;
        repository.find(actor.tenantId(), suppressionId).orElseThrow(() ->
                new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!repository.revoke(
                actor.tenantId(), suppressionId, actor.userId(),
                expectedVersion, request.reason().trim())) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        Suppression result = repository.find(actor.tenantId(), suppressionId).orElseThrow();
        record(actor, "notification.suppression.revoked", result, request.reason().trim());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private SuppressionPreview preview(long tenantId, Normalized normalized) {
        SuppressionCommand request = normalized.request();
        List<String> riskFlags = new ArrayList<>();
        if (!request.criticalBypass()) riskFlags.add("CRITICAL_DELIVERY_BLOCKED");
        if ("TENANT".equals(request.scopeType())) riskFlags.add("TENANT_WIDE");
        if ("ALL".equals(request.channel())) riskFlags.add("ALL_CHANNELS");
        boolean inAppObserved = "ALL".equals(request.channel())
                || "IN_APP".equals(request.channel());
        if (!inAppObserved) riskFlags.add("EXTERNAL_CHANNEL_DISABLED");
        if (Duration.between(normalized.startsAt(), request.expiresAt()).toDays() >= 7) {
            riskFlags.add("LONG_RUNNING");
        }
        return new SuppressionPreview(
                request.scopeType(),
                request.scopeKey(),
                request.channel(),
                normalized.startsAt(),
                request.expiresAt(),
                request.criticalBypass(),
                repository.affectedTypeCount(tenantId, request.scopeType(), request.scopeKey()),
                inAppObserved
                        ? repository.observedNotifications7Days(
                                tenantId, request.scopeType(), request.scopeKey())
                        : 0,
                inAppObserved
                        ? repository.criticalNotifications7Days(
                                tenantId, request.scopeType(), request.scopeKey())
                        : 0,
                repository.overlappingCount(tenantId, request, normalized.startsAt()),
                repository.matchedTypeKeys(tenantId, request.scopeType(), request.scopeKey()),
                List.copyOf(riskFlags),
                Instant.now());
    }

    private Normalized normalize(long tenantId, SuppressionCommand request, Instant now) {
        String scopeType = request.scopeType().trim().toUpperCase(Locale.ROOT);
        String scopeKey = "TENANT".equals(scopeType) ? "*" : request.scopeKey().trim();
        String channel = request.channel().trim().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scopeType) || !CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Unsupported notification suppression scope.");
        }
        if (!repository.scopeExists(tenantId, scopeType, scopeKey)) {
            throw new IllegalArgumentException("The notification suppression scope is inactive.");
        }
        Instant requestedStart = request.startsAt();
        if (requestedStart != null && requestedStart.isBefore(now.minus(Duration.ofMinutes(5)))) {
            throw new IllegalArgumentException("A suppression cannot start in the past.");
        }
        Instant startsAt = requestedStart == null || requestedStart.isBefore(now)
                ? now : requestedStart;
        Duration ttl = Duration.between(startsAt, request.expiresAt());
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAXIMUM_TTL) > 0) {
            throw new IllegalArgumentException("Suppression TTL must be between now and 31 days.");
        }
        SuppressionCommand normalized = new SuppressionCommand(
                scopeType,
                scopeKey,
                channel,
                startsAt,
                request.expiresAt(),
                request.criticalBypass(),
                request.reason().trim());
        return new Normalized(normalized, startsAt);
    }

    private void record(
            NotificationRequestContext.Actor actor,
            String action,
            Suppression suppression,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity(suppression.criticalBypass() ? "MEDIUM" : "HIGH")
                .riskScore(suppression.criticalBypass() ? 55 : 75)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-delivery-admission")
                .targetType("NOTIFICATION_SUPPRESSION")
                .targetId(suppression.suppressionId().toString())
                .targetDisplayName(suppression.scopeType() + ":" + suppression.scopeKey())
                .reason(reason)
                .policyDecision("notification.suppression.created".equals(action)
                        ? "DENY"
                        : "NOT_APPLICABLE")
                .afterState(Map.of(
                        "scopeType", suppression.scopeType(),
                        "scopeKey", suppression.scopeKey(),
                        "channel", suppression.channel(),
                        "criticalBypass", suppression.criticalBypass(),
                        "startsAt", suppression.startsAt().toString(),
                        "expiresAt", suppression.expiresAt().toString()))
                .retentionClass("EXTENDED")
                .build());
    }

    private record Normalized(SuppressionCommand request, Instant startsAt) {
    }
}
