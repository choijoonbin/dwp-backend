package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.AccessPermission;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

@Service
public class WorkplaceBookingOrchestrationService
        extends WorkplaceBookingOrchestrationServiceSupport {
    private static final int DEFAULT_HOLD_TTL_SECONDS = 120;

    private final WorkplaceBookingBatchExecutor executor;

    @Autowired
    public WorkplaceBookingOrchestrationService(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceSpatialGovernanceService spatialGovernance,
            WorkplaceBookingBatchExecutor executor,
            ObjectMapper objectMapper) {
        this(repository, spatialGovernance, executor, objectMapper, Clock.systemUTC());
    }

    WorkplaceBookingOrchestrationService(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceSpatialGovernanceService spatialGovernance,
            WorkplaceBookingBatchExecutor executor,
            ObjectMapper objectMapper,
            Clock clock) {
        super(repository, spatialGovernance, objectMapper, clock);
        this.executor = executor;
    }

    @Transactional(readOnly = true)
    public AuthorizedBeneficiaries beneficiaries(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs) {
        requireActor(tenantId, actorId);
        OffsetDateTime now = now();
        List<AuthorizedBeneficiary> result = new ArrayList<>();
        result.add(new AuthorizedBeneficiary(
                actorId, actorPersonPublicId, display(actorDisplayName, actorId), null,
                List.copyOf(EnumSet.allOf(ResourceType.class)), null, true));
        for (BeneficiaryGrantRow row : repository.beneficiaries(
                tenantId, actorId, verifiedGroupRefs, now)) {
            result.add(new AuthorizedBeneficiary(
                    row.beneficiaryUserId(), row.beneficiaryPersonPublicId(),
                    row.beneficiaryDisplayName(), row.grantId(), row.resourceTypes(),
                    row.validUntil(), false));
        }
        return new AuthorizedBeneficiaries(List.copyOf(result), now);
    }

    @Transactional
    public BookingIntentPreview preview(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String locale,
            String idempotencyKey,
            String correlationId,
            IntentPreviewRequest request) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        validatePreview(request);
        String fingerprint = fingerprint("intent-preview", request);
        IntentRow existing = repository.intentByIdempotency(tenantId, actorId, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing.requestFingerprint(), fingerprint);
            return intentPreview(tenantId, existing, locale);
        }

        OffsetDateTime now = now();
        Map<UUID, BeneficiaryGrantRow> grants = repository
                .beneficiaries(tenantId, actorId, verifiedGroupRefs, now).stream()
                .collect(Collectors.toMap(BeneficiaryGrantRow::grantId, Function.identity()));
        int ttl = request.requestedHoldTtlSeconds() == null
                ? DEFAULT_HOLD_TTL_SECONDS : request.requestedHoldTtlSeconds();
        UUID intentId = UUID.randomUUID();
        Set<String> clientKeys = new HashSet<>();
        Map<String, PlannedItem> planned = new LinkedHashMap<>();
        for (IntentItemRequest requested : request.items()) {
            String clientKey = requested.clientItemKey().trim();
            if (!clientKeys.add(clientKey)) {
                throw invalid("Each planner item must have a unique clientItemKey.");
            }
            CanonicalBeneficiary beneficiary = canonicalBeneficiary(
                    actorId, actorPersonPublicId, actorDisplayName, requested, grants);
            List<CandidateRow> candidates = candidates(
                    tenantId, actorId, verifiedGroupRefs, requested, request.allowAlternatives());
            planned.put(clientKey, plannedItem(requested, beneficiary, candidates));
        }
        List<TeamPlacementConstraintEvidence> evidence = applyTeamConstraints(
                planned, request.teamPlacementConstraints());
        IntentRow intent = new IntentRow(
                intentId, tenantId, actorId, IntentState.PREVIEWED, request.reason().trim(),
                ttl, request.allowAlternatives(), key,
                List.copyOf(request.teamPlacementConstraints()), evidence, fingerprint,
                correlation(correlationId), 1, now, now);
        repository.createIntent(intent);

        for (Map.Entry<String, PlannedItem> entry : planned.entrySet()) {
            IntentItemRequest requested = entry.getValue().requested();
            CanonicalBeneficiary beneficiary = entry.getValue().beneficiary();
            IntentItemRow item = new IntentItemRow(
                    UUID.randomUUID(), intentId, tenantId, entry.getKey(), actorId,
                    beneficiary.userId(), beneficiary.personPublicId(), beneficiary.displayName(),
                    beneficiary.grantId(), requested.resourceType(), requested.preferredResourceId(),
                    requested.siteId(), requested.floorId(), requested.startsAt(), requested.endsAt(),
                    blank(requested.purpose()), requested.visibleToColleagues(),
                    requested.accessibleOnly(), normalizedFeatures(requested.requiredFeatures()),
                    entry.getValue().decision(), entry.getValue().decisionCode(),
                    entry.getValue().candidates().stream().map(CandidateRow::resourceId).toList(),
                    1, now);
            repository.createIntentItem(item);
        }
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.booking.intent.previewed", "BOOKING_INTENT",
                intentId, intent.version(), "BookingIntentPreviewed", intent.correlationId(),
                Map.of("intentId", intentId, "itemCount", request.items().size(),
                        "allowAlternatives", request.allowAlternatives(),
                        "placementConstraintCount", evidence.size()), now);
        return intentPreview(tenantId, intent, locale);
    }

    @Transactional(readOnly = true)
    public BookingIntentStatus intentStatus(
            long tenantId, long actorId, UUID intentId, String locale) {
        requireActor(tenantId, actorId);
        IntentRow intent = requireIntent(tenantId, actorId, intentId);
        BatchRow latest = repository.latestBatchForIntent(tenantId, actorId, intentId).orElse(null);
        return new BookingIntentStatus(
                intentPreview(tenantId, intent, locale),
                repository.holds(tenantId, intentId).stream().map(this::hold).toList(),
                latest == null ? null : latest.batchId(),
                latest == null ? null : batchStatusUrl(latest.batchId()), now());
    }

    @Transactional
    public HoldResponse createHolds(
            long tenantId,
            long actorId,
            String verifiedGroupRefs,
            String idempotencyKey,
            String correlationId,
            UUID intentId,
            HoldRequest request) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw invalid("Hold creation requires explicit confirmation.");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        String scope = "booking-intent:" + intentId + ":holds";
        String fingerprint = fingerprint(scope, request);
        CommandReceiptRow receipt = repository.commandReceipt(
                tenantId, actorId, scope, key).orElse(null);
        if (receipt != null) {
            requireFingerprint(receipt.requestFingerprint(), fingerprint);
            return holdResponse(requireIntent(tenantId, actorId, intentId));
        }

        IntentRow intent = requireIntent(tenantId, actorId, intentId);
        if (intent.state() != IntentState.PREVIEWED) {
            throw conflict("Only a current preview can issue reservation holds.");
        }
        if (intent.version() != request.expectedIntentVersion()) {
            throw versionConflict("The booking intent changed. Refresh the preview.");
        }
        Set<UUID> selectedItems = new HashSet<>();
        Map<String, UUID> selectedResourcesByClientKey = new LinkedHashMap<>();
        OffsetDateTime now = now();
        OffsetDateTime expiresAt = now.plusSeconds(intent.holdTtlSeconds());
        for (HoldSelection selection : request.selections()) {
            if (!selectedItems.add(selection.intentItemId())) {
                throw invalid("A planner item can be held only once.");
            }
            IntentItemRow item = repository.intentItem(tenantId, intentId, selection.intentItemId())
                    .orElseThrow(() -> notFound("The planner item was not found."));
            if (item.version() != selection.expectedItemVersion()) {
                throw versionConflict("A planner item changed. Refresh the preview.");
            }
            if (!item.candidateResourceIds().contains(selection.resourceId())) {
                throw conflict("The selected resource was not part of this preview.");
            }
            selectedResourcesByClientKey.put(item.clientItemKey(), selection.resourceId());
            if (!repository.delegationValid(
                    tenantId, item.delegationGrantId(), actorId, item.beneficiaryUserId(),
                    item.resourceType(), verifiedGroupRefs, now)) {
                throw forbidden("The booking beneficiary authorization expired or was revoked.");
            }
            CandidateRow candidate = repository.candidate(tenantId, selection.resourceId())
                    .orElseThrow(() -> conflict("The selected resource is no longer reservable."));
            if (candidate.version() != selection.expectedResourceVersion()) {
                throw versionConflict("The selected resource changed. Refresh the preview.");
            }
            if (candidate.resourceType() != item.resourceType()) {
                throw conflict("The selected resource type no longer matches the planner item.");
            }
            requireFloorBookAccess(tenantId, actorId, verifiedGroupRefs, candidate);
            repository.lockResource(tenantId, candidate.resourceId());
            repository.expireHolds(tenantId, candidate.resourceId(), now);
            if (repository.hasHoldConflict(
                    tenantId, candidate.resourceId(), item.startsAt(), item.endsAt(), now)
                    || repository.hasBookingConflict(
                            tenantId, candidate, item.startsAt(), item.endsAt())) {
                throw conflict("The selected resource became unavailable before the hold was issued.");
            }
            repository.createHold(new HoldRow(
                    UUID.randomUUID(), tenantId, intentId, item.itemId(), candidate.resourceId(),
                    actorId, item.beneficiaryUserId(), HoldState.ACTIVE,
                    item.startsAt(), item.endsAt(), expiresAt, 1, now, now));
        }
        requireWholePlacementGroups(intent, selectedResourcesByClientKey);
        if (!repository.updateIntentState(
                tenantId, actorId, intentId, intent.version(), IntentState.PREVIEWED,
                IntentState.HELD, now)) {
            throw versionConflict("The booking intent changed while holds were being created.");
        }
        repository.createCommandReceipt(new CommandReceiptRow(
                tenantId, actorId, scope, key, fingerprint, "BOOKING_INTENT", intentId, now));
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.booking.holds.created", "BOOKING_INTENT",
                intentId, intent.version() + 1, "ReservationHoldsCreated",
                correlation(correlationId), Map.of("intentId", intentId,
                        "holdCount", request.selections().size(), "expiresAt", expiresAt), now);
        return holdResponse(requireIntent(tenantId, actorId, intentId));
    }

    @Transactional
    public BatchStartResponse startBatch(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String correlationId,
            BatchStartRequest request) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw invalid("Booking confirmation requires explicit confirmation.");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint("booking-batch", request);
        BatchRow existing = repository.batchByIdempotency(tenantId, actorId, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing.requestFingerprint(), fingerprint);
            return batchStart(existing);
        }
        IntentRow intent = requireIntent(tenantId, actorId, request.intentId());
        if (intent.state() != IntentState.HELD) {
            throw conflict("The booking intent has no confirmable reservation holds.");
        }
        if (intent.version() != request.expectedIntentVersion()) {
            throw versionConflict("The booking intent changed. Refresh its status before confirmation.");
        }
        Set<UUID> uniqueHolds = new LinkedHashSet<>();
        List<HoldRow> holds = new ArrayList<>();
        Map<String, UUID> selectedResourcesByClientKey = new LinkedHashMap<>();
        OffsetDateTime now = now();
        for (HoldReference reference : request.holds()) {
            if (!uniqueHolds.add(reference.holdId())) {
                throw invalid("A reservation hold can appear only once in a batch.");
            }
            HoldRow hold = repository.hold(tenantId, reference.holdId())
                    .orElseThrow(() -> notFound("The reservation hold was not found."));
            if (!hold.intentId().equals(intent.intentId()) || hold.actorUserId() != actorId) {
                throw notFound("The reservation hold was not found for this booking intent.");
            }
            if (hold.version() != reference.expectedVersion()) {
                throw versionConflict("A reservation hold changed. Refresh the intent status.");
            }
            if (hold.state() != HoldState.ACTIVE || !hold.expiresAt().isAfter(now)) {
                throw conflict("A reservation hold expired or is no longer confirmable.");
            }
            IntentItemRow heldItem = repository.intentItem(
                    tenantId, intent.intentId(), hold.intentItemId())
                    .orElseThrow(() -> conflict("A held planner item no longer exists."));
            selectedResourcesByClientKey.put(heldItem.clientItemKey(), hold.resourceId());
            holds.add(hold);
        }
        requireWholePlacementGroups(intent, selectedResourcesByClientKey);
        BatchRow batch = new BatchRow(
                UUID.randomUUID(), tenantId, intent.intentId(), actorId, BatchState.ACCEPTED,
                request.failurePolicy(), request.reason().trim(), true, key, fingerprint,
                correlation(correlationId), 1, now, null, null, now, null, null, 0);
        repository.createBatch(batch);
        for (HoldRow hold : holds) {
            IntentItemRow item = repository.intentItem(
                    tenantId, intent.intentId(), hold.intentItemId())
                    .orElseThrow(() -> conflict("A held planner item no longer exists."));
            CandidateRow candidate = repository.candidate(tenantId, hold.resourceId())
                    .orElseThrow(() -> conflict("A held resource is no longer reservable."));
            BookingAuthority authority = candidate.resourceType() == ResourceType.ROOM
                    ? BookingAuthority.CALENDAR : BookingAuthority.WORKPLACE;
            if (authority == BookingAuthority.CALENDAR && candidate.calendarResourceId() == null) {
                throw conflict("The held room no longer has a Calendar authority mapping.");
            }
            if (!repository.attachHoldToBatch(
                    tenantId, hold.holdId(), hold.version(), now)) {
                throw versionConflict("A reservation hold changed before confirmation.");
            }
            repository.createBatchItem(
                    UUID.randomUUID(), batch, item, hold, authority, now);
        }
        if (!repository.updateIntentState(
                tenantId, actorId, intent.intentId(), intent.version(), IntentState.HELD,
                IntentState.CONFIRMING, now)) {
            throw versionConflict("The booking intent changed before confirmation.");
        }
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.booking.batch.started", "BOOKING_BATCH",
                batch.batchId(), batch.version(), "BookingBatchStarted", batch.correlationId(),
                Map.of("batchId", batch.batchId(), "intentId", batch.intentId(),
                        "itemCount", holds.size(), "failurePolicy", batch.failurePolicy().name()), now);
        return batchStart(batch);
    }

    public void executeBatch(
            long tenantId, UUID batchId, String locale, String verifiedGroupRefs) {
        executor.execute(tenantId, batchId, locale, verifiedGroupRefs);
    }

    @Transactional(readOnly = true)
    public BookingBatch batch(long tenantId, long actorId, UUID batchId) {
        requireActor(tenantId, actorId);
        return bookingBatch(repository.batch(tenantId, actorId, batchId)
                .orElseThrow(() -> notFound("The booking batch was not found.")));
    }

    @Transactional
    public Set<UUID> beginCompensation(
            long tenantId,
            long actorId,
            UUID batchId,
            String idempotencyKey,
            String correlationId,
            BatchCompensationRequest request) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw invalid("Compensation requires explicit confirmation.");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        String scope = "booking-batch:" + batchId + ":compensation";
        String fingerprint = fingerprint(scope, request);
        CommandReceiptRow receipt = repository.commandReceipt(
                tenantId, actorId, scope, key).orElse(null);
        if (receipt != null) {
            requireFingerprint(receipt.requestFingerprint(), fingerprint);
            return selectedCompensationItems(
                    repository.batchItems(tenantId, batchId), request);
        }
        BatchRow batch = repository.batch(tenantId, actorId, batchId)
                .orElseThrow(() -> notFound("The booking batch was not found."));
        List<BatchItemRow> items = repository.batchItems(tenantId, batchId);
        Set<UUID> selected = selectedCompensationItems(items, request);
        if (selected.isEmpty()) {
            throw invalid("Select at least one successful booking to compensate.");
        }
        Map<UUID, BatchItemRow> byId = items.stream().collect(Collectors.toMap(
                BatchItemRow::batchItemId, Function.identity()));
        for (UUID id : selected) {
            BatchItemRow item = byId.get(id);
            if (item == null || item.state() != BatchItemState.SUCCEEDED
                    || !item.compensationAvailable()) {
                throw conflict("A selected batch item is not compensatable.");
            }
        }
        OffsetDateTime now = now();
        if (!repository.beginManualCompensation(
                tenantId, actorId, batchId, request.expectedBatchVersion(), now)) {
            throw versionConflict("The booking batch changed or is not compensatable.");
        }
        repository.createCommandReceipt(new CommandReceiptRow(
                tenantId, actorId, scope, key, fingerprint, "BOOKING_BATCH", batchId, now));
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.booking.batch.compensation.started",
                "BOOKING_BATCH", batchId, batch.version() + 1,
                "BookingBatchCompensationStarted", correlation(correlationId),
                Map.of("batchId", batchId, "itemIds", selected,
                        "reason", request.reason().trim()), now);
        return Set.copyOf(selected);
    }

    public void executeCompensation(
            long tenantId,
            long actorId,
            UUID batchId,
            Set<UUID> itemIds,
            String locale,
            String verifiedGroupRefs) {
        executor.compensateSelected(
                tenantId, actorId, batchId, itemIds, locale, verifiedGroupRefs);
    }

    @Transactional
    public BookingIntentPreview replan(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String locale,
            UUID batchId,
            String idempotencyKey,
            String correlationId,
            BatchReplanRequest request) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        String scope = "booking-batch:" + batchId + ":replan";
        String fingerprint = fingerprint(scope, request);
        CommandReceiptRow receipt = repository.commandReceipt(
                tenantId, actorId, scope, key).orElse(null);
        if (receipt != null) {
            requireFingerprint(receipt.requestFingerprint(), fingerprint);
            IntentRow existing = repository.intent(
                    tenantId, actorId, receipt.aggregateId())
                    .orElseThrow(() -> conflict("The replan receipt no longer resolves."));
            return intentPreview(tenantId, existing, locale);
        }
        BatchRow batch = repository.batch(tenantId, actorId, batchId)
                .orElseThrow(() -> notFound("The booking batch was not found."));
        Map<UUID, BatchItemRow> items = repository.batchItems(tenantId, batchId).stream()
                .collect(Collectors.toMap(BatchItemRow::batchItemId, Function.identity()));
        List<IntentItemRequest> replanned = new ArrayList<>();
        for (UUID itemId : new LinkedHashSet<>(request.batchItemIds())) {
            BatchItemRow batchItem = items.get(itemId);
            if (batchItem == null) throw notFound("A selected batch item was not found.");
            if (batchItem.state() == BatchItemState.RESULT_UNKNOWN
                    || batchItem.state() == BatchItemState.COMPENSATION_FAILED) {
                throw conflict("Result-unknown items must be re-queried before replanning.");
            }
            if (batchItem.state() != BatchItemState.FAILED
                    && batchItem.state() != BatchItemState.COMPENSATED) {
                throw conflict("Only failed or compensated batch items can be replanned.");
            }
            IntentItemRow source = repository.intentItem(
                    tenantId, batch.intentId(), batchItem.intentItemId())
                    .orElseThrow(() -> conflict("The source planner item no longer exists."));
            replanned.add(new IntentItemRequest(
                    source.clientItemKey() + "-retry", source.beneficiaryUserId(),
                    source.beneficiaryPersonPublicId(), source.beneficiaryDisplayName(),
                    source.delegationGrantId(), source.resourceType(), source.preferredResourceId(),
                    source.siteId(), source.floorId(), source.startsAt(), source.endsAt(),
                    source.purpose(), source.visibleToColleagues(), source.accessibleOnly(),
                    source.requiredFeatures()));
        }
        OffsetDateTime now = now();
        if (!repository.touchBatchForReplan(
                tenantId, actorId, batchId, request.expectedBatchVersion(), now)) {
            throw versionConflict("The booking batch changed or cannot be replanned.");
        }
        BookingIntentPreview created = preview(
                tenantId, actorId, actorPersonPublicId, actorDisplayName,
                verifiedGroupRefs, locale, derivedKey(scope, key), correlationId,
                new IntentPreviewRequest(replanned, request.requestedHoldTtlSeconds(),
                        request.allowAlternatives(), request.reason()));
        repository.createCommandReceipt(new CommandReceiptRow(
                tenantId, actorId, scope, key, fingerprint, "BOOKING_INTENT",
                created.intentId(), now));
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.booking.batch.replanned", "BOOKING_BATCH",
                batchId, batch.version() + 1, "BookingBatchReplanned",
                correlation(correlationId), Map.of("batchId", batchId,
                        "newIntentId", created.intentId(), "itemCount", replanned.size()), now);
        return created;
    }

    @Transactional
    public WaitlistEntry createWaitlist(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String idempotencyKey,
            String correlationId,
            WaitlistCreateRequest request) {
        requireActor(tenantId, actorId);
        validateItem(request.item());
        validateWaitlistConditions(request.conditions(), request.item());
        String key = requireIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint("waitlist-create", request);
        WaitlistRow existing = repository.waitlistByIdempotency(
                tenantId, actorId, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing.requestFingerprint(), fingerprint);
            return waitlist(existing);
        }
        OffsetDateTime now = now();
        Map<UUID, BeneficiaryGrantRow> grants = repository
                .beneficiaries(tenantId, actorId, verifiedGroupRefs, now).stream()
                .collect(Collectors.toMap(BeneficiaryGrantRow::grantId, Function.identity()));
        CanonicalBeneficiary beneficiary = canonicalBeneficiary(
                actorId, actorPersonPublicId, actorDisplayName, request.item(), grants);
        IntentItemRequest item = request.item();
        WaitlistRow row = new WaitlistRow(
                UUID.randomUUID(), tenantId, actorId, beneficiary.userId(),
                beneficiary.personPublicId(), beneficiary.displayName(), beneficiary.grantId(),
                item.resourceType(), item.preferredResourceId(), item.siteId(), item.floorId(),
                item.startsAt(), item.endsAt(), blank(item.purpose()), item.visibleToColleagues(),
                item.accessibleOnly(), normalizedFeatures(item.requiredFeatures()),
                request.autoConfirm(), request.conditions().maximumDistanceMeters(),
                request.conditions().earliestStart(), request.conditions().latestEnd(),
                request.conditions().pricingMode(), request.conditions().maximumPrice(),
                request.conditions().currency(),
                List.copyOf(new LinkedHashSet<>(request.notificationChannels())),
                WaitlistState.ACTIVE, PromotionEvaluationState.PENDING, null, null,
                false, null, key, fingerprint,
                correlation(correlationId), 1, now, now);
        repository.createWaitlist(row);
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.waitlist.created", "WAITLIST_ENTRY",
                row.entryId(), row.version(), "WaitlistEntryCreated", row.correlationId(),
                Map.of("waitlistEntryId", row.entryId(), "resourceType", row.resourceType().name(),
                        "beneficiaryUserId", row.beneficiaryUserId()), now);
        return waitlist(row);
    }

    @Transactional(readOnly = true)
    public WaitlistPage waitlists(
            long tenantId,
            long actorId,
            OffsetDateTime from,
            OffsetDateTime to,
            Long beneficiaryUserId,
            int page,
            int size) {
        requireActor(tenantId, actorId);
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(MAX_PLANNING_HORIZON) > 0) {
            throw invalid("A valid waitlist range of at most 400 days is required.");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Waitlist page must be non-negative and size must be between 1 and 100.");
        }
        long total = repository.waitlistCount(
                tenantId, actorId, from, to, beneficiaryUserId);
        List<WaitlistEntry> content = repository.waitlists(
                tenantId, actorId, from, to, beneficiaryUserId, page, size).stream()
                .map(this::waitlist).toList();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new WaitlistPage(content, page, size, total, totalPages, now());
    }

    @Transactional(readOnly = true)
    public WaitlistEntry waitlist(long tenantId, long actorId, UUID entryId) {
        requireActor(tenantId, actorId);
        return waitlist(repository.waitlist(tenantId, actorId, entryId)
                .orElseThrow(() -> notFound("The waitlist entry was not found.")));
    }

    @Transactional
    public WaitlistEntry updateWaitlist(
            long tenantId,
            long actorId,
            UUID entryId,
            String idempotencyKey,
            String correlationId,
            WaitlistUpdateRequest request) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        String scope = "waitlist-entry:" + entryId + ":update";
        String fingerprint = fingerprint(scope, request);
        CommandReceiptRow receipt = repository.commandReceipt(
                tenantId, actorId, scope, key).orElse(null);
        if (receipt != null) {
            requireFingerprint(receipt.requestFingerprint(), fingerprint);
            return waitlist(tenantId, actorId, entryId);
        }
        WaitlistRow current = repository.waitlist(tenantId, actorId, entryId)
                .orElseThrow(() -> notFound("The waitlist entry was not found."));
        validateWaitlistConditions(request.conditions(), new IntentItemRequest(
                "waitlist", current.beneficiaryUserId(), current.beneficiaryPersonPublicId(),
                current.beneficiaryDisplayName(), current.delegationGrantId(), current.resourceType(),
                current.preferredResourceId(), current.siteId(), current.floorId(), current.startsAt(),
                current.endsAt(), current.purpose(), current.visibleToColleagues(), false, List.of()));
        OffsetDateTime now = now();
        if (!repository.updateWaitlist(
                tenantId, actorId, entryId, request.expectedVersion(), request.autoConfirm(),
                request.conditions().maximumDistanceMeters(), request.conditions().earliestStart(),
                request.conditions().latestEnd(), request.conditions().pricingMode(),
                request.conditions().maximumPrice(), request.conditions().currency(),
                List.copyOf(new LinkedHashSet<>(request.notificationChannels())), now)) {
            throw versionConflict("The waitlist entry changed or can no longer be updated.");
        }
        repository.createCommandReceipt(new CommandReceiptRow(
                tenantId, actorId, scope, key, fingerprint, "WAITLIST_ENTRY", entryId, now));
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.waitlist.updated", "WAITLIST_ENTRY", entryId,
                current.version() + 1, "WaitlistEntryUpdated", correlation(correlationId),
                Map.of("waitlistEntryId", entryId, "reason", request.reason().trim()), now);
        return waitlist(repository.waitlist(tenantId, actorId, entryId)
                .orElseThrow(() -> conflict("The updated waitlist entry could not be reloaded.")));
    }

    @Transactional
    public WaitlistEntry cancelWaitlist(
            long tenantId,
            long actorId,
            UUID entryId,
            String idempotencyKey,
            String correlationId,
            WaitlistCancelRequest request) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw invalid("Waitlist cancellation requires explicit confirmation.");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        String scope = "waitlist-entry:" + entryId + ":cancel";
        String fingerprint = fingerprint(scope, request);
        CommandReceiptRow receipt = repository.commandReceipt(
                tenantId, actorId, scope, key).orElse(null);
        if (receipt != null) {
            requireFingerprint(receipt.requestFingerprint(), fingerprint);
            return waitlist(tenantId, actorId, entryId);
        }
        WaitlistRow current = repository.waitlist(tenantId, actorId, entryId)
                .orElseThrow(() -> notFound("The waitlist entry was not found."));
        OffsetDateTime now = now();
        if (!repository.cancelWaitlist(
                tenantId, actorId, entryId, request.expectedVersion(), now)) {
            throw versionConflict("The waitlist entry changed or can no longer be cancelled.");
        }
        repository.createCommandReceipt(new CommandReceiptRow(
                tenantId, actorId, scope, key, fingerprint, "WAITLIST_ENTRY", entryId, now));
        repository.auditAndOutbox(
                tenantId, actorId, "workplace.waitlist.cancelled", "WAITLIST_ENTRY", entryId,
                current.version() + 1, "WaitlistEntryCancelled", correlation(correlationId),
                Map.of("waitlistEntryId", entryId, "reason", request.reason().trim()), now);
        return waitlist(repository.waitlist(tenantId, actorId, entryId)
                .orElseThrow(() -> conflict("The cancelled waitlist entry could not be reloaded.")));
    }
}
