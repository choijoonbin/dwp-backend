package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationRepositorySupport.*;

final class WorkplaceBookingWaitlistService
        extends WorkplaceBookingOrchestrationServiceSupport {
    WorkplaceBookingWaitlistService(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceSpatialGovernanceService spatialGovernance,
            ObjectMapper objectMapper,
            Clock clock) {
        super(repository, spatialGovernance, objectMapper, clock);
    }

    WaitlistEntry create(
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

    WaitlistPage page(
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

    WaitlistEntry get(long tenantId, long actorId, UUID entryId) {
        requireActor(tenantId, actorId);
        return waitlist(repository.waitlist(tenantId, actorId, entryId)
                .orElseThrow(() -> notFound("The waitlist entry was not found.")));
    }

    WaitlistEntry update(
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
            return get(tenantId, actorId, entryId);
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

    WaitlistEntry cancel(
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
            return get(tenantId, actorId, entryId);
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
