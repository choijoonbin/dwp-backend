package com.dwp.services.approval.operations;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ApprovalOperationsService {
    private static final Set<String> OPEN_REQUEST_STATES = Set.of("SUBMITTED", "IN_REVIEW");
    private static final Set<String> OPEN_STEP_STATES = Set.of("PENDING", "IN_PROGRESS");

    private final ApprovalOperationsRepository repository;
    private final ApprovalOperationsAuthority authority;
    private final ApprovalOperationsAudit audit;
    private final Clock clock;

    @Autowired
    public ApprovalOperationsService(
            ApprovalOperationsRepository repository,
            ApprovalOperationsAuthority authority,
            ApprovalOperationsAudit audit) {
        this(repository, authority, audit, Clock.systemUTC());
    }

    ApprovalOperationsService(
            ApprovalOperationsRepository repository,
            ApprovalOperationsAuthority authority,
            ApprovalOperationsAudit audit,
            Clock clock) {
        this.repository = repository;
        this.authority = authority;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt deadLetter(
            UUID outboxId,
            Long expectedVersion,
            ApprovalOperationsDtos.Reason input,
            ApprovalStepUpHeaders headers) {
        long version = requireVersion(expectedVersion);
        String reason = reason(input == null ? null : input.reason());
        return deliveries(
                ApprovalOperationsProtocol.Route.DELIVERY_DEAD_LETTER,
                outboxId,
                List.of(new ApprovalOperationsDtos.DeliveryTarget(outboxId, version)),
                reason,
                version,
                "/v1/admin/operations/events/" + outboxId + "/dead-letter",
                input,
                headers,
                null);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt replay(
            UUID outboxId,
            Long expectedVersion,
            ApprovalOperationsDtos.Reason input,
            ApprovalStepUpHeaders headers) {
        long version = requireVersion(expectedVersion);
        String reason = reason(input == null ? null : input.reason());
        return deliveries(
                ApprovalOperationsProtocol.Route.DELIVERY_REPLAY,
                outboxId,
                List.of(new ApprovalOperationsDtos.DeliveryTarget(outboxId, version)),
                reason,
                version,
                "/v1/admin/operations/events/" + outboxId + "/replay",
                input,
                headers,
                null);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt retryBatch(
            ApprovalOperationsDtos.DeliveryBatchCommand input,
            ApprovalStepUpHeaders headers) {
        return deliveryBatch(
                ApprovalOperationsProtocol.Route.DELIVERY_BATCH_RETRY,
                input,
                "/v1/admin/operations/deliveries/retry",
                headers);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt deadLetterBatch(
            ApprovalOperationsDtos.DeliveryBatchCommand input,
            ApprovalStepUpHeaders headers) {
        return deliveryBatch(
                ApprovalOperationsProtocol.Route.DELIVERY_BATCH_DEAD_LETTER,
                input,
                "/v1/admin/operations/deliveries/dead-letter",
                headers);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt replayBatch(
            ApprovalOperationsDtos.DeliveryBatchCommand input,
            ApprovalStepUpHeaders headers) {
        return deliveryBatch(
                ApprovalOperationsProtocol.Route.DELIVERY_BATCH_REPLAY,
                input,
                "/v1/admin/operations/deliveries/replay",
                headers);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt reconcile(
            ApprovalOperationsDtos.DeliveryBatchCommand input,
            ApprovalStepUpHeaders headers) {
        return deliveryBatch(
                ApprovalOperationsProtocol.Route.DELIVERY_RECONCILE,
                input,
                "/v1/admin/operations/deliveries/reconcile",
                headers);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt reassignTask(
            UUID taskId,
            Long expectedVersion,
            ApprovalOperationsDtos.TaskReassignment input,
            ApprovalStepUpHeaders headers) {
        long version = requireVersion(expectedVersion);
        if (input == null) throw ApprovalOperationsProtocol.invalid("Task reassignment is required.");
        String reason = reason(input.reason());
        var target = new ApprovalOperationsDtos.TaskReassignmentTarget(
                taskId, version, input.assigneeUserId(), input.assigneePersonPublicId());
        return tasks(
                ApprovalOperationsProtocol.Route.TASK_REASSIGN,
                taskId,
                List.of(target),
                reason,
                version,
                "/v1/admin/operations/tasks/" + taskId + "/reassign",
                input,
                headers,
                null);
    }

    @Transactional
    public ApprovalOperationsDtos.OperationReceipt reassignTasks(
            ApprovalOperationsDtos.TaskReassignmentBatchCommand input,
            ApprovalStepUpHeaders headers) {
        requireTaskBatch(input);
        return tasks(
                ApprovalOperationsProtocol.Route.TASK_BATCH_REASSIGN,
                input.operationId(),
                input.items(),
                reason(input.reason()),
                ApprovalOperationsProtocol.BATCH_COMMAND_VERSION,
                "/v1/admin/operations/tasks/reassign",
                input,
                headers,
                input.operationId());
    }

    private ApprovalOperationsDtos.OperationReceipt deliveryBatch(
            ApprovalOperationsProtocol.Route route,
            ApprovalOperationsDtos.DeliveryBatchCommand input,
            String path,
            ApprovalStepUpHeaders headers) {
        requireDeliveryBatch(input);
        return deliveries(
                route,
                input.operationId(),
                input.items(),
                reason(input.reason()),
                ApprovalOperationsProtocol.BATCH_COMMAND_VERSION,
                path,
                input,
                headers,
                input.operationId());
    }

    private ApprovalOperationsDtos.OperationReceipt deliveries(
            ApprovalOperationsProtocol.Route route,
            UUID challengeTarget,
            List<ApprovalOperationsDtos.DeliveryTarget> rawItems,
            String reason,
            long commandVersion,
            String path,
            Object payload,
            ApprovalStepUpHeaders headers,
            UUID requestedOperationId) {
        List<ApprovalOperationsDtos.DeliveryTarget> items = validateDeliveries(rawItems);
        ApprovalOperationsAuthority.Current current = authority.requireCurrent(
                route, headers, commandVersion);
        String fingerprint = ApprovalOperationsCanonical.delivery(
                route, challengeTarget, items, reason);
        ApprovalOperationsDtos.OperationReceipt prior = repository.begin(
                current, route, requestedOperationId, headers.idempotencyKey(), fingerprint);
        if (prior != null) return prior;
        var challenge = authority.verifyStepUp(
                current, route, route.mode().equals("BATCH") ? "OPERATION_BATCH" : "OUTBOX_EVENT",
                challengeTarget, commandVersion, path, payload, headers);
        List<ApprovalOperationsRepository.DeliveryRow> seeds = repository.deliveries(
                current, ids(items), false);
        repository.lockLiveRequests(current,
                seeds.stream().map(ApprovalOperationsRepository.DeliveryRow::requestId).toList());
        Map<UUID, ApprovalOperationsDtos.DeliveryTarget> expected = deliveryTargets(items);
        Instant now = clock.instant();
        seeds.forEach(row -> validateDelivery(route.operation(), row, expected.get(row.targetId()), now));
        Map<UUID, ApprovalOperationsAuthority.DeliveryObservation> observations = new HashMap<>();
        seeds.forEach(row -> observations.put(row.targetId(), authority.observeDelivery(current, row)));
        List<ApprovalOperationsRepository.DeliveryRow> locked = repository.deliveries(
                current, ids(items), true);
        requireSameRows(seeds, locked);
        locked.forEach(row -> validateDelivery(route.operation(), row, expected.get(row.targetId()), now));

        List<ApprovalOperationsRepository.DeliveryCommit> commits = locked.stream()
                .map(row -> deliveryCommit(route.operation(), row, observations.get(row.targetId())))
                .toList();
        repository.updateDeliveries(current, route.operation(), commits);
        Instant committedAt = clock.instant();
        UUID operationId = requestedOperationId == null ? UUID.randomUUID() : requestedOperationId;
        ApprovalOperationsDtos.OperationReceipt receipt = deliveryReceipt(
                operationId, route, current, committedAt, commits);
        UUID auditEventId = audit.record(current, receipt, headers.idempotencyKey());
        repository.save(current, route, headers.idempotencyKey(), reason,
                fingerprint, receipt, auditEventId, deliveryItems(commits));
        authority.consume(challenge);
        return receipt;
    }

    private ApprovalOperationsDtos.OperationReceipt tasks(
            ApprovalOperationsProtocol.Route route,
            UUID challengeTarget,
            List<ApprovalOperationsDtos.TaskReassignmentTarget> rawItems,
            String reason,
            long commandVersion,
            String path,
            Object payload,
            ApprovalStepUpHeaders headers,
            UUID requestedOperationId) {
        List<ApprovalOperationsDtos.TaskReassignmentTarget> items = validateTasks(rawItems);
        ApprovalOperationsAuthority.Current current = authority.requireCurrent(
                route, headers, commandVersion);
        String fingerprint = ApprovalOperationsCanonical.tasks(route, challengeTarget, items, reason);
        ApprovalOperationsDtos.OperationReceipt prior = repository.begin(
                current, route, requestedOperationId, headers.idempotencyKey(), fingerprint);
        if (prior != null) return prior;
        var challenge = authority.verifyStepUp(
                current, route, route.mode().equals("BATCH") ? "OPERATION_BATCH" : "APPROVAL_TASK",
                challengeTarget, commandVersion, path, payload, headers);
        List<ApprovalOperationsRepository.TaskRow> seeds = repository.tasks(
                current, taskIds(items), false);
        repository.lockLiveRequests(current,
                seeds.stream().map(ApprovalOperationsRepository.TaskRow::requestId).toList());
        Map<UUID, ApprovalOperationsDtos.TaskReassignmentTarget> expected = taskTargets(items);
        seeds.forEach(row -> validateTask(row, expected.get(row.targetId())));
        Map<UUID, ApprovalOperationsAuthority.TaskObservation> observations = new HashMap<>();
        seeds.forEach(row -> {
            var target = expected.get(row.targetId());
            observations.put(row.targetId(), authority.observeTask(
                    current, row, target.assigneeUserId(), target.assigneePersonPublicId()));
        });
        List<ApprovalOperationsRepository.TaskRow> locked = repository.tasks(
                current, taskIds(items), true);
        requireSameRows(seeds, locked);
        locked.forEach(row -> validateTask(row, expected.get(row.targetId())));

        List<ApprovalOperationsRepository.TaskCommit> commits = locked.stream()
                .map(row -> taskCommit(row, observations.get(row.targetId())))
                .toList();
        repository.updateTasks(current, commits);
        Instant committedAt = clock.instant();
        UUID operationId = requestedOperationId == null ? UUID.randomUUID() : requestedOperationId;
        ApprovalOperationsDtos.OperationReceipt receipt = taskReceipt(
                operationId, route, current, committedAt, commits);
        repository.recordTaskEvents(
                current, headers.idempotencyKey(), reason, committedAt, commits);
        UUID auditEventId = audit.record(current, receipt, headers.idempotencyKey());
        repository.save(current, route, headers.idempotencyKey(), reason,
                fingerprint, receipt, auditEventId, taskItems(commits));
        authority.consume(challenge);
        return receipt;
    }

    private ApprovalOperationsRepository.DeliveryCommit deliveryCommit(
            ApprovalOperationsProtocol.Operation operation,
            ApprovalOperationsRepository.DeliveryRow row,
            ApprovalOperationsAuthority.DeliveryObservation observation) {
        String after = operation.statusAfter(row.status());
        return new ApprovalOperationsRepository.DeliveryCommit(
                row, observation, after,
                ApprovalOperationsCanonical.target(
                        row.targetId(), row.eventId(), row.requestId(), row.version(), row.status(),
                        after, row.assignmentRevision(), observation.brokerRevision()));
    }

    private ApprovalOperationsRepository.TaskCommit taskCommit(
            ApprovalOperationsRepository.TaskRow row,
            ApprovalOperationsAuthority.TaskObservation observation) {
        return new ApprovalOperationsRepository.TaskCommit(
                row, observation,
                ApprovalOperationsCanonical.target(
                        row.targetId(), row.requestId(), row.version(), row.status(),
                        observation.authorityUserId(), observation.authorityPersonPublicId(),
                        observation.authorityRole(), observation.brokerRevision()));
    }

    private void validateDelivery(
            ApprovalOperationsProtocol.Operation operation,
            ApprovalOperationsRepository.DeliveryRow row,
            ApprovalOperationsDtos.DeliveryTarget expected,
            Instant now) {
        if (expected == null || row.version() != expected.expectedVersion()) {
            throw ApprovalOperationsProtocol.conflict("A delivery version changed.");
        }
        if (!operation.accepts(row.status())) {
            throw ApprovalOperationsProtocol.rejected(
                    "A delivery is not eligible for " + operation.name() + ".");
        }
        if (operation == ApprovalOperationsProtocol.Operation.DELIVERY_RECONCILE
                && "SENDING".equals(row.status())
                && row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
            throw ApprovalOperationsProtocol.rejected(
                    "An actively leased delivery cannot be reconciled.");
        }
    }

    private void validateTask(
            ApprovalOperationsRepository.TaskRow row,
            ApprovalOperationsDtos.TaskReassignmentTarget expected) {
        if (expected == null || row.version() != expected.expectedVersion()) {
            throw ApprovalOperationsProtocol.conflict("An Approval task version changed.");
        }
        if (!ApprovalOperationsProtocol.Operation.TASK_REASSIGN.accepts(row.status())
                || !OPEN_REQUEST_STATES.contains(row.requestStatus())
                || !OPEN_STEP_STATES.contains(row.stepStatus())
                || row.stepCandidateRole() == null
                || !row.stepCandidateRole().matches("[A-Z][A-Z0-9_]{1,79}")) {
            throw ApprovalOperationsProtocol.rejected(
                    "An Approval task is not currently eligible for reassignment.");
        }
    }

    private List<ApprovalOperationsDtos.DeliveryTarget> validateDeliveries(
            List<ApprovalOperationsDtos.DeliveryTarget> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > ApprovalOperationsProtocol.MAXIMUM_BATCH_SIZE) {
            throw ApprovalOperationsProtocol.invalid("Delivery operations require 1 to 50 targets.");
        }
        for (var item : raw) {
            if (item == null || item.targetId() == null || item.expectedVersion() < 0) {
                throw ApprovalOperationsProtocol.invalid("A delivery target is invalid.");
            }
        }
        requireUnique(raw.stream().map(ApprovalOperationsDtos.DeliveryTarget::targetId).toList());
        return ApprovalOperationsCanonical.sortedDeliveries(List.copyOf(raw));
    }

    private List<ApprovalOperationsDtos.TaskReassignmentTarget> validateTasks(
            List<ApprovalOperationsDtos.TaskReassignmentTarget> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > ApprovalOperationsProtocol.MAXIMUM_BATCH_SIZE) {
            throw ApprovalOperationsProtocol.invalid("Task operations require 1 to 50 targets.");
        }
        for (var item : raw) {
            if (item == null || item.targetId() == null || item.expectedVersion() < 0
                    || item.assigneeUserId() <= 0 || item.assigneePersonPublicId() == null) {
                throw ApprovalOperationsProtocol.invalid("A task reassignment target is invalid.");
            }
        }
        requireUnique(raw.stream().map(ApprovalOperationsDtos.TaskReassignmentTarget::targetId).toList());
        return ApprovalOperationsCanonical.sortedTasks(List.copyOf(raw));
    }

    private void requireDeliveryBatch(ApprovalOperationsDtos.DeliveryBatchCommand input) {
        if (input == null || input.operationId() == null) {
            throw ApprovalOperationsProtocol.invalid("A delivery operation id is required.");
        }
    }

    private void requireTaskBatch(ApprovalOperationsDtos.TaskReassignmentBatchCommand input) {
        if (input == null || input.operationId() == null) {
            throw ApprovalOperationsProtocol.invalid("A task operation id is required.");
        }
    }

    private void requireUnique(List<UUID> ids) {
        if (new HashSet<>(ids).size() != ids.size()) {
            throw ApprovalOperationsProtocol.invalid("Operation target ids must be unique.");
        }
    }

    private long requireVersion(Long version) {
        if (version == null || version < 0 || version > 9_007_199_254_740_991L) {
            throw ApprovalOperationsProtocol.conflict("A current expected object version is required.");
        }
        return version;
    }

    private String reason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > 1000) {
            throw ApprovalOperationsProtocol.invalid("An operation reason is required.");
        }
        return normalized;
    }

    private List<UUID> ids(List<ApprovalOperationsDtos.DeliveryTarget> items) {
        return items.stream().map(ApprovalOperationsDtos.DeliveryTarget::targetId).toList();
    }

    private List<UUID> taskIds(List<ApprovalOperationsDtos.TaskReassignmentTarget> items) {
        return items.stream().map(ApprovalOperationsDtos.TaskReassignmentTarget::targetId).toList();
    }

    private Map<UUID, ApprovalOperationsDtos.DeliveryTarget> deliveryTargets(
            List<ApprovalOperationsDtos.DeliveryTarget> items) {
        Map<UUID, ApprovalOperationsDtos.DeliveryTarget> result = new HashMap<>();
        items.forEach(item -> result.put(item.targetId(), item));
        return result;
    }

    private Map<UUID, ApprovalOperationsDtos.TaskReassignmentTarget> taskTargets(
            List<ApprovalOperationsDtos.TaskReassignmentTarget> items) {
        Map<UUID, ApprovalOperationsDtos.TaskReassignmentTarget> result = new HashMap<>();
        items.forEach(item -> result.put(item.targetId(), item));
        return result;
    }

    private void requireSameRows(List<?> seeds, List<?> locked) {
        if (!seeds.equals(locked)) {
            throw ApprovalOperationsProtocol.conflict(
                    "An operation target changed during current-authority validation.");
        }
    }

    private ApprovalOperationsDtos.OperationReceipt deliveryReceipt(
            UUID operationId,
            ApprovalOperationsProtocol.Route route,
            ApprovalOperationsAuthority.Current current,
            Instant committedAt,
            List<ApprovalOperationsRepository.DeliveryCommit> commits) {
        List<ApprovalOperationsDtos.ItemReceipt> items = commits.stream().map(commit ->
                new ApprovalOperationsDtos.ItemReceipt(
                        commit.row().targetId(), commit.row().requestId(), commit.row().version(),
                        commit.row().version() + 1, commit.row().status(), commit.statusAfter(), null))
                .toList();
        return receipt(operationId, route, current, committedAt, items);
    }

    private ApprovalOperationsDtos.OperationReceipt taskReceipt(
            UUID operationId,
            ApprovalOperationsProtocol.Route route,
            ApprovalOperationsAuthority.Current current,
            Instant committedAt,
            List<ApprovalOperationsRepository.TaskCommit> commits) {
        List<ApprovalOperationsDtos.ItemReceipt> items = commits.stream().map(commit ->
                new ApprovalOperationsDtos.ItemReceipt(
                        commit.row().targetId(), commit.row().requestId(), commit.row().version(),
                        commit.row().version() + 1, commit.row().status(), "PENDING",
                        commit.observation().authorityUserId()))
                .toList();
        return receipt(operationId, route, current, committedAt, items);
    }

    private ApprovalOperationsDtos.OperationReceipt receipt(
            UUID operationId,
            ApprovalOperationsProtocol.Route route,
            ApprovalOperationsAuthority.Current current,
            Instant committedAt,
            List<ApprovalOperationsDtos.ItemReceipt> items) {
        return new ApprovalOperationsDtos.OperationReceipt(
                operationId, route.operation().name(), route.mode(), current.actor().userId(),
                current.scope().resourceSetKey(), items.size(), committedAt, List.copyOf(items));
    }

    private List<ApprovalOperationsRepository.ItemCommit> deliveryItems(
            List<ApprovalOperationsRepository.DeliveryCommit> commits) {
        return commits.stream().map(commit -> new ApprovalOperationsRepository.ItemCommit(
                "OUTBOX_EVENT", commit.row().targetId(), commit.row().requestId(),
                commit.row().version(), commit.row().status(), commit.statusAfter(),
                commit.observation().authorityUserId(),
                commit.observation().authorityPersonPublicId(), null,
                commit.observation().brokerRevision(), commit.observation().observedAt(),
                commit.targetFingerprint())).toList();
    }

    private List<ApprovalOperationsRepository.ItemCommit> taskItems(
            List<ApprovalOperationsRepository.TaskCommit> commits) {
        return commits.stream().map(commit -> new ApprovalOperationsRepository.ItemCommit(
                "APPROVAL_TASK", commit.row().targetId(), commit.row().requestId(),
                commit.row().version(), commit.row().status(), "PENDING",
                commit.observation().authorityUserId(),
                commit.observation().authorityPersonPublicId(),
                commit.observation().authorityRole(), commit.observation().brokerRevision(),
                commit.observation().observedAt(), commit.targetFingerprint())).toList();
    }
}
