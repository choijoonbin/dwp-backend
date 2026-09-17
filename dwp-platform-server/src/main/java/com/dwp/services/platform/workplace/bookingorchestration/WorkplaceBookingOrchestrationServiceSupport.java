package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.AccessPermission;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationRepository.*;

public abstract class WorkplaceBookingOrchestrationServiceSupport {
    protected static final Duration MAX_PLANNING_HORIZON = Duration.ofDays(400);
    protected static final Duration MAX_ITEM_DURATION = Duration.ofHours(24);
    protected static final String PUBLIC_PREFIX = "/api/platform/v1/workplace";

    protected final WorkplaceBookingOrchestrationRepository repository;
    protected final WorkplaceSpatialGovernanceService spatialGovernance;
    protected final ObjectMapper objectMapper;
    protected final Clock clock;

    protected WorkplaceBookingOrchestrationServiceSupport(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceSpatialGovernanceService spatialGovernance,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.spatialGovernance = spatialGovernance;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public BatchStartResponse acceptAlternativeOffer(
            long tenantId,
            long actorId,
            UUID offerId,
            String idempotencyKey,
            String correlationId,
            AlternativeOfferAcceptRequest request) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw invalid("Alternative offer acceptance requires explicit confirmation.");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        String scope = "alternative-offer:" + offerId + ":accept";
        String fingerprint = fingerprint(scope, request);
        CommandReceiptRow receipt = repository.commandReceipt(
                tenantId, actorId, scope, key).orElse(null);
        if (receipt != null) {
            requireFingerprint(receipt.requestFingerprint(), fingerprint);
            return batchStart(repository.batch(tenantId, actorId, receipt.aggregateId())
                    .orElseThrow(() -> conflict("The offer acceptance receipt no longer resolves.")));
        }
        OfferRow offer = repository.offer(tenantId, actorId, offerId)
                .orElseThrow(() -> notFound("The alternative offer was not found."));
        OffsetDateTime now = now();
        if (offer.state() != OfferState.OFFERED || !offer.expiresAt().isAfter(now)) {
            throw conflict("The alternative offer expired or is no longer available.");
        }
        HoldRow hold = repository.hold(tenantId, offer.holdId())
                .orElseThrow(() -> conflict("The alternative offer hold no longer exists."));
        IntentRow intent = requireIntent(tenantId, actorId, hold.intentId());
        if (intent.state() != IntentState.HELD || hold.state() != HoldState.ACTIVE
                || !hold.expiresAt().isAfter(now)) {
            throw conflict("The alternative offer is no longer confirmable.");
        }
        IntentItemRow item = repository.intentItem(
                tenantId, intent.intentId(), hold.intentItemId())
                .orElseThrow(() -> conflict("The alternative offer planner item no longer exists."));
        CandidateRow candidate = repository.candidate(tenantId, hold.resourceId())
                .orElseThrow(() -> conflict("The alternative resource is no longer reservable."));
        BookingAuthority authority = candidate.resourceType() == ResourceType.ROOM
                ? BookingAuthority.CALENDAR : BookingAuthority.WORKPLACE;
        if (authority == BookingAuthority.CALENDAR && candidate.calendarResourceId() == null) {
            throw conflict("The offered room no longer has a Calendar authority mapping.");
        }
        if (!repository.beginOfferAcceptance(
                tenantId, offerId, request.expectedOfferVersion(), now)) {
            throw versionConflict("The alternative offer changed before acceptance.");
        }
        BatchRow batch = new BatchRow(
                UUID.randomUUID(), tenantId, intent.intentId(), actorId, BatchState.ACCEPTED,
                request.failurePolicy(), request.reason().trim(), true,
                derivedKey(scope, key), fingerprint, correlation(correlationId),
                1, now, null, null, now, null, null, 0);
        repository.createBatch(batch);
        if (!repository.attachHoldToBatch(
                tenantId, hold.holdId(), hold.version(), now)) {
            throw versionConflict("The alternative offer hold changed before confirmation.");
        }
        repository.createBatchItem(UUID.randomUUID(), batch, item, hold, authority, now);
        if (!repository.updateIntentState(
                tenantId, actorId, intent.intentId(), intent.version(), IntentState.HELD,
                IntentState.CONFIRMING, now)) {
            throw versionConflict("The alternative offer intent changed before confirmation.");
        }
        repository.completeOfferAcceptance(
                tenantId, offerId, batch.batchId(), OfferState.ACCEPTED, now);
        repository.createCommandReceipt(new CommandReceiptRow(
                tenantId, actorId, scope, key, fingerprint, "BOOKING_BATCH",
                batch.batchId(), now));
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.alternative.offer.accepted",
                "ALTERNATIVE_OFFER", offerId, offer.version() + 2,
                "AlternativeOfferAccepted", batch.correlationId(),
                Map.of("offerId", offerId, "batchId", batch.batchId(),
                        "waitlistEntryId", offer.waitlistEntryId()), now);
        return batchStart(batch);
    }

    protected List<CandidateRow> candidates(
            long tenantId,
            long actorId,
            String verifiedGroupRefs,
            IntentItemRequest item,
            boolean allowAlternatives) {
        List<CandidateRow> result = repository.candidates(
                tenantId, item.resourceType(), item.preferredResourceId(), item.siteId(),
                item.floorId(), item.startsAt(), item.endsAt(), item.accessibleOnly(),
                normalizedFeatures(item.requiredFeatures()), 12).stream()
                .filter(candidate -> floorBookAllowed(
                        tenantId, actorId, verifiedGroupRefs, candidate))
                .toList();
        if (result.isEmpty() && allowAlternatives && item.preferredResourceId() != null) {
            result = repository.candidates(
                    tenantId, item.resourceType(), null, item.siteId(), item.floorId(),
                    item.startsAt(), item.endsAt(), item.accessibleOnly(),
                    normalizedFeatures(item.requiredFeatures()), 12).stream()
                    .filter(candidate -> floorBookAllowed(
                            tenantId, actorId, verifiedGroupRefs, candidate))
                    .toList();
        }
        return result;
    }

    protected static void requireWholePlacementGroups(
            IntentRow intent, Map<String, UUID> selectedResourcesByClientKey) {
        for (TeamPlacementConstraintEvidence evidence : intent.placementConstraintEvidence()) {
            if (evidence.state() != ConstraintEvaluationState.SATISFIED) continue;
            boolean any = evidence.clientItemKeys().stream()
                    .anyMatch(selectedResourcesByClientKey::containsKey);
            if (!any) continue;
            if (!selectedResourcesByClientKey.keySet().containsAll(evidence.clientItemKeys())) {
                throw conflict("A team placement group must be held and confirmed as one complete set.");
            }
            for (int index = 0; index < evidence.clientItemKeys().size(); index++) {
                UUID selected = selectedResourcesByClientKey.get(
                        evidence.clientItemKeys().get(index));
                if (!Objects.equals(selected, evidence.selectedResourceIds().get(index))) {
                    throw conflict("The selected resource set no longer matches the team placement preview.");
                }
            }
        }
    }

    protected PlannedItem plannedItem(
            IntentItemRequest requested,
            CanonicalBeneficiary beneficiary,
            List<CandidateRow> candidates) {
        boolean preferredAvailable = requested.preferredResourceId() == null
                || candidates.stream().anyMatch(CandidateRow::preferred);
        IntentItemDecision decision = candidates.isEmpty()
                ? IntentItemDecision.UNAVAILABLE
                : preferredAvailable ? IntentItemDecision.AVAILABLE
                : IntentItemDecision.ALTERNATIVES_AVAILABLE;
        String code = candidates.isEmpty() ? "NO_AUTHORIZED_RESOURCE_AVAILABLE"
                : preferredAvailable ? "AVAILABLE" : "PREFERRED_UNAVAILABLE_ALTERNATIVES_FOUND";
        return new PlannedItem(requested, beneficiary, candidates, decision, code);
    }

    protected List<TeamPlacementConstraintEvidence> applyTeamConstraints(
            Map<String, PlannedItem> planned,
            List<TeamPlacementConstraint> constraints) {
        List<TeamPlacementConstraintEvidence> evidence = new ArrayList<>();
        Set<String> assignedItems = new HashSet<>();
        for (TeamPlacementConstraint constraint : constraints) {
            List<String> keys = constraint.clientItemKeys().stream()
                    .map(String::trim).distinct().toList();
            if (keys.size() != constraint.clientItemKeys().size()) {
                throw invalid("A team placement group cannot repeat a clientItemKey.");
            }
            if (keys.stream().anyMatch(assignedItems::contains)) {
                throw invalid("A planner item can belong to only one team placement group.");
            }
            assignedItems.addAll(keys);
            List<PlannedItem> group = keys.stream().map(planned::get).toList();
            if (group.stream().anyMatch(Objects::isNull)) {
                throw invalid("Every team placement clientItemKey must reference a planner item.");
            }
            if (constraint.minimumDistanceMeters() != null
                    || constraint.maximumDistanceMeters() != null) {
                if (constraint.minimumDistanceMeters() != null
                        && constraint.maximumDistanceMeters() != null
                        && constraint.maximumDistanceMeters().compareTo(
                                constraint.minimumDistanceMeters()) < 0) {
                    throw invalid("maximumDistanceMeters cannot be below minimumDistanceMeters.");
                }
                denyTeamGroup(planned, keys, "TEAM_DISTANCE_SCALE_UNAVAILABLE");
                evidence.add(new TeamPlacementConstraintEvidence(
                        constraint.groupKey().trim(), keys, ConstraintEvaluationState.UNSUPPORTED,
                        "PHYSICAL_DISTANCE_SCALE_NOT_CONFIGURED",
                        "The floor plan has normalized coordinates but no governed meter scale; "
                                + "distance-constrained booking is fail-closed.", List.of()));
                continue;
            }
            if (constraint.adjacentSeats() && group.stream().anyMatch(item ->
                    item.requested().resourceType() != ResourceType.DESK)) {
                denyTeamGroup(planned, keys, "TEAM_ADJACENCY_RESOURCE_TYPE_UNSUPPORTED");
                evidence.add(new TeamPlacementConstraintEvidence(
                        constraint.groupKey().trim(), keys, ConstraintEvaluationState.UNSUPPORTED,
                        "ADJACENCY_SUPPORTED_FOR_DESKS_ONLY",
                        "Adjacent-seat placement is supported only for desk resources.", List.of()));
                continue;
            }
            List<CandidateRow> selection = selectTeamPlacement(group, constraint);
            if (selection.isEmpty()) {
                denyTeamGroup(planned, keys, "TEAM_PLACEMENT_UNAVAILABLE");
                evidence.add(new TeamPlacementConstraintEvidence(
                        constraint.groupKey().trim(), keys, ConstraintEvaluationState.UNSATISFIED,
                        "NO_COMMON_TEAM_PLACEMENT",
                        "No authorized candidate set satisfies the requested team placement.",
                        List.of()));
                continue;
            }
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                PlannedItem current = planned.get(key);
                CandidateRow selected = selection.get(index);
                planned.put(key, new PlannedItem(
                        current.requested(), current.beneficiary(), List.of(selected),
                        current.requested().preferredResourceId() == null
                                || selected.preferred()
                                ? IntentItemDecision.AVAILABLE
                                : IntentItemDecision.ALTERNATIVES_AVAILABLE,
                        "TEAM_PLACEMENT_SATISFIED"));
            }
            evidence.add(new TeamPlacementConstraintEvidence(
                    constraint.groupKey().trim(), keys, ConstraintEvaluationState.SATISFIED,
                    "TEAM_PLACEMENT_SATISFIED",
                    "The preview selected a mutually compatible, authorized resource set.",
                    selection.stream().map(CandidateRow::resourceId).toList()));
        }
        return List.copyOf(evidence);
    }

    protected List<CandidateRow> selectTeamPlacement(
            List<PlannedItem> group, TeamPlacementConstraint constraint) {
        if (group.stream().anyMatch(item -> item.candidates().isEmpty())) return List.of();
        for (CandidateRow anchor : group.getFirst().candidates()) {
            List<CandidateRow> selected = new ArrayList<>();
            selected.add(anchor);
            boolean complete = true;
            for (int index = 1; index < group.size(); index++) {
                CandidateRow next = group.get(index).candidates().stream()
                        .filter(candidate -> selected.stream().noneMatch(existing ->
                                existing.resourceId().equals(candidate.resourceId())))
                        .filter(candidate -> !constraint.sameNeighborhood()
                                || sameNeighborhood(anchor, candidate))
                        .filter(candidate -> !constraint.adjacentSeats()
                                || selected.stream().anyMatch(existing -> adjacent(existing, candidate)))
                        .findFirst().orElse(null);
                if (next == null) {
                    complete = false;
                    break;
                }
                selected.add(next);
            }
            if (complete) return List.copyOf(selected);
        }
        return List.of();
    }

    protected static boolean sameNeighborhood(CandidateRow left, CandidateRow right) {
        return left.neighborhood() != null && !left.neighborhood().isBlank()
                && left.neighborhood().equalsIgnoreCase(right.neighborhood())
                && left.floorId().equals(right.floorId());
    }

    protected static boolean adjacent(CandidateRow left, CandidateRow right) {
        if (!left.floorId().equals(right.floorId())) return false;
        double leftEndX = left.positionX().add(left.widthPercent()).doubleValue();
        double rightEndX = right.positionX().add(right.widthPercent()).doubleValue();
        double leftEndY = left.positionY().add(left.heightPercent()).doubleValue();
        double rightEndY = right.positionY().add(right.heightPercent()).doubleValue();
        double dx = Math.max(0, Math.max(left.positionX().doubleValue(),
                right.positionX().doubleValue()) - Math.min(leftEndX, rightEndX));
        double dy = Math.max(0, Math.max(left.positionY().doubleValue(),
                right.positionY().doubleValue()) - Math.min(leftEndY, rightEndY));
        return Math.hypot(dx, dy) <= 2.0d;
    }

    protected static void denyTeamGroup(
            Map<String, PlannedItem> planned, List<String> keys, String decisionCode) {
        for (String key : keys) {
            PlannedItem current = planned.get(key);
            planned.put(key, new PlannedItem(
                    current.requested(), current.beneficiary(), List.of(),
                    IntentItemDecision.POLICY_DENIED, decisionCode));
        }
    }

    protected BookingIntentPreview intentPreview(
            long tenantId, IntentRow intent, String locale) {
        List<IntentItemPreview> items = repository.intentItems(tenantId, intent.intentId()).stream()
                .map(item -> new IntentItemPreview(
                        item.itemId(), item.clientItemKey(), item.actorUserId(),
                        item.beneficiaryUserId(), item.beneficiaryPersonPublicId(),
                        item.beneficiaryDisplayName(), item.delegationGrantId(),
                        item.resourceType(), item.startsAt(),
                        item.endsAt(), item.decision(), item.decisionCode(),
                        item.candidateResourceIds().stream()
                                .map(id -> repository.candidate(tenantId, id).orElse(null))
                                .filter(Objects::nonNull)
                                .map(value -> candidate(value, locale)).toList(), item.version()))
                .toList();
        return new BookingIntentPreview(
                intent.intentId(), intent.state(), intent.actorUserId(), intent.holdTtlSeconds(),
                intent.allowAlternatives(), intent.reason(), items,
                intent.teamPlacementConstraints(), intent.placementConstraintEvidence(),
                intent.version(),
                intent.createdAt());
    }

    protected BookingCandidate candidate(CandidateRow row, String locale) {
        String name = korean(locale) ? row.nameKo() : row.nameEn();
        if (name == null || name.isBlank()) name = korean(locale) ? row.nameEn() : row.nameKo();
        return new BookingCandidate(
                row.resourceId(), row.calendarResourceId(), name, row.resourceType(), row.siteId(),
                row.floorId(), row.timeZone(), row.accessible(), row.features(), row.neighborhood(),
                row.preferred(),
                row.version());
    }

    protected HoldResponse holdResponse(IntentRow intent) {
        return new HoldResponse(
                intent.intentId(), intent.state(), intent.version(), now(),
                repository.holds(intent.tenantId(), intent.intentId()).stream()
                        .map(this::hold).toList());
    }

    protected ReservationHold hold(HoldRow row) {
        return new ReservationHold(
                row.holdId(), row.intentItemId(), row.resourceId(), row.state(), row.startsAt(),
                row.endsAt(), row.expiresAt(), row.version());
    }

    protected BatchStartResponse batchStart(BatchRow batch) {
        return new BatchStartResponse(
                batch.batchId(), batch.state(), batchStatusUrl(batch.batchId()),
                batch.version(), batch.createdAt());
    }

    protected BookingBatch bookingBatch(BatchRow batch) {
        List<BookingBatchItem> items = repository.batchItems(
                batch.tenantId(), batch.batchId()).stream().map(row -> new BookingBatchItem(
                        row.batchItemId(), row.intentItemId(), row.holdId(), row.clientItemKey(),
                        row.beneficiaryUserId(), row.beneficiaryPersonPublicId(),
                        row.beneficiaryDisplayName(), row.delegationGrantId(), row.resourceType(),
                        row.resourceId(), row.resourceName(), row.siteId(), row.floorId(),
                        row.timeZone(), row.startsAt(), row.endsAt(), row.authority(),
                        row.state(), row.ownerReferenceId(), row.ownerVersion(), row.errorCode(),
                        row.errorMessage(), row.compensationAvailable(), row.requeryRequired(),
                        row.version(), row.updatedAt())).toList();
        boolean terminal = switch (batch.state()) {
            case SUCCEEDED, PARTIAL, FAILED, COMPENSATED, RESULT_UNKNOWN -> true;
            default -> false;
        };
        boolean requeryRequired = batch.state() == BatchState.RESULT_UNKNOWN
                || items.stream().anyMatch(BookingBatchItem::requeryRequired);
        return new BookingBatch(
                batch.batchId(), batch.intentId(), batch.actorUserId(), batch.state(),
                batch.failurePolicy(), batch.reason(), items, terminal, requeryRequired,
                batch.version(), batch.createdAt(), batch.startedAt(), batch.completedAt(),
                batch.updatedAt());
    }

    protected WaitlistEntry waitlist(WaitlistRow row) {
        OfferRow offer = repository.activeOffer(row.tenantId(), row.entryId()).orElse(null);
        HoldRow offerHold = offer == null ? null
                : repository.hold(row.tenantId(), offer.holdId()).orElse(null);
        ResourcePresentationRow presentation = offer == null ? null
                : repository.resourcePresentation(row.tenantId(), offer.resourceId()).orElse(null);
        AlternativeOffer publicOffer = offer == null ? null : new AlternativeOffer(
                offer.offerId(), offer.resourceId(),
                presentation == null ? null : presentation.resourceDisplayName(),
                presentation == null ? null : presentation.siteId(),
                presentation == null ? null : presentation.floorId(),
                presentation == null ? null : presentation.timeZone(),
                offer.holdId(), offer.state(),
                offerHold == null ? null : offerHold.startsAt(),
                offerHold == null ? null : offerHold.endsAt(),
                offer.expiresAt(), offer.acceptedBatchId(), offer.version());
        return new WaitlistEntry(
                row.entryId(), row.actorUserId(), row.beneficiaryUserId(),
                row.beneficiaryPersonPublicId(), row.beneficiaryDisplayName(), row.resourceType(),
                row.preferredResourceId(), row.siteId(), row.floorId(), row.startsAt(), row.endsAt(),
                row.purpose(), row.autoConfirm(), new WaitlistConditions(
                        row.maximumDistanceMeters(), row.earliestStart(), row.latestEnd(),
                row.pricingMode(), row.maximumPrice(), row.currency()),
                row.notificationChannels(), row.state(), row.promotionEvaluationState(),
                row.promotionDecisionCode(), row.promotionEvaluatedAt(),
                row.rank(), row.rankVisible(), publicOffer,
                row.version(), row.createdAt(), row.updatedAt());
    }

    protected Set<UUID> selectedCompensationItems(
            List<BatchItemRow> items, BatchCompensationRequest request) {
        if (request.compensateAllSucceeded()) {
            if (request.batchItemIds() != null && !request.batchItemIds().isEmpty()) {
                throw invalid("Choose either compensateAllSucceeded or explicit batchItemIds.");
            }
            return items.stream().filter(item -> item.state() == BatchItemState.SUCCEEDED
                            && item.compensationAvailable())
                    .map(BatchItemRow::batchItemId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (request.batchItemIds() == null) return Set.of();
        return new LinkedHashSet<>(request.batchItemIds());
    }

    protected CanonicalBeneficiary canonicalBeneficiary(
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            IntentItemRequest item,
            Map<UUID, BeneficiaryGrantRow> grants) {
        if (item.beneficiaryUserId() == actorId) {
            if (item.delegationGrantId() != null) {
                throw invalid("Self bookings cannot carry a delegation grant.");
            }
            return new CanonicalBeneficiary(
                    actorId, actorPersonPublicId, display(actorDisplayName, actorId), null);
        }
        if (item.delegationGrantId() == null) {
            throw forbidden("A delegated beneficiary requires an active delegation grant.");
        }
        BeneficiaryGrantRow grant = grants.get(item.delegationGrantId());
        if (grant == null || grant.beneficiaryUserId() != item.beneficiaryUserId()
                || (!grant.resourceTypes().isEmpty()
                        && !grant.resourceTypes().contains(item.resourceType()))) {
            throw forbidden("The delegation grant does not authorize this beneficiary and resource type.");
        }
        return new CanonicalBeneficiary(
                grant.beneficiaryUserId(), grant.beneficiaryPersonPublicId(),
                grant.beneficiaryDisplayName(), grant.grantId());
    }

    protected void validatePreview(IntentPreviewRequest request) {
        if (request == null) throw invalid("A booking intent preview is required.");
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 50) {
            throw invalid("A booking intent requires between 1 and 50 items.");
        }
        for (IntentItemRequest item : request.items()) validateItem(item);
        if (request.teamPlacementConstraints() == null
                || request.teamPlacementConstraints().size() > 20) {
            throw invalid("teamPlacementConstraints must contain at most 20 groups.");
        }
        for (TeamPlacementConstraint constraint : request.teamPlacementConstraints()) {
            if (constraint == null || constraint.groupKey() == null
                    || constraint.groupKey().isBlank()
                    || constraint.clientItemKeys() == null
                    || constraint.clientItemKeys().size() < 2
                    || constraint.clientItemKeys().size() > 50) {
                throw invalid("Each team placement constraint needs a key and at least two planner items.");
            }
            if (constraint.minimumDistanceMeters() != null
                    && constraint.minimumDistanceMeters().signum() < 0
                    || constraint.maximumDistanceMeters() != null
                    && constraint.maximumDistanceMeters().signum() < 0) {
                throw invalid("Team placement distances cannot be negative.");
            }
        }
    }

    protected void validateItem(IntentItemRequest item) {
        if (item == null || item.beneficiaryUserId() == null || item.beneficiaryUserId() < 1
                || item.resourceType() == null || item.startsAt() == null || item.endsAt() == null
                || item.clientItemKey() == null || item.clientItemKey().isBlank()
                || item.beneficiaryDisplayName() == null || item.beneficiaryDisplayName().isBlank()) {
            throw invalid("Each booking intent item requires a beneficiary, resource type, and time range.");
        }
        OffsetDateTime now = now();
        if (!item.endsAt().isAfter(item.startsAt()) || !item.endsAt().isAfter(now)) {
            throw invalid("Each planner time range must end in the future and after its start.");
        }
        if (Duration.between(item.startsAt(), item.endsAt()).compareTo(MAX_ITEM_DURATION) > 0) {
            throw invalid("A single planner item cannot exceed 24 hours.");
        }
        if (Duration.between(now, item.startsAt()).compareTo(MAX_PLANNING_HORIZON) > 0) {
            throw invalid("Planner items are limited to 400 days in advance.");
        }
        if (item.requiredFeatures() == null || item.requiredFeatures().size() > 20) {
            throw invalid("requiredFeatures must contain at most 20 values.");
        }
    }

    protected void validateWaitlistConditions(
            WaitlistConditions conditions, IntentItemRequest item) {
        if (conditions == null) throw invalid("Waitlist conditions are required.");
        if (conditions.earliestStart() != null && conditions.latestEnd() != null
                && !conditions.latestEnd().isAfter(conditions.earliestStart())) {
            throw invalid("The waitlist alternative time range is invalid.");
        }
        if (conditions.earliestStart() != null
                && conditions.earliestStart().isAfter(item.startsAt())) {
            throw invalid("earliestStart cannot be later than the requested start.");
        }
        if (conditions.latestEnd() != null && conditions.latestEnd().isBefore(item.endsAt())) {
            throw invalid("latestEnd cannot be earlier than the requested end.");
        }
        if (conditions.pricingMode() == null) {
            throw invalid("pricingMode is required for waitlist conditions.");
        }
        if (conditions.pricingMode() == PricingMode.NOT_APPLICABLE) {
            if (conditions.maximumPrice() != null || conditions.currency() != null) {
                throw invalid("Price and currency must be absent when pricing is NOT_APPLICABLE.");
            }
        } else {
            if (conditions.maximumPrice() == null || conditions.maximumPrice().signum() < 0
                    || conditions.currency() == null
                    || !conditions.currency().matches("[A-Z]{3}")) {
                throw invalid("Managed pricing requires a non-negative maximumPrice and ISO currency.");
            }
            throw invalid("Workplace resources have no governed price source; use NOT_APPLICABLE.");
        }
    }

    protected void requireFloorBookAccess(
            long tenantId, long actorId, String groups, CandidateRow candidate) {
        if (!floorBookAllowed(tenantId, actorId, groups, candidate)) {
            throw forbidden("The selected Workplace floor is outside the actor's booking scope.");
        }
    }

    protected boolean floorBookAllowed(
            long tenantId, long actorId, String groups, CandidateRow candidate) {
        return spatialGovernance.evaluateFloorAccess(
                tenantId, actorId, groups, candidate.siteId(), candidate.floorId(),
                AccessPermission.BOOK).allowed();
    }

    protected IntentRow requireIntent(long tenantId, long actorId, UUID intentId) {
        return repository.intent(tenantId, actorId, intentId)
                .orElseThrow(() -> notFound("The booking intent was not found."));
    }

    protected String fingerprint(String scope, Object request) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The booking command could not be fingerprinted.", exception);
        }
    }

    protected static void requireFingerprint(String existing, String requested) {
        if (!Objects.equals(existing, requested)) {
            throw conflict("The Idempotency-Key was already used for a different command.");
        }
    }

    protected static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[!-~]{1,160}")) {
            throw invalid("An opaque Idempotency-Key of 1–160 ASCII characters is required.");
        }
        return value;
    }

    protected static String derivedKey(String scope, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "scoped-" + HexFormat.of().formatHex(digest.digest(
                    (scope + "\u0000" + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    protected static void requireActor(long tenantId, long actorId) {
        if (tenantId < 1) throw new BaseException(ErrorCode.TENANT_MISSING);
        if (actorId < 1) throw new BaseException(ErrorCode.UNAUTHORIZED);
    }

    protected static List<String> normalizedFeatures(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
    }

    protected static String display(String value, long actorId) {
        return value == null || value.isBlank() ? "User " + actorId : value.trim();
    }

    protected static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    protected static String correlation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String result = value.trim();
        if (result.length() > 160) throw invalid("X-Correlation-ID cannot exceed 160 characters.");
        return result;
    }

    protected static boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    protected static String batchStatusUrl(UUID batchId) {
        return PUBLIC_PREFIX + "/booking-batches/" + batchId;
    }

    protected OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    protected static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    protected static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    protected static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    protected static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    protected static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    protected record CanonicalBeneficiary(
            long userId, UUID personPublicId, String displayName, UUID grantId) { }

    protected record PlannedItem(
            IntentItemRequest requested,
            CanonicalBeneficiary beneficiary,
            List<CandidateRow> candidates,
            IntentItemDecision decision,
            String decisionCode) { }
}
