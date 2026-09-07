package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentRepository.*;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentSourceAuthority.*;

/** Work owns the confirmed task; the source owner alone authorizes promotion and reassignment. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class WorkAssignmentService {
    private final WorkAssignmentRepository repository;
    private final PersonalWorkAccess access;
    private final WorkAssignmentSourceAuthority sourceAuthority;
    private final PlatformAuditService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private static final Duration SOURCE_VALIDATION_WINDOW = Duration.ofSeconds(10);

    @Autowired
    public WorkAssignmentService(WorkAssignmentRepository repository, PersonalWorkAccess access,
                                 WorkAssignmentSourceAuthority sourceAuthority,
                                 PlatformAuditService audit, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this(repository, access, sourceAuthority, audit, mapper, transactionManager, Clock.systemUTC());
    }

    public WorkAssignmentService(WorkAssignmentRepository repository, PersonalWorkAccess access,
                                 WorkAssignmentSourceAuthority sourceAuthority,
                                 PlatformAuditService audit, ObjectMapper mapper,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.sourceAuthority = sourceAuthority;
        this.audit = audit;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.clock = clock;
    }

    public TaskPage list(AccessContext context, Scope scope, int page, int size) {
        access.read(context);
        if (scope == null || page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Invalid scope or page.");
        long count = repository.count(context, scope);
        List<Task> items = repository.list(context, scope, page, size).stream()
                .map(row -> response(context, row, false)).toList();
        return new TaskPage(items, page, size, count, ((long) page + 1) * size < count);
    }

    public Task get(AccessContext context, UUID assignmentId) {
        access.read(context);
        return response(context, requireAssignment(context, assignmentId, false));
    }

    public Task findBySource(AccessContext context, SourceIdentity source) {
        access.read(context);
        requireSource(source);
        return response(context, repository.findBySource(context, source)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND)));
    }

    public MutationResult create(AccessContext context, UUID commandId, String correlationId, CreateRequest request) {
        access.write(context);
        if (request == null) throw invalid("A confirmed source is required.");
        requireSource(request.source());
        requireNonNegative(request.expectedSourceVersion(), "source version");
        String target = request.source().sourceSystem() + ":" + request.source().candidateId();
        String fingerprint = requireCommandFingerprint(commandId, request);
        Optional<AppliedCommand> replay = replay(context, commandId, "CREATE", target, fingerprint, false);
        if (replay.isPresent()) return result(context, replay.get());
        // No database transaction or lock is held while the owner service validates the source.
        Instant validationStartedAt = clock.instant();
        ConfirmedTask confirmed = sourceAuthority.confirmCreate(context, request.source(), request.expectedSourceVersion());
        requireConfirmed(confirmed, request);
        return execute(context, commandId, "CREATE", target, request, () -> {
            repository.lockCandidate(context, request.source());
            if (repository.candidateExists(context, request.source())) throw conflict();
            requireFreshSourceValidation(validationStartedAt);
            AssignmentRow row = repository.insert(context, UUID.randomUUID(), confirmed);
            recordEvent(context, correlationId, "CREATE", null, null, row);
            return row;
        });
    }

    public MutationResult command(AccessContext context, UUID assignmentId, UUID commandId,
                                  String correlationId, Action action, VersionCommand request) {
        access.write(context);
        requireId(assignmentId);
        if (action == null || action == Action.REASSIGN || request == null) throw invalid("Invalid assignment command.");
        requireVersionInput(request.version(), request.assignmentRevision());
        requireReason(request.reasonCode(), action == Action.DECLINE || action == Action.CANCEL);
        return execute(context, commandId, action.name(), assignmentId.toString(), request, () -> {
            AssignmentRow before = requireAssignment(context, assignmentId, true);
            requireVersion(before, request.version(), request.assignmentRevision());
            requireActive(before);
            if (action == Action.CANCEL) requireCreator(context, before);
            else requireAssignee(context, before);
            AssignmentState assignmentState = before.assignmentState();
            WorkState workState = before.workState();
            switch (action) {
                case ACCEPT -> { requirePending(before); assignmentState = AssignmentState.ACCEPTED; }
                case DECLINE -> { requirePending(before); assignmentState = AssignmentState.DECLINED; }
                case START -> {
                    requireAccepted(before);
                    if (workState == WorkState.IN_PROGRESS) throw conflict();
                    workState = WorkState.IN_PROGRESS;
                }
                case WAIT -> {
                    requireAccepted(before);
                    if (workState == WorkState.WAITING) throw conflict();
                    workState = WorkState.WAITING;
                }
                case COMPLETE -> { requireAccepted(before); workState = WorkState.COMPLETED; }
                case CANCEL -> workState = WorkState.CANCELLED;
                default -> throw invalid("Invalid assignment command.");
            }
            AssignmentRow after = repository.transition(context, before, assignmentState, workState);
            recordEvent(context, correlationId, action.name(), request.reasonCode(), before, after);
            return after;
        });
    }

    public MutationResult reassign(AccessContext context, UUID assignmentId, UUID commandId,
                                   String correlationId, ReassignRequest request) {
        access.write(context);
        requireId(assignmentId);
        if (request == null || request.assigneeUserId() == null || request.assigneeUserId() <= 0) {
            throw invalid("An eligible assignee is required.");
        }
        requireVersionInput(request.version(), request.assignmentRevision());
        requireReason(request.reasonCode(), true);
        String fingerprint = requireCommandFingerprint(commandId, request);
        Optional<AppliedCommand> replay = replay(context, commandId, "REASSIGN", assignmentId.toString(), fingerprint, false);
        if (replay.isPresent()) return result(context, replay.get());
        AssignmentRow snapshot = requireAssignment(context, assignmentId, false);
        requireReassignable(context, snapshot, request);
        Instant validationStartedAt = clock.instant();
        sourceAuthority.requireReassignment(context, snapshot.source(), request.assigneeUserId());
        return execute(context, commandId, "REASSIGN", assignmentId.toString(), request, () -> {
            AssignmentRow before = requireAssignment(context, assignmentId, true);
            requireReassignable(context, before, request);
            requireFreshSourceValidation(validationStartedAt);
            AssignmentRow after = repository.reassign(context, before, request.assigneeUserId());
            recordEvent(context, correlationId, "REASSIGN", request.reasonCode(), before, after);
            return after;
        });
    }

    /** Recovery returns stable evidence with current Work visibility, never a saved response snapshot. */
    public MutationResult receipt(AccessContext context, UUID commandId) {
        access.read(context);
        requireId(commandId);
        ReceiptRow receipt = repository.receipt(context, commandId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return result(context, new AppliedCommand(requireAssignment(context, receipt.assignmentId(), false), receipt, true));
    }

    public EventPage events(AccessContext context, UUID assignmentId, long afterVersion, int size) {
        access.read(context);
        if (afterVersion < -1 || size < 1 || size > 100) throw invalid("Invalid event cursor or size.");
        requireAssignment(context, assignmentId, false);
        List<Event> found = repository.events(context, assignmentId, afterVersion, size + 1);
        boolean more = found.size() > size;
        List<Event> items = List.copyOf(more ? found.subList(0, size) : found);
        return new EventPage(items, items.isEmpty() ? afterVersion : items.getLast().version(), more);
    }

    private MutationResult execute(AccessContext context, UUID commandId, String operation,
                                   String target, Object request, Supplier<AssignmentRow> mutation) {
        String fingerprint = requireCommandFingerprint(commandId, request);
        AppliedCommand applied;
        try {
            applied = transactions.execute(status -> {
                repository.lockCommand(context, commandId);
                Optional<AppliedCommand> existing = replay(context, commandId, operation, target, fingerprint, true);
                if (existing.isPresent()) return existing.get();
                AssignmentRow row = mutation.get();
                ReceiptRow receipt = repository.recordReceipt(context, commandId, operation, target, fingerprint, row);
                return new AppliedCommand(row, receipt, false);
            });
        } catch (DataAccessException exception) {
            if (!(exception instanceof CannotAcquireLockException) && !isPostgresLockTimeout(exception)) throw exception;
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "Assignment is busy; retry with the same command key.", exception);
        }
        // Source inspection happens only after the command, audit, event, and receipt have committed.
        return result(context, applied);
    }

    private Optional<AppliedCommand> replay(AccessContext context, UUID commandId, String operation,
                                           String target, String fingerprint, boolean forUpdate) {
        Optional<ReceiptRow> existing = repository.receipt(context, commandId);
        if (existing.isPresent()) {
            ReceiptRow receipt = existing.get();
            // An earlier assignee loses access to old successful commands when the task is reassigned.
            AssignmentRow current = requireAssignment(context, receipt.assignmentId(), forUpdate);
            if (!operation.equals(receipt.operation()) || !target.equals(receipt.target())
                    || !fingerprint.equals(receipt.fingerprint())) throw conflict();
            return Optional.of(new AppliedCommand(current, receipt, true));
        }
        return Optional.empty();
    }

    private MutationResult result(AccessContext context, AppliedCommand applied) {
        ReceiptRow receipt = applied.receipt();
        // Re-read after commit: a different command may have reassigned this task in the meantime.
        AssignmentRow row = requireAssignment(context, receipt.assignmentId(), false);
        return new MutationResult(response(context, row), new CommandReceipt(receipt.commandId(),
                receipt.assignmentId(), receipt.operation(), receipt.appliedVersion(),
                receipt.appliedAssignmentRevision(), receipt.appliedAt(), applied.replayed()));
    }

    private Task response(AccessContext context, AssignmentRow row) {
        return response(context, row, true);
    }

    private Task response(AccessContext context, AssignmentRow row, boolean inspectSource) {
        Inspection inspection = inspectSource
                ? sourceAuthority.inspect(context, row.source(), row.confirmedSourceVersion()) : null;
        // Owner I/O may take time. A reassignment during that wait must revoke the old assignee's view.
        row = requireAssignment(context, row.assignmentId(), false);
        SourceView source = inspectSource ? sanitizedSource(inspection, row)
                : new SourceView(SourceAvailability.NOT_REQUESTED, null, null, null);
        boolean writable = Arrays.stream(context.permissions().split(","))
                .map(String::trim).anyMatch("APP.WORK:UPDATE"::equals);
        boolean active = !terminal(row.workState()) && writable;
        boolean assignee = active && row.assigneeUserId() == context.userId();
        boolean creator = active && row.createdByUserId() == context.userId();
        boolean pending = assignee && row.assignmentState() == AssignmentState.PENDING;
        boolean accepted = assignee && row.assignmentState() == AssignmentState.ACCEPTED;
        Capabilities capabilities = new Capabilities(pending, pending,
                accepted && row.workState() != WorkState.IN_PROGRESS,
                accepted && row.workState() != WorkState.WAITING, accepted,
                creator && source.availability() == SourceAvailability.AVAILABLE && inspection.canReassign(), creator);
        return new Task(row.assignmentId(), row.createdByUserId(), row.assignedByUserId(), row.assigneeUserId(),
                row.title(), row.description(), row.priority(), row.dueAt(), row.assignmentState(), row.workState(),
                row.assignmentRevision(), row.version(), source, capabilities, row.createdAt(), row.updatedAt(),
                row.acceptedAt(), row.completedAt());
    }

    private SourceView sanitizedSource(Inspection inspection, AssignmentRow row) {
        if (inspection == null || inspection.source() == null
                || inspection.source().availability() != SourceAvailability.AVAILABLE) return unavailableSource();
        SourceView source = inspection.source();
        // Even a faulty owner adapter cannot bind this task to a different source or expose arbitrary URLs.
        if (!row.source().equals(source.reference()) || source.sourceVersion() == null || source.sourceVersion() < 0
                || source.sourceRoute() != null && !safeRoute(source.sourceRoute())) return unavailableSource();
        return source;
    }

    private boolean safeRoute(String route) {
        return route.startsWith("/") && !route.startsWith("//") && !route.contains("\\")
                && route.chars().noneMatch(character -> Character.isISOControl(character));
    }

    private SourceView unavailableSource() { return new SourceView(SourceAvailability.UNAVAILABLE, null, null, null); }

    private AssignmentRow requireAssignment(AccessContext context, UUID assignmentId, boolean lock) {
        requireId(assignmentId);
        return repository.find(context, assignmentId, lock).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void recordEvent(AccessContext context, String correlationId, String action, String reasonCode,
                             AssignmentRow before, AssignmentRow after) {
        UUID auditId = audit.successWithId(context.tenantId(), context.userId(),
                "work-assignment." + action.toLowerCase(Locale.ROOT), "WORK_ASSIGNMENT",
                after.assignmentId().toString(), correlationId, snapshot(before, null), snapshot(after, reasonCode));
        repository.appendEvent(context, after, action, reasonCode, auditId);
    }

    private Map<String, Object> snapshot(AssignmentRow row, String reasonCode) {
        if (row == null) return Map.of();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("createdByUserId", row.createdByUserId());
        snapshot.put("assignedByUserId", row.assignedByUserId());
        snapshot.put("assigneeUserId", row.assigneeUserId());
        snapshot.put("assignmentState", row.assignmentState());
        snapshot.put("workState", row.workState());
        snapshot.put("assignmentRevision", row.assignmentRevision());
        snapshot.put("version", row.version());
        snapshot.put("reasonCode", reasonCode);
        return snapshot;
    }

    private String fingerprint(Object value) {
        try {
            byte[] json = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Work assignment serialization failed.", exception);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String requireCommandFingerprint(UUID commandId, Object request) {
        if (commandId == null) throw invalid("Idempotency-Key is required.");
        return fingerprint(request);
    }

    private boolean isPostgresLockTimeout(Throwable exception) {
        // Some configured JDBC translators leave PostgreSQL's lock-not-available state uncategorized.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** A bounded freshness fence reduces stale validation; it is not a distributed transaction. */
    private void requireFreshSourceValidation(Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        if (elapsed.isNegative() || elapsed.compareTo(SOURCE_VALIDATION_WINDOW) > 0) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Source validation expired; review and retry the command.");
        }
    }

    private void requireReassignable(AccessContext context, AssignmentRow before, ReassignRequest request) {
        requireCreator(context, before);
        requireVersion(before, request.version(), request.assignmentRevision());
        requireActive(before);
        if (before.assigneeUserId() == request.assigneeUserId()) throw conflict();
    }

    private void requireConfirmed(ConfirmedTask task, CreateRequest request) {
        if (task == null || !request.source().equals(task.source())
                || task.sourceVersion() != request.expectedSourceVersion() || task.assigneeUserId() <= 0
                || task.title() == null || task.title().isBlank() || task.title().length() > 500
                || task.description() != null && task.description().length() > 4000 || task.priority() == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, "The source has no matching confirmed task.");
        }
    }

    private void requireSource(SourceIdentity source) {
        if (source == null || source.sourceSystem() != SourceSystem.MEETING_FOLLOWUP
                || source.meetingId() == null || source.reportId() == null || source.candidateId() == null) {
            throw invalid("A complete Meeting follow-up identity is required.");
        }
    }

    private void requireVersionInput(Long version, Long assignmentRevision) {
        requireNonNegative(version, "version");
        requireNonNegative(assignmentRevision, "assignment revision");
    }

    private void requireNonNegative(Long value, String name) {
        if (value == null || value < 0) throw invalid("A non-negative " + name + " is required.");
    }

    private void requireVersion(AssignmentRow row, long version, long assignmentRevision) {
        if (row.version() != version || row.assignmentRevision() != assignmentRevision) throw conflict();
    }

    private void requireReason(String reason, boolean required) {
        if (required && reason == null || reason != null && !reason.matches("[A-Z][A-Z0-9_]{2,47}")) {
            throw invalid("A valid reason code is required.");
        }
    }

    private void requireCreator(AccessContext context, AssignmentRow row) {
        if (row.createdByUserId() != context.userId()) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private void requireAssignee(AccessContext context, AssignmentRow row) {
        if (row.assigneeUserId() != context.userId()) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private void requirePending(AssignmentRow row) {
        if (row.assignmentState() != AssignmentState.PENDING) throw conflict();
    }

    private void requireAccepted(AssignmentRow row) {
        if (row.assignmentState() != AssignmentState.ACCEPTED) throw conflict();
    }

    private boolean terminal(WorkState state) { return state == WorkState.COMPLETED || state == WorkState.CANCELLED; }
    private void requireActive(AssignmentRow row) { if (terminal(row.workState())) throw conflict(); }
    private void requireId(UUID id) { if (id == null) throw invalid("An identifier is required."); }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT); }
    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private record AppliedCommand(AssignmentRow assignment, ReceiptRow receipt, boolean replayed) { }
}
