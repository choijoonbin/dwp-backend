package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.AccessPermission;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationRepository.*;

/**
 * Re-evaluates durable waitlist entries against current provider-backed resource truth.
 * A promotion creates its exact reservation hold and offer in one transaction. The owner
 * booking is only attempted by the normal batch executor after an auto-confirmed offer.
 */
@Component
public class WorkplaceWaitlistPromotionWorker {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceWaitlistPromotionWorker.class);

    private final WorkplaceBookingOrchestrationRepository repository;
    private final WorkplaceBookingOrchestrationService orchestration;
    private final WorkplaceBookingBatchExecutor executor;
    private final WorkplaceSpatialGovernanceService spatialGovernance;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final int offerTtlSeconds;

    @Autowired
    public WorkplaceWaitlistPromotionWorker(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceBookingOrchestrationService orchestration,
            WorkplaceBookingBatchExecutor executor,
            WorkplaceSpatialGovernanceService spatialGovernance,
            PlatformTransactionManager transactionManager,
            @Value("${dwp.platform.workplace.waitlist-promotion.enabled:true}") boolean enabled,
            @Value("${dwp.platform.workplace.waitlist-promotion.batch-size:50}") int batchSize,
            @Value("${dwp.platform.workplace.waitlist-promotion.offer-ttl-seconds:120}")
            int offerTtlSeconds) {
        this(repository, orchestration, executor, spatialGovernance, transactionManager,
                Clock.systemUTC(), enabled, batchSize, offerTtlSeconds);
    }

    WorkplaceWaitlistPromotionWorker(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceBookingOrchestrationService orchestration,
            WorkplaceBookingBatchExecutor executor,
            WorkplaceSpatialGovernanceService spatialGovernance,
            PlatformTransactionManager transactionManager,
            Clock clock,
            boolean enabled,
            int batchSize,
            int offerTtlSeconds) {
        this.repository = repository;
        this.orchestration = orchestration;
        this.executor = executor;
        this.spatialGovernance = spatialGovernance;
        this.clock = clock;
        this.enabled = enabled;
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("waitlist promotion batchSize must be between 1 and 500");
        }
        if (offerTtlSeconds < 30 || offerTtlSeconds > 300) {
            throw new IllegalArgumentException("waitlist offer TTL must be between 30 and 300 seconds");
        }
        this.batchSize = batchSize;
        this.offerTtlSeconds = offerTtlSeconds;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${dwp.platform.workplace.waitlist-promotion.poll-delay-ms:30000}")
    public void maintainWaitlists() {
        if (!enabled) return;
        maintainOnce();
    }

    MaintenanceResult maintainOnce() {
        OffsetDateTime now = now();
        int expired = 0;
        int promoted = 0;
        int autoConfirmed = 0;
        for (OfferKey key : repository.expiredOfferKeys(now, batchSize)) {
            try {
                Boolean changed = transaction.execute(status -> expire(key, now));
                if (Boolean.TRUE.equals(changed)) expired++;
            } catch (RuntimeException exception) {
                log.warn("Waitlist offer expiry failed for tenant={} offerId={}",
                        key.tenantId(), key.offerId(), exception);
            }
        }
        for (WaitlistKey key : repository.promotionCandidateKeys(now, batchSize)) {
            Promotion promotion;
            try {
                promotion = transaction.execute(status -> promote(key, now));
            } catch (DataIntegrityViolationException exception) {
                // A concurrent worker won the active-offer or exact-hold race.
                log.debug("Waitlist promotion lost a concurrent claim for tenant={} entryId={}",
                        key.tenantId(), key.entryId());
                continue;
            } catch (RuntimeException exception) {
                log.warn("Waitlist promotion failed for tenant={} entryId={}",
                        key.tenantId(), key.entryId(), exception);
                continue;
            }
            if (promotion == null) continue;
            promoted++;
            if (promotion.entry().autoConfirm() && autoConfirm(promotion)) {
                autoConfirmed++;
            }
        }
        return new MaintenanceResult(expired, promoted, autoConfirmed);
    }

    PromotionEvaluationState reevaluate(long tenantId, UUID entryId) {
        Promotion promotion = transaction.execute(status -> promote(
                new WaitlistKey(tenantId, entryId), now()));
        if (promotion != null && promotion.entry().autoConfirm()) {
            autoConfirm(promotion);
        }
        return repository.waitlist(tenantId, entryId)
                .map(WaitlistRow::promotionEvaluationState)
                .orElseThrow(() -> new IllegalStateException("Waitlist entry no longer exists."));
    }

    private Boolean expire(OfferKey key, OffsetDateTime now) {
        OfferRow offer = repository.lockExpiredOffer(key.tenantId(), key.offerId(), now)
                .orElse(null);
        if (offer == null) return false;
        WaitlistRow entry = repository.waitlist(offer.tenantId(), offer.waitlistEntryId())
                .orElseThrow(() -> new IllegalStateException("Waitlist entry no longer exists."));
        repository.expireOffer(offer, now);
        repository.auditAndOutbox(
                offer.tenantId(), entry.actorUserId(), "workplace.waitlist.offer.expired",
                "ALTERNATIVE_OFFER", offer.offerId(), offer.version() + 1,
                "AlternativeOfferExpired", entry.correlationId(),
                Map.of("offerId", offer.offerId(),
                        "waitlistEntryId", offer.waitlistEntryId(),
                        "holdId", offer.holdId()), now);
        return true;
    }

    private Promotion promote(WaitlistKey key, OffsetDateTime now) {
        WaitlistRow entry = repository.lockWaitlistForPromotion(
                key.tenantId(), key.entryId(), now).orElse(null);
        if (entry == null || repository.activeOffer(entry.tenantId(), entry.entryId()).isPresent()) {
            return null;
        }
        if (!repository.delegationValid(
                entry.tenantId(), entry.delegationGrantId(), entry.actorUserId(),
                entry.beneficiaryUserId(), entry.resourceType(), null, now)) {
            block(entry, repository.groupDelegation(entry.tenantId(), entry.delegationGrantId())
                    ? "GROUP_MEMBERSHIP_REVALIDATION_UNAVAILABLE"
                    : "BENEFICIARY_AUTHORIZATION_NOT_ACTIVE", now);
            return null;
        }

        if (entry.maximumDistanceMeters() != null && entry.preferredResourceId() == null) {
            block(entry, "DISTANCE_ANCHOR_REQUIRED", now);
            return null;
        }
        Match match = candidate(entry, now).orElse(null);
        if (match == null) {
            String code = entry.maximumDistanceMeters() == null
                    ? "NO_CURRENT_RESOURCE_MATCH"
                    : "PHYSICAL_DISTANCE_SCALE_NOT_CONFIGURED";
            if (entry.maximumDistanceMeters() == null) {
                repository.recordPromotionEvaluation(
                        entry, PromotionEvaluationState.NO_MATCH, code, now);
            } else {
                block(entry, code, now);
            }
            return null;
        }
        CandidateRow candidate = match.candidate();
        repository.lockResource(entry.tenantId(), candidate.resourceId());
        repository.expireHolds(entry.tenantId(), candidate.resourceId(), now);
        candidate = exactCandidate(
                entry, candidate.resourceId(), match.startsAt(), match.endsAt()).orElse(null);
        if (candidate == null
                || repository.hasHoldConflict(entry.tenantId(), candidate.resourceId(),
                match.startsAt(), match.endsAt(), now)
                || repository.hasBookingConflict(
                entry.tenantId(), candidate, match.startsAt(), match.endsAt())) {
            repository.recordPromotionEvaluation(
                    entry, PromotionEvaluationState.NO_MATCH,
                    "MATCH_LOST_DURING_EXACT_HOLD", now);
            return null;
        }

        UUID intentId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusSeconds(offerTtlSeconds);
        String correlationId = entry.correlationId();
        String intentKey = "waitlist-promotion-" + offerId;
        String requestFingerprint = fingerprint(entry.entryId(), offerId, candidate.resourceId());
        repository.createIntent(new IntentRow(
                intentId, entry.tenantId(), entry.actorUserId(), IntentState.HELD,
                "Waitlist resource promotion", offerTtlSeconds, true, intentKey,
                List.of(), List.of(), requestFingerprint, correlationId, 1, now, now));
        repository.createIntentItem(new IntentItemRow(
                itemId, intentId, entry.tenantId(), "waitlist-" + entry.entryId(),
                entry.actorUserId(), entry.beneficiaryUserId(),
                entry.beneficiaryPersonPublicId(), entry.beneficiaryDisplayName(),
                entry.delegationGrantId(), entry.resourceType(), candidate.resourceId(),
                candidate.siteId(), candidate.floorId(), match.startsAt(), match.endsAt(),
                entry.purpose(), entry.visibleToColleagues(), entry.accessibleOnly(),
                entry.requiredFeatures(), IntentItemDecision.AVAILABLE,
                "WAITLIST_RESOURCE_AVAILABLE", List.of(candidate.resourceId()), 1, now));
        repository.createHold(new HoldRow(
                holdId, entry.tenantId(), intentId, itemId, candidate.resourceId(),
                entry.actorUserId(), entry.beneficiaryUserId(), HoldState.ACTIVE,
                match.startsAt(), match.endsAt(), expiresAt, 1, now, now));
        OfferRow offer = new OfferRow(
                offerId, entry.tenantId(), entry.entryId(), holdId, candidate.resourceId(),
                OfferState.OFFERED, expiresAt, null, 1, now, now);
        repository.createOffer(offer, match.decisionCode());
        repository.auditAndOutbox(
                entry.tenantId(), entry.actorUserId(), "workplace.waitlist.offer.created",
                "ALTERNATIVE_OFFER", offerId, 1, "AlternativeOfferCreated", correlationId,
                Map.of("offerId", offerId, "waitlistEntryId", entry.entryId(),
                        "intentId", intentId, "holdId", holdId,
                        "resourceId", candidate.resourceId(),
                        "startsAt", match.startsAt(), "endsAt", match.endsAt(),
                        "decisionCode", match.decisionCode(), "expiresAt", expiresAt), now);
        return new Promotion(entry, offer);
    }

    private Optional<Match> candidate(WaitlistRow entry, OffsetDateTime now) {
        List<Window> windows = windows(entry, now);
        for (Window window : windows) {
            Optional<CandidateRow> exact = repository.candidates(
                            entry.tenantId(), entry.resourceType(), entry.preferredResourceId(),
                            entry.siteId(), entry.floorId(), window.startsAt(), window.endsAt(),
                            entry.accessibleOnly(), entry.requiredFeatures(), 12).stream()
                    .filter(candidate -> floorBookAllowed(entry, candidate)).findFirst();
            if (exact.isPresent()) {
                return Optional.of(new Match(exact.get(), window.startsAt(), window.endsAt(),
                        window.original() ? "MATCHED_ORIGINAL_WINDOW"
                                : "MATCHED_FLEXIBLE_WINDOW"));
            }
        }
        if (entry.preferredResourceId() != null && entry.maximumDistanceMeters() != null) {
            return Optional.empty();
        }
        for (Window window : windows) {
            Optional<CandidateRow> alternative = repository.candidates(
                            entry.tenantId(), entry.resourceType(), null,
                            entry.siteId(), entry.floorId(), window.startsAt(), window.endsAt(),
                            entry.accessibleOnly(), entry.requiredFeatures(), 12).stream()
                    .filter(candidate -> floorBookAllowed(entry, candidate)).findFirst();
            if (alternative.isPresent()) {
                return Optional.of(new Match(alternative.get(), window.startsAt(), window.endsAt(),
                        window.original() ? "MATCHED_ALTERNATIVE_RESOURCE"
                                : "MATCHED_FLEXIBLE_WINDOW_AND_RESOURCE"));
            }
        }
        return Optional.empty();
    }

    private Optional<CandidateRow> exactCandidate(
            WaitlistRow entry, UUID resourceId,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return repository.candidates(
                        entry.tenantId(), entry.resourceType(), resourceId,
                        entry.siteId(), entry.floorId(), startsAt, endsAt,
                        entry.accessibleOnly(), entry.requiredFeatures(), 1).stream()
                .filter(candidate -> floorBookAllowed(entry, candidate)).findFirst();
    }

    private List<Window> windows(WaitlistRow entry, OffsetDateTime now) {
        Duration duration = Duration.between(entry.startsAt(), entry.endsAt());
        OffsetDateTime earliest = entry.earliestStart() == null
                ? entry.startsAt() : entry.earliestStart();
        OffsetDateTime latestEnd = entry.latestEnd() == null
                ? entry.endsAt() : entry.latestEnd();
        LinkedHashMap<String, Window> unique = new LinkedHashMap<>();
        if (entry.startsAt().isAfter(now)) {
            Window requested = new Window(entry.startsAt(), entry.endsAt(), true);
            unique.put(requested.startsAt() + "/" + requested.endsAt(), requested);
        }
        OffsetDateTime cursor = earliest;
        int bounded = 0;
        while (!cursor.plus(duration).isAfter(latestEnd) && bounded++ < 96) {
            if (cursor.isAfter(now)) {
                Window alternative = new Window(cursor, cursor.plus(duration), false);
                unique.putIfAbsent(
                        alternative.startsAt() + "/" + alternative.endsAt(), alternative);
            }
            cursor = cursor.plusMinutes(30);
        }
        return new ArrayList<>(unique.values());
    }

    private void block(WaitlistRow entry, String code, OffsetDateTime now) {
        boolean changed = repository.recordPromotionEvaluation(
                entry, PromotionEvaluationState.BLOCKED, code, now);
        if (!changed) return;
        repository.auditAndOutbox(
                entry.tenantId(), entry.actorUserId(), "workplace.waitlist.promotion.blocked",
                "WAITLIST_ENTRY", entry.entryId(), entry.version() + 1,
                "WaitlistPromotionBlocked", entry.correlationId(),
                Map.of("waitlistEntryId", entry.entryId(), "decisionCode", code), now);
    }

    private boolean floorBookAllowed(WaitlistRow entry, CandidateRow candidate) {
        return spatialGovernance.evaluateFloorAccess(
                entry.tenantId(), entry.actorUserId(), null, candidate.siteId(),
                candidate.floorId(), AccessPermission.BOOK).allowed();
    }

    private boolean autoConfirm(Promotion promotion) {
        try {
            BatchStartResponse batch = transaction.execute(status ->
                    orchestration.acceptAlternativeOffer(
                            promotion.entry().tenantId(), promotion.entry().actorUserId(),
                            promotion.offer().offerId(),
                            "waitlist-auto-confirm-" + promotion.offer().offerId(),
                            promotion.entry().correlationId(),
                            new AlternativeOfferAcceptRequest(
                                    promotion.offer().version(), FailurePolicy.KEEP_SUCCEEDED,
                                    "Auto-confirmed by the waitlist preference", true)));
            if (batch == null) return false;
            executor.execute(promotion.entry().tenantId(), batch.batchId(), "en", null);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Waitlist auto-confirm dispatch failed for tenant={} offerId={}",
                    promotion.entry().tenantId(), promotion.offer().offerId(), exception);
            return false;
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String fingerprint(UUID entryId, UUID offerId, UUID resourceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(entryId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(offerId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(resourceId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record MaintenanceResult(int expired, int promoted, int autoConfirmed) { }
    private record Window(OffsetDateTime startsAt, OffsetDateTime endsAt, boolean original) { }
    private record Match(
            CandidateRow candidate, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String decisionCode) { }
    private record Promotion(WaitlistRow entry, OfferRow offer) { }
}
