package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static com.dwp.services.platform.workhub.personal.PersonalWorkRepository.*;

@Service
public class PersonalWorkService {
    public static final String NATIVE_SOURCE = "PERSONAL_TASK";
    public static final String PLAN_SELECTION = "DAY_PLAN_SELECTION";
    private final PersonalWorkRepository repository;
    private final PersonalWorkAccess access;
    private final List<PersonalWorkSourceResolver> resolvers;
    private final PlatformAuditService audit;
    private final ObjectMapper mapper;

    public PersonalWorkService(PersonalWorkRepository repository, PersonalWorkAccess access,
            List<PersonalWorkSourceResolver> resolvers, PlatformAuditService audit, ObjectMapper mapper) {
        this.repository = repository;
        this.access = access;
        this.resolvers = List.copyOf(resolvers);
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public TaskPage list(AccessContext context, Status status, int page, int size) {
        access.read(context);
        pageBounds(page, size);
        long total = repository.count(context, status);
        List<Task> items = repository.list(context, status, page, size).stream()
                .map(row -> response(context, row)).toList();
        return new TaskPage(items, page, size, total, ((long) page + 1) * size < total);
    }

    @Transactional(readOnly = true)
    public Task get(AccessContext context, UUID taskId) {
        access.read(context);
        return response(context, requireTask(context, taskId));
    }

    @Transactional
    public Task create(AccessContext context, UUID commandId, String correlationId, CreateTaskRequest request) {
        access.write(context);
        validateFields(request.title(), request.description(), request.priority());
        validateReference(request.sourceReference());
        List<ChecklistItem> checklist = validateChecklist(request.checklist() == null ? List.of() : request.checklist());
        List<SourceReference> sources = requestedSources(request.sourceReference(), request.sourceReferences(), false, List.of());
        TaskRow result = command(context, commandId, "CREATE", "tasks", request, TaskRow.class, () -> {
            sources.forEach(source -> requireSource(context, source));
            CreateTaskRequest effective = new CreateTaskRequest(request.title(), request.description(),
                    request.priority(), request.dueAt(), sources.isEmpty() ? null : sources.getFirst(), checklist, sources);
            TaskRow created = repository.insert(context, UUID.randomUUID(), effective);
            recordTask(context, correlationId, "CREATED", null, created);
            return created;
        });
        return response(context, result);
    }

    @Transactional
    public Task update(AccessContext context, UUID taskId, UUID commandId,
                       String correlationId, UpdateTaskRequest request) {
        access.write(context);
        validateFields(request.title(), request.description(), request.priority());
        validateReference(request.sourceReference());
        if (request.clearSourceReference() && request.sourceReference() != null) {
            throw invalid("Choose a source reference or clear it, not both.");
        }
        TaskRow result = command(context, commandId, "UPDATE", taskId.toString(), request, TaskRow.class, () -> {
            TaskRow before = requireTask(context, taskId);
            requireVersion(before.version(), request.version());
            if (before.status() == Status.ARCHIVED) throw invalid("Reopen an archived task before editing it.");
            List<SourceReference> sources = requestedSources(request.sourceReference(), request.sourceReferences(),
                    request.clearSourceReference(), before.sourceReferences());
            for (SourceReference source : sources) {
                if (!before.sourceReferences().contains(source)) requireSource(context, source);
            }
            List<ChecklistItem> checklist = validateChecklist(request.checklist() == null
                    ? before.checklist() : request.checklist());
            UpdateTaskRequest effective = new UpdateTaskRequest(request.title(), request.description(),
                    request.priority(), request.dueAt(), sources.isEmpty() ? null : sources.getFirst(),
                    request.clearSourceReference(), request.version(), checklist, sources);
            TaskRow after = repository.update(context, taskId, effective);
            recordTask(context, correlationId, "UPDATED", before, after);
            return after;
        });
        return response(context, result);
    }

    @Transactional
    public Task transition(AccessContext context, UUID taskId, UUID commandId,
                           String correlationId, StatusRequest request) {
        access.write(context);
        if (request.status() == null) throw invalid("A target status is required.");
        TaskRow result = command(context, commandId, "STATUS", taskId.toString(), request, TaskRow.class, () -> {
            TaskRow before = requireTask(context, taskId);
            requireVersion(before.version(), request.version());
            requireTransition(before.status(), request.status());
            TaskRow after = repository.transition(context, taskId, request.status(), request.version());
            recordTask(context, correlationId, request.status().name(), before, after);
            return after;
        });
        return response(context, result);
    }

    @Transactional
    public DeleteResult delete(AccessContext context, UUID taskId, UUID commandId,
                               String correlationId, VersionRequest request) {
        access.write(context);
        TaskRow result = command(context, commandId, "DELETE", taskId.toString(), request, TaskRow.class, () -> {
            TaskRow before = requireTask(context, taskId);
            requireVersion(before.version(), request.version());
            TaskRow after = repository.softDelete(context, taskId, request.version());
            repository.removeTaskFromPlans(context, taskId);
            recordTask(context, correlationId, "DELETED", before, after);
            return after;
        });
        return new DeleteResult(result.taskId(), result.version(), result.deletedAt());
    }

    @Transactional(readOnly = true)
    public TimelinePage timeline(AccessContext context, UUID taskId, int page, int size) {
        access.read(context);
        pageBounds(page, size);
        requireTask(context, taskId);
        long total = repository.timelineCount(context, taskId);
        return new TimelinePage(repository.timeline(context, taskId, page, size), page, size,
                total, ((long) page + 1) * size < total);
    }

    @Transactional(readOnly = true)
    public DayPlan dayPlan(AccessContext context, LocalDate date) {
        access.read(context);
        requireDate(date);
        return planResponse(context, repository.plan(context, date));
    }

    @Transactional
    public DayPlan replaceDayPlan(AccessContext context, LocalDate date, UUID commandId,
                                  String correlationId, ReplaceDayPlanRequest request) {
        access.write(context);
        requireDate(date);
        if (request.items() == null || request.items().size() > 100) throw invalid("Select at most 100 items.");
        request.items().forEach(this::requireReference);
        PlanRow result = command(context, commandId, "DAY_PLAN", date.toString(), request, PlanRow.class, () -> {
            PlanRow before = repository.plan(context, date);
            requireVersion(before.version(), request.version());
            Map<SourceReference, SourceReference> existingSelections = new LinkedHashMap<>();
            before.items().forEach(item -> existingSelections.put(selectionReference(context, date, item), item));
            List<SourceReference> items = new ArrayList<>();
            for (SourceReference requested : request.items()) {
                if (PLAN_SELECTION.equals(requested.sourceSystem())) {
                    SourceReference original = existingSelections.get(requested);
                    if (original == null) throw new BaseException(ErrorCode.NOT_FOUND);
                    items.add(original); // Retain/reorder a private selection even after source access changes.
                } else {
                    requireSource(context, requested);
                    items.add(requested);
                }
            }
            if (new HashSet<>(items).size() != items.size()) throw invalid("A work item may only be selected once per day.");
            PlanRow after = repository.replacePlan(context, date, request.version(), items);
            audit.success(context.tenantId(), context.userId(), "personal-work.day-plan.updated",
                    "PERSONAL_WORK_DAY_PLAN", date.toString(), correlationId,
                    Map.of("version", before.version(), "selectionCount", before.items().size()),
                    Map.of("version", after.version(), "selectionCount", after.items().size()));
            return after;
        });
        return planResponse(context, result);
    }

    /** Resolves an owned native task without reading another owner's row. Used by work-hub adapters. */
    @Transactional(readOnly = true)
    public Optional<ResolvedSource> resolveNative(AccessContext context, SourceReference reference) {
        access.read(context);
        if (!NATIVE_SOURCE.equals(reference.sourceSystem()) || reference.obligationKey() != null) return Optional.empty();
        UUID taskId;
        try { taskId = UUID.fromString(reference.sourceReference()); }
        catch (IllegalArgumentException exception) { return Optional.empty(); }
        if (!taskId.toString().equals(reference.sourceReference())) return Optional.empty();
        return repository.find(context, taskId).map(task -> new ResolvedSource(reference, task.title(),
                "/work/queue?work=PERSONAL_TASK%3A" + task.taskId() + "%3A", task.status().name(), task.dueAt()));
    }

    private Task response(AccessContext context, TaskRow row) {
        return new Task(row.taskId(), row.title(), row.description(), row.status(), row.priority(), row.dueAt(),
                row.sourceReference() == null ? null : sourceLink(context, row.sourceReference()),
                row.version(), row.createdAt(), row.updatedAt(), row.completedAt(), row.checklist(),
                row.sourceReferences().stream().map(source -> sourceLink(context, source)).toList());
    }

    private DayPlan planResponse(AccessContext context, PlanRow plan) {
        List<DayPlanItem> items = new ArrayList<>();
        for (int i = 0; i < plan.items().size(); i++) {
            SourceReference item = plan.items().get(i);
            items.add(new DayPlanItem(i, selectionReference(context, plan.date(), item), sourceLink(context, item)));
        }
        return new DayPlan(plan.date(), plan.version(), List.copyOf(items), plan.updatedAt());
    }

    private SourceReference selectionReference(AccessContext context, LocalDate date, SourceReference item) {
        String value = context.tenantId() + ":" + context.userId() + ":" + date + ":" + json(item);
        return new SourceReference(PLAN_SELECTION, hash(value), null);
    }

    private SourceLink sourceLink(AccessContext context, SourceReference reference) {
        return resolve(context, reference)
                .map(source -> new SourceLink(referenceOnly(source) ? "REFERENCE_ONLY" : "AVAILABLE",
                        source.reference(), source.title(),
                        source.sourceRoute(), source.status(), source.dueAt()))
                .orElseGet(() -> new SourceLink("UNAVAILABLE", null, null, null, null, null));
    }

    private boolean referenceOnly(ResolvedSource source) {
        return source.title() == null && source.sourceRoute() == null
                && source.status() == null && source.dueAt() == null;
    }

    private Optional<ResolvedSource> resolve(AccessContext context, SourceReference reference) {
        if (NATIVE_SOURCE.equals(reference.sourceSystem())) return resolveNative(context, reference);
        for (PersonalWorkSourceResolver resolver : resolvers) {
            if (!resolver.supports(reference)) continue;
            Optional<ResolvedSource> resolved = resolver.resolve(context, reference);
            if (resolved.isPresent() && !reference.equals(resolved.get().reference())) {
                throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Source identity resolution mismatch.");
            }
            return resolved;
        }
        return Optional.empty();
    }

    private void requireSource(AccessContext context, SourceReference reference) {
        if (reference != null && resolve(context, reference).isEmpty()) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE);
        }
    }

    private TaskRow requireTask(AccessContext context, UUID taskId) {
        if (taskId == null) throw invalid("A task ID is required.");
        return repository.find(context, taskId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void recordTask(AccessContext context, String correlationId, String action, TaskRow before, TaskRow after) {
        UUID auditId = audit.successWithId(context.tenantId(), context.userId(),
                "personal-work.task." + action.toLowerCase(java.util.Locale.ROOT), "PERSONAL_WORK_TASK",
                after.taskId().toString(), correlationId, snapshot(before), snapshot(after));
        repository.appendTimeline(context, after, action, auditId);
    }

    private Map<String, Object> snapshot(TaskRow row) {
        if (row == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", row.status());
        result.put("priority", row.priority());
        result.put("dueAt", row.dueAt());
        result.put("version", row.version());
        result.put("hasSourceReference", row.sourceReference() != null);
        result.put("sourceCount", row.sourceReferences().size());
        result.put("checklistCount", row.checklist().size());
        result.put("checklistCompleted", row.checklist().stream().filter(ChecklistItem::completed).count());
        result.put("deleted", row.deletedAt() != null);
        return result;
    }

    private <T> T command(AccessContext context, UUID commandId, String operation, String target,
                           Object request, Class<T> responseType, Supplier<T> mutation) {
        if (commandId == null) throw invalid("Idempotency-Key is required.");
        String fingerprint = requestFingerprint(request);
        repository.lock(context);
        Optional<Receipt> existing = repository.receipt(context, commandId);
        if (existing.isPresent()) {
            Receipt receipt = existing.get();
            if (!operation.equals(receipt.operation()) || !target.equals(receipt.target())
                    || !fingerprint.equals(receipt.fingerprint())) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            try {
                T replay = mapper.readValue(receipt.payload(), responseType);
                if (replay instanceof TaskRow task && !"DELETE".equals(operation)
                        && repository.isDeleted(context, task.taskId())) throw new BaseException(ErrorCode.NOT_FOUND);
                return replay;
            }
            catch (JsonProcessingException exception) {
                throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Invalid personal work receipt.", exception);
            }
        }
        T result = mutation.get();
        repository.recordReceipt(context, commandId, operation, target, fingerprint, json(result));
        return result;
    }

    private String requestFingerprint(Object request) {
        if (request instanceof CreateTaskRequest || request instanceof UpdateTaskRequest) {
            // Retain receipt identity for requests sent before the additive optional fields existed.
            com.fasterxml.jackson.databind.node.ObjectNode fields = mapper.valueToTree(request);
            for (String field : List.of("checklist", "sourceReferences")) {
                if (fields.path(field).isNull()) fields.remove(field);
            }
            return hash(json(fields));
        }
        return hash(json(request));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Personal work serialization failed.", exception);
        }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void validateFields(String title, String description, Priority priority) {
        if (title == null || title.isBlank() || title.length() > 500 || priority == null
                || description != null && description.length() > 10000) throw invalid("Invalid personal task fields.");
    }

    private List<ChecklistItem> validateChecklist(List<ChecklistItem> items) {
        if (items.size() > 100) throw invalid("A task supports at most 100 checklist items.");
        HashSet<UUID> ids = new HashSet<>();
        List<ChecklistItem> result = new ArrayList<>();
        for (ChecklistItem item : items) {
            if (item == null || item.itemId() == null || !ids.add(item.itemId())
                    || item.title() == null || item.title().isBlank() || item.title().length() > 500) {
                throw invalid("Checklist items require unique identities and nonblank titles.");
            }
            result.add(new ChecklistItem(item.itemId(), item.title().strip(), item.completed()));
        }
        return List.copyOf(result);
    }

    private List<SourceReference> requestedSources(SourceReference single, List<SourceReference> multiple,
                                                   boolean clear, List<SourceReference> previous) {
        if ((single != null && multiple != null) || (clear && (single != null || multiple != null))) {
            throw invalid("Choose one source update mode.");
        }
        List<SourceReference> result = clear ? List.of()
                : multiple != null ? multiple : single != null ? List.of(single) : previous;
        if (result.size() > 10) throw invalid("A task supports at most 10 source references.");
        result.forEach(this::requireReference);
        if (new HashSet<>(result).size() != result.size()) throw invalid("Source references must be unique.");
        return List.copyOf(result);
    }

    private void requireReference(SourceReference reference) {
        if (reference == null) throw invalid("A source reference is required.");
        validateReference(reference);
    }

    private void validateReference(SourceReference reference) {
        if (reference == null) return;
        if (reference.sourceSystem() == null || !reference.sourceSystem().matches("[A-Z][A-Z0-9_]{0,63}")
                || reference.sourceReference() == null || reference.sourceReference().isBlank()
                || reference.sourceReference().length() > 256
                || !reference.sourceReference().equals(reference.sourceReference().trim())
                || reference.obligationKey() != null && (reference.obligationKey().isBlank()
                    || reference.obligationKey().length() > 160
                    || !reference.obligationKey().equals(reference.obligationKey().trim()))) {
            throw invalid("Invalid source reference identity.");
        }
    }

    private void requireVersion(long current, Long requested) {
        if (requested == null || requested < 0) throw invalid("A non-negative version is required.");
        if (current != requested) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    private void requireTransition(Status from, Status to) {
        if (from == to || from == Status.ARCHIVED && to != Status.OPEN
                || from == Status.COMPLETED && to != Status.OPEN && to != Status.ARCHIVED) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The task status transition is not available.");
        }
    }

    private void requireDate(LocalDate date) {
        if (date == null || date.getYear() < 1900 || date.getYear() > 9999) throw invalid("Invalid plan date.");
    }

    private void pageBounds(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Invalid page or size.");
    }

    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
}
