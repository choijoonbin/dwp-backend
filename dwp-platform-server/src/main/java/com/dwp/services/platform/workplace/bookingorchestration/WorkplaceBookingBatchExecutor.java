package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.calendar.RoomService;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceOperationsService;
import com.dwp.services.platform.workplace.WorkplaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.EventVisibility;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;

@Component
public class WorkplaceBookingBatchExecutor {
    static final Duration EXECUTION_LEASE = Duration.ofSeconds(30);
    private static final String UNKNOWN_MESSAGE =
            "Owner result is unconfirmed. Requery the batch before retrying.";
    private static final String COMPENSATION_UNKNOWN_MESSAGE =
            "Compensation result is unknown. Requery the batch.";

    private final WorkplaceBookingOrchestrationRepository repository;
    private final BookingCreator bookingCreator;
    private final WorkplaceService workplace;
    private final RoomService rooms;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public WorkplaceBookingBatchExecutor(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceOperationsService workplaceOperations,
            WorkplaceService workplace,
            RoomService rooms,
            PlatformTransactionManager transactionManager) {
        this(repository, workplaceOperations, workplace, rooms,
                transactionManager, Clock.systemUTC());
    }

    WorkplaceBookingBatchExecutor(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceOperationsService workplaceOperations,
            WorkplaceService workplace,
            RoomService rooms,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(repository, workplaceOperations::createBooking, workplace, rooms,
                transactionManager, clock);
    }

    /** Compatibility seam for focused owner-failure tests; production uses the operations service. */
    public WorkplaceBookingBatchExecutor(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceService workplace,
            RoomService rooms,
            PlatformTransactionManager transactionManager) {
        this(repository, directCreator(workplace), workplace, rooms,
                transactionManager, Clock.systemUTC());
    }

    WorkplaceBookingBatchExecutor(
            WorkplaceBookingOrchestrationRepository repository,
            WorkplaceService workplace,
            RoomService rooms,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(repository, directCreator(workplace), workplace, rooms,
                transactionManager, clock);
    }

    private WorkplaceBookingBatchExecutor(
            WorkplaceBookingOrchestrationRepository repository,
            BookingCreator bookingCreator,
            WorkplaceService workplace,
            RoomService rooms,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.bookingCreator = bookingCreator;
        this.workplace = workplace;
        this.rooms = rooms;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void execute(long tenantId, UUID batchId, String locale, String verifiedGroupRefs) {
        UUID token = UUID.randomUUID();
        WorkplaceBookingOrchestrationRepository.BatchRow batch = transaction.execute(status -> {
            OffsetDateTime now = now();
            var claimed = repository.claimBatchExecution(
                    tenantId, batchId, token, now, leaseExpiresAt(now),
                    now.minus(EXECUTION_LEASE)).orElse(null);
            if (claimed != null) {
                repository.recoverInterruptedBatchItems(tenantId, batchId, now);
            }
            return claimed;
        });
        if (batch == null) return;

        if (!executePendingItems(batch, token, locale, verifiedGroupRefs)) return;
        if (batch.failurePolicy() == FailurePolicy.COMPENSATE_ALL
                && hasCreationFailure(batch.tenantId(), batch.batchId())) {
            if (!compensateSucceededItems(
                    batch, token, null, locale, verifiedGroupRefs)) return;
        }
        finalizeBatch(batch.tenantId(), batch.batchId(), token);
    }

    public void compensateSelected(
            long tenantId,
            long actorId,
            UUID batchId,
            Set<UUID> selectedItemIds,
            String locale,
            String verifiedGroupRefs) {
        UUID token = UUID.randomUUID();
        WorkplaceBookingOrchestrationRepository.BatchRow batch = transaction.execute(status -> {
            OffsetDateTime now = now();
            var claimed = repository.claimCompensationExecution(
                    tenantId, actorId, batchId, token, now, leaseExpiresAt(now)).orElse(null);
            if (claimed != null) {
                repository.recoverInterruptedBatchItems(tenantId, batchId, now);
            }
            return claimed;
        });
        if (batch == null) return;

        if (!compensateSucceededItems(
                batch, token, selectedItemIds, locale, verifiedGroupRefs)) return;
        finalizeBatch(batch.tenantId(), batch.batchId(), token);
    }

    private boolean executePendingItems(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            UUID token,
            String locale,
            String verifiedGroupRefs) {
        for (var snapshot : repository.batchItems(batch.tenantId(), batch.batchId())) {
            try {
                processItem(batch, token, snapshot.batchItemId(), locale, verifiedGroupRefs);
            } catch (ClaimLostException exception) {
                return false;
            } catch (BaseException exception) {
                if (!recordCreationFailure(batch, token, snapshot.batchItemId(),
                        BatchItemState.FAILED, exception.getErrorCode().getCode(),
                        exception.getMessage(), false)) return false;
            } catch (DataIntegrityViolationException exception) {
                if (!recordCreationFailure(batch, token, snapshot.batchItemId(),
                        BatchItemState.FAILED, "RESOURCE_CONFLICT",
                        "The held resource became unavailable before confirmation.", false)) {
                    return false;
                }
            } catch (RuntimeException exception) {
                if (!recordCreationFailure(batch, token, snapshot.batchItemId(),
                        BatchItemState.RESULT_UNKNOWN, "RESULT_UNKNOWN",
                        UNKNOWN_MESSAGE, true)) return false;
            }
        }
        return true;
    }

    private void processItem(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            UUID token,
            UUID itemId,
            String locale,
            String verifiedGroupRefs) {
        transaction.executeWithoutResult(status -> {
            renewOrLose(batch.tenantId(), batch.batchId(), token);
            var item = item(batch.tenantId(), batch.batchId(), itemId);
            if (terminal(item.state())) return;
            if (item.state() != BatchItemState.PENDING
                    || !repository.beginBatchItem(
                            batch.tenantId(), item.batchItemId(), item.version(), now())) {
                throw new ClaimLostException();
            }
            validateItem(batch, item, verifiedGroupRefs);
            String groups = groups(batch, item, verifiedGroupRefs);
            repository.setHoldContext(item.holdId());
            OwnerResult result = item.authority() == BookingAuthority.CALENDAR
                    ? createRoom(batch, item, locale, groups)
                    : createWorkplace(batch, item, locale, groups);
            OffsetDateTime completedAt = now();
            if (!repository.completeBatchItemIfState(
                    batch.tenantId(), item.batchItemId(), BatchItemState.PROCESSING,
                    BatchItemState.SUCCEEDED, result.referenceId(), result.version(),
                    null, null, true, false, completedAt)) {
                throw new ClaimLostException();
            }
            repository.consumeHold(batch.tenantId(), item.holdId(), completedAt);
            repository.auditAndOutbox(
                    batch.tenantId(), batch.actorUserId(),
                    "workplace.booking.batch.item.resolved", "BOOKING_BATCH_ITEM",
                    item.batchItemId(), item.version() + 2, "BookingBatchItemResolved",
                    batch.correlationId(), new ItemResultAudit(
                            batch.batchId(), item.intentItemId(), item.holdId(), item.authority(),
                            BatchItemState.SUCCEEDED, result.referenceId(), null), completedAt);
        });
    }

    private void validateItem(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            WorkplaceBookingOrchestrationRepository.BatchItemRow item,
            String verifiedGroupRefs) {
        OffsetDateTime now = now();
        if (!item.holdExpiresAt().isAfter(now)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The reservation hold expired before confirmation.");
        }
        if (!repository.delegationValid(
                batch.tenantId(), item.delegationGrantId(), batch.actorUserId(),
                item.beneficiaryUserId(), item.resourceType(),
                groups(batch, item, verifiedGroupRefs), now)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The booking beneficiary authorization is no longer valid.");
        }
    }

    private OwnerResult createWorkplace(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            WorkplaceBookingOrchestrationRepository.BatchItemRow item,
            String locale,
            String groups) {
        WorkplaceDtos.Booking booking = bookingCreator.create(
                batch.tenantId(), item.beneficiaryUserId(), item.beneficiaryPersonPublicId(),
                item.beneficiaryDisplayName(), locale, batch.correlationId(),
                ownerCommandKey(item), groups, new WorkplaceDtos.BookingRequest(
                        item.resourceId(), item.startsAt(), item.endsAt(), item.purpose(),
                        item.visibleToColleagues()));
        return new OwnerResult(booking.bookingId(), booking.version());
    }

    private OwnerResult createRoom(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            WorkplaceBookingOrchestrationRepository.BatchItemRow item,
            String locale,
            String groups) {
        if (item.calendarResourceId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The selected room has no Calendar authority mapping.");
        }
        String title = item.purpose() == null || item.purpose().isBlank()
                ? "Workplace reservation" : item.purpose().trim();
        UUID commandId = UUID.nameUUIDFromBytes(
                ownerCommandKey(item).getBytes(StandardCharsets.UTF_8));
        CalendarDtos.EventSummary event = rooms.createRoomBooking(
                batch.tenantId(), item.beneficiaryUserId(), item.beneficiaryPersonPublicId(),
                item.beneficiaryDisplayName(), locale, batch.correlationId(), groups,
                new CalendarDtos.CreateEventRequest(
                        title, null, EventType.MEETING, item.startsAt(), item.endsAt(),
                        item.timeZone(), false, item.resourceName(), null,
                        EventVisibility.PRIVATE, RecurrencePattern.NONE, 1, null,
                        false, List.of(), item.calendarResourceId(), commandId));
        return new OwnerResult(event.eventId(), event.version());
    }

    private boolean recordCreationFailure(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            UUID token,
            UUID itemId,
            BatchItemState target,
            String code,
            String message,
            boolean requeryRequired) {
        try {
            transaction.executeWithoutResult(status -> {
                renewOrLose(batch.tenantId(), batch.batchId(), token);
                var current = item(batch.tenantId(), batch.batchId(), itemId);
                if (terminal(current.state())) return;
                if (current.state() != BatchItemState.PENDING
                        || !repository.completeBatchItemIfState(
                                batch.tenantId(), itemId, BatchItemState.PENDING, target,
                                null, null, code, message, false, requeryRequired, now())) {
                    throw new ClaimLostException();
                }
                repository.auditAndOutbox(
                        batch.tenantId(), batch.actorUserId(),
                        target == BatchItemState.RESULT_UNKNOWN
                                ? "workplace.booking.batch.item.unknown"
                                : "workplace.booking.batch.item.resolved",
                        "BOOKING_BATCH_ITEM", itemId, current.version() + 1,
                        target == BatchItemState.RESULT_UNKNOWN
                                ? "BookingBatchItemResultUnknown" : "BookingBatchItemResolved",
                        batch.correlationId(), new ItemResultAudit(
                                batch.batchId(), current.intentItemId(), current.holdId(),
                                current.authority(), target, null, code), now());
            });
            return true;
        } catch (ClaimLostException exception) {
            return false;
        }
    }

    private boolean compensateSucceededItems(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            UUID token,
            Set<UUID> selectedItemIds,
            String locale,
            String verifiedGroupRefs) {
        for (var snapshot : repository.batchItems(batch.tenantId(), batch.batchId())) {
            if (selectedItemIds != null && !selectedItemIds.contains(snapshot.batchItemId())) {
                continue;
            }
            try {
                compensateItem(
                        batch, token, snapshot.batchItemId(), locale, verifiedGroupRefs);
            } catch (ClaimLostException exception) {
                return false;
            } catch (BaseException | DataIntegrityViolationException exception) {
                if (!recordCompensationFailure(batch, token, snapshot.batchItemId(),
                        "COMPENSATION_FAILED",
                        "The owner could not confirm compensation. Requery the batch.")) {
                    return false;
                }
            } catch (RuntimeException exception) {
                if (!recordCompensationFailure(batch, token, snapshot.batchItemId(),
                        "RESULT_UNKNOWN", COMPENSATION_UNKNOWN_MESSAGE)) return false;
            }
        }
        return true;
    }

    private void compensateItem(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            UUID token,
            UUID itemId,
            String locale,
            String verifiedGroupRefs) {
        transaction.executeWithoutResult(status -> {
            renewOrLose(batch.tenantId(), batch.batchId(), token);
            var item = item(batch.tenantId(), batch.batchId(), itemId);
            if (item.state() == BatchItemState.COMPENSATED
                    || item.state() == BatchItemState.COMPENSATION_FAILED) return;
            if (item.state() != BatchItemState.SUCCEEDED) return;
            OffsetDateTime now = now();
            if (!repository.markCompensationPending(
                    batch.tenantId(), item.batchItemId(), now)) {
                throw new ClaimLostException();
            }
            String groups = groups(batch, item, verifiedGroupRefs);
            if (item.authority() == BookingAuthority.CALENDAR) {
                rooms.cancelRoomBooking(
                        batch.tenantId(), item.beneficiaryUserId(),
                        item.beneficiaryPersonPublicId(), item.ownerReferenceId(), locale,
                        batch.correlationId(), groups,
                        new CalendarDtos.VersionRequest(item.ownerVersion()));
            } else {
                workplace.cancelBooking(
                        batch.tenantId(), item.beneficiaryUserId(), item.ownerReferenceId(),
                        locale, batch.correlationId(), groups, compensationCommandKey(item),
                        new WorkplaceDtos.VersionRequest(item.ownerVersion()));
            }
            if (!repository.completeBatchItemIfState(
                    batch.tenantId(), item.batchItemId(),
                    BatchItemState.COMPENSATION_PENDING, BatchItemState.COMPENSATED,
                    item.ownerReferenceId(), item.ownerVersion() + 1, null, null,
                    false, false, now)) {
                throw new ClaimLostException();
            }
            repository.auditAndOutbox(
                    batch.tenantId(), batch.actorUserId(),
                    "workplace.booking.batch.item.compensated", "BOOKING_BATCH_ITEM",
                    item.batchItemId(), item.version() + 2, "BookingBatchItemCompensated",
                    batch.correlationId(), new ItemResultAudit(
                            batch.batchId(), item.intentItemId(), item.holdId(), item.authority(),
                            BatchItemState.COMPENSATED, item.ownerReferenceId(), null), now);
        });
    }

    private boolean recordCompensationFailure(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            UUID token,
            UUID itemId,
            String code,
            String message) {
        try {
            transaction.executeWithoutResult(status -> {
                renewOrLose(batch.tenantId(), batch.batchId(), token);
                var current = item(batch.tenantId(), batch.batchId(), itemId);
                if (current.state() == BatchItemState.COMPENSATED
                        || current.state() == BatchItemState.COMPENSATION_FAILED) return;
                if (current.state() != BatchItemState.SUCCEEDED
                        || !repository.completeBatchItemIfState(
                                batch.tenantId(), itemId, BatchItemState.SUCCEEDED,
                                BatchItemState.COMPENSATION_FAILED,
                                current.ownerReferenceId(), current.ownerVersion(), code, message,
                                false, true, now())) {
                    throw new ClaimLostException();
                }
            });
            return true;
        } catch (ClaimLostException exception) {
            return false;
        }
    }

    private void finalizeBatch(long tenantId, UUID batchId, UUID token) {
        try {
            transaction.executeWithoutResult(status -> {
                renewOrLose(tenantId, batchId, token);
                var batch = repository.batch(tenantId, batchId)
                        .orElseThrow(() -> new ClaimLostException());
                List<WorkplaceBookingOrchestrationRepository.BatchItemRow> items =
                        repository.batchItems(tenantId, batchId);
                if (items.stream().anyMatch(item -> !terminal(item.state()))) return;
                BatchState state = finalState(items);
                OffsetDateTime now = now();
                if (!repository.finishBatchExecution(
                        tenantId, batchId, token, state, now)) {
                    throw new ClaimLostException();
                }
                if (state != BatchState.RESULT_UNKNOWN) {
                    repository.completeIntent(tenantId, batch.intentId(), now);
                }
                repository.offerByAcceptedBatch(tenantId, batchId).ifPresent(offer -> {
                    repository.reconcileOfferBatch(offer, state, now);
                    repository.auditAndOutbox(
                            tenantId, batch.actorUserId(),
                            "workplace.waitlist.offer.batch.reconciled", "ALTERNATIVE_OFFER",
                            offer.offerId(), offer.version() + 1,
                            "AlternativeOfferBatchReconciled", batch.correlationId(),
                            new OfferBatchResultAudit(
                                    offer.offerId(), offer.waitlistEntryId(), batchId, state), now);
                });
                repository.auditAndOutbox(
                        tenantId, batch.actorUserId(), "workplace.booking.batch.completed",
                        "BOOKING_BATCH", batchId, batch.version() + 1,
                        "BookingBatchCompleted", batch.correlationId(),
                        new BatchResultAudit(
                                state, items.size(), count(items, BatchItemState.SUCCEEDED),
                                count(items, BatchItemState.FAILED),
                                items.stream().filter(
                                        WorkplaceBookingOrchestrationRepository.BatchItemRow
                                                ::requeryRequired).count()), now);
            });
        } catch (ClaimLostException ignored) {
            // A current claim owner is responsible for completion.
        }
    }

    private boolean hasCreationFailure(long tenantId, UUID batchId) {
        return repository.batchItems(tenantId, batchId).stream().anyMatch(item ->
                item.state() == BatchItemState.FAILED
                        || item.state() == BatchItemState.RESULT_UNKNOWN);
    }

    private BatchState finalState(
            List<WorkplaceBookingOrchestrationRepository.BatchItemRow> items) {
        if (items.stream().anyMatch(item -> item.state() == BatchItemState.RESULT_UNKNOWN
                || item.state() == BatchItemState.COMPENSATION_FAILED)) {
            return BatchState.RESULT_UNKNOWN;
        }
        if (items.stream().allMatch(item -> item.state() == BatchItemState.SUCCEEDED)) {
            return BatchState.SUCCEEDED;
        }
        if (items.stream().anyMatch(item -> item.state() == BatchItemState.COMPENSATED)
                && items.stream().noneMatch(item -> item.state() == BatchItemState.SUCCEEDED)) {
            return BatchState.COMPENSATED;
        }
        if (items.stream().noneMatch(item -> item.state() == BatchItemState.SUCCEEDED)) {
            return BatchState.FAILED;
        }
        return BatchState.PARTIAL;
    }

    private WorkplaceBookingOrchestrationRepository.BatchItemRow item(
            long tenantId, UUID batchId, UUID itemId) {
        return repository.batchItems(tenantId, batchId).stream()
                .filter(item -> item.batchItemId().equals(itemId))
                .findFirst().orElseThrow(ClaimLostException::new);
    }

    private void renewOrLose(long tenantId, UUID batchId, UUID token) {
        OffsetDateTime now = now();
        if (!repository.renewBatchExecution(
                tenantId, batchId, token, leaseExpiresAt(now), now)) {
            throw new ClaimLostException();
        }
    }

    private static String groups(
            WorkplaceBookingOrchestrationRepository.BatchRow batch,
            WorkplaceBookingOrchestrationRepository.BatchItemRow item,
            String verifiedGroupRefs) {
        return batch.actorUserId() == item.beneficiaryUserId()
                ? verifiedGroupRefs : null;
    }

    static String ownerCommandKey(
            WorkplaceBookingOrchestrationRepository.BatchItemRow item) {
        return "booking-batch-item:" + item.batchItemId();
    }

    static String compensationCommandKey(
            WorkplaceBookingOrchestrationRepository.BatchItemRow item) {
        return "booking-batch-compensation:" + item.batchItemId();
    }

    private static boolean terminal(BatchItemState state) {
        return state == BatchItemState.SUCCEEDED
                || state == BatchItemState.FAILED
                || state == BatchItemState.RESULT_UNKNOWN
                || state == BatchItemState.COMPENSATED
                || state == BatchItemState.COMPENSATION_FAILED;
    }

    private static long count(
            List<WorkplaceBookingOrchestrationRepository.BatchItemRow> items,
            BatchItemState state) {
        return items.stream().filter(item -> item.state() == state).count();
    }

    private OffsetDateTime leaseExpiresAt(OffsetDateTime now) {
        return now.plus(EXECUTION_LEASE);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record OwnerResult(UUID referenceId, long version) { }
    @FunctionalInterface
    private interface BookingCreator {
        WorkplaceDtos.Booking create(
                Long tenantId, Long userId, UUID personPublicId, String displayName,
                String locale, String correlationId, String idempotencyKey,
                String verifiedGroupRefs, WorkplaceDtos.BookingRequest request);
    }

    private static BookingCreator directCreator(WorkplaceService workplace) {
        return (tenantId, userId, personPublicId, displayName, locale, correlationId,
                idempotencyKey, verifiedGroupRefs, request) -> workplace.createBooking(
                tenantId, userId, personPublicId, displayName, locale, correlationId,
                verifiedGroupRefs, request);
    }
    private record ItemResultAudit(
            UUID batchId, UUID intentItemId, UUID holdId, BookingAuthority authority,
            BatchItemState state, UUID ownerReferenceId, String errorCode) { }
    private record BatchResultAudit(
            BatchState state, int itemCount, long succeeded, long failed, long resultUnknown) { }
    private record OfferBatchResultAudit(
            UUID offerId, UUID waitlistEntryId, UUID batchId, BatchState batchState) { }
    private static final class ClaimLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
