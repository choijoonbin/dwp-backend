package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuditControlService {

    private static final int MAX_BATCH_SIZE = 200;
    private static final Set<String> FINDING_STATES = Set.of(
            "OPEN", "ACKNOWLEDGED", "INVESTIGATING", "RESOLVED", "DISMISSED");
    private static final Set<String> CASE_STATES = Set.of(
            "OPEN", "INVESTIGATING", "CONTAINED", "RESOLVED", "CLOSED");
    private static final Set<String> CASE_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> TASK_STATES = Set.of("OPEN", "IN_PROGRESS", "DONE", "SKIPPED");

    private final AuditControlRepository repository;
    private final AuditRiskEngine riskEngine;
    private final AuditIntegrityService integrityService;
    private final AuditOutboxRecorder outboxRecorder;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedServices;

    public AuditControlService(
            AuditControlRepository repository,
            AuditRiskEngine riskEngine,
            AuditIntegrityService integrityService,
            AuditOutboxRecorder outboxRecorder,
            ObjectMapper objectMapper,
            @Value("${dwp.platform.audit.allowed-services:"
                    + "dwp-auth-server,dwp-platform-server,dwp-people-server,"
                    + "dwp-provider-server,dwp-agent-runtime}") String allowedServices) {
        this.repository = repository;
        this.riskEngine = riskEngine;
        this.integrityService = integrityService;
        this.outboxRecorder = outboxRecorder;
        this.objectMapper = objectMapper;
        this.allowedServices = Arrays.stream(allowedServices.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public int ingest(String claimedService, List<AuditEvent> events) {
        if (events == null || events.isEmpty() || events.size() > MAX_BATCH_SIZE) {
            throw invalid("Audit batch size must be between 1 and 200.");
        }
        if (claimedService == null || !allowedServices.contains(claimedService)) {
            throw invalid("The audit source is not registered.");
        }
        int accepted = 0;
        Instant now = Instant.now();
        for (AuditEvent candidate : events) {
            if (candidate == null || !claimedService.equals(candidate.sourceService())) {
                throw invalid("Audit source identity does not match the event batch.");
            }
            if (candidate.occurredAt().isAfter(now.plus(Duration.ofMinutes(5)))
                    || candidate.occurredAt().isBefore(now.minus(Duration.ofDays(3_650)))) {
                throw invalid("Audit event timestamp is outside the accepted window.");
            }
            AuditEvent event = riskEngine.enrich(candidate.sanitized());
            List<String> changedFields = changedFields(event.beforeState(), event.afterState());
            String hash = hash(event);
            int inserted = repository.ingest(event, changedFields, hash);
            accepted += inserted;
            if (inserted > 0) createFindingIfNeeded(event);
        }
        return accepted;
    }

    @Transactional(readOnly = true)
    public AuditControlDtos.Overview overview(AuditCriteria criteria) {
        return new AuditControlDtos.Overview(
                criteria.window(), criteria.from(), criteria.to(), Instant.now(),
                repository.summary(criteria), repository.trend(criteria),
                repository.dimension(criteria, "category", 12),
                repository.dimension(criteria, "outcome", 4),
                repository.dimension(criteria, "actor_display_name", 6),
                repository.findings(criteria.tenantId(), "OPEN", 6),
                repository.sourceHealth(criteria.tenantId()));
    }

    @Transactional(readOnly = true)
    public AuditControlDtos.EventPage events(AuditCriteria criteria, int page, int size) {
        return repository.events(criteria, Math.max(0, page), Math.min(100, Math.max(10, size)));
    }

    @Transactional(readOnly = true)
    public AuditControlDtos.Event event(Long tenantId, UUID eventId) {
        return repository.event(tenantId, eventId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<AuditControlDtos.Finding> findings(Long tenantId, String status) {
        return repository.findings(tenantId, status, 200);
    }

    @Transactional(readOnly = true)
    public AuditControlDtos.FindingContext findingContext(Long tenantId, UUID findingId) {
        AuditControlDtos.Finding finding = repository.finding(tenantId, findingId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        AuditControlDtos.Event primary = finding.eventId() == null
                ? null
                : repository.event(tenantId, finding.eventId()).orElse(null);
        List<AuditControlDtos.Event> related = primary == null
                ? List.of()
                : repository.relatedEvents(tenantId, primary, 24);
        return new AuditControlDtos.FindingContext(finding, primary, related);
    }

    @Transactional
    public AuditControlDtos.Finding updateFinding(
            Long tenantId, String actorId, UUID findingId, AuditControlDtos.FindingUpdate request) {
        AuditControlDtos.Finding before = repository.finding(tenantId, findingId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String status = normalized(request.status());
        if (status != null && !FINDING_STATES.contains(status)) throw invalid("Invalid finding status.");
        AuditControlDtos.FindingUpdate normalized = new AuditControlDtos.FindingUpdate(
                status, clean(request.assignedTo(), 160), clean(request.resolution(), 2_000), request.caseId());
        AuditControlDtos.Finding result = repository.updateFinding(tenantId, findingId, normalized)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (result.caseId() != null) {
            if (!result.caseId().equals(before.caseId())) {
                repository.recordCaseActivity(
                        tenantId, result.caseId(), "FINDING_LINKED", actorId,
                        "audit.finding.linked", Map.of(
                                "findingId", result.findingId().toString(),
                                "severity", result.severity(),
                                "riskScore", result.riskScore()));
            }
            if (result.eventId() != null) {
                repository.event(tenantId, result.eventId()).ifPresent(event -> {
                    repository.linkEvent(
                            tenantId, result.caseId(), actorId,
                            new AuditControlDtos.CaseEventLink(
                                    event.eventId(), event.occurredAt(), "Linked from finding"));
                    captureEntities(tenantId, result.caseId(), actorId, event);
                });
            }
        }
        recordControl(tenantId, actorId, "audit.finding.updated", "AUDIT_FINDING", findingId.toString(),
                Map.of("status", result.status(), "severity", result.severity()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<AuditControlDtos.AuditCase> cases(Long tenantId) {
        return repository.cases(tenantId);
    }

    @Transactional
    public AuditControlDtos.AuditCase createCase(
            Long tenantId, String actorId, AuditControlDtos.CaseCreate request) {
        required(request.title(), 240, "title");
        String severity = normalized(request.severity());
        if (!CASE_SEVERITIES.contains(severity)) throw invalid("Invalid case severity.");
        AuditControlDtos.CaseCreate normalized = new AuditControlDtos.CaseCreate(
                request.title().trim(), clean(request.description(), 4_000), severity,
                clean(request.ownerActorId(), 160));
        UUID caseId = repository.createCase(tenantId, actorId, normalized);
        repository.recordCaseActivity(
                tenantId, caseId, "CASE_CREATED", actorId, "audit.case.created",
                Map.of("severity", severity, "title", normalized.title()));
        recordControl(tenantId, actorId, "audit.case.created", "AUDIT_CASE", caseId.toString(),
                Map.of("severity", severity));
        return repository.caseById(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public AuditControlDtos.AuditCase updateCase(
            Long tenantId, String actorId, UUID caseId, AuditControlDtos.CaseUpdate request) {
        AuditControlDtos.AuditCase before = repository.caseById(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String status = normalized(request.status());
        String severity = normalized(request.severity());
        if (status != null && !CASE_STATES.contains(status)) throw invalid("Invalid case status.");
        if (severity != null && !CASE_SEVERITIES.contains(severity)) throw invalid("Invalid case severity.");
        if ("CLOSED".equals(status) && (request.resolution() == null || request.resolution().isBlank())) {
            throw invalid("A case resolution is required before closing.");
        }
        repository.updateCase(tenantId, caseId, actorId, new AuditControlDtos.CaseUpdate(
                clean(request.title(), 240), clean(request.description(), 4_000), severity, status,
                clean(request.ownerActorId(), 160), clean(request.resolution(), 4_000)));
        AuditControlDtos.AuditCase result = repository.caseById(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String activityType = !result.status().equals(before.status())
                ? "STATUS_CHANGED"
                : !java.util.Objects.equals(result.ownerActorId(), before.ownerActorId())
                        ? "ASSIGNMENT_CHANGED"
                        : result.resolution() != null && !java.util.Objects.equals(result.resolution(), before.resolution())
                                ? "RESOLUTION_RECORDED" : "CASE_UPDATED";
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("fromStatus", before.status());
        activity.put("toStatus", result.status());
        putIfPresent(activity, "owner", result.ownerActorId());
        activity.put("severity", result.severity());
        repository.recordCaseActivity(
                tenantId, caseId, activityType, actorId, "audit.case.updated", activity);
        recordControl(tenantId, actorId, "audit.case.updated", "AUDIT_CASE", caseId.toString(),
                Map.of("status", result.status(), "severity", result.severity()));
        return result;
    }

    @Transactional
    public AuditControlDtos.AuditCase linkEvent(
            Long tenantId, String actorId, UUID caseId, AuditControlDtos.CaseEventLink request) {
        if (request.eventId() == null || request.occurredAt() == null) {
            throw invalid("eventId and occurredAt are required.");
        }
        int linked = repository.linkEvent(tenantId, caseId, actorId, request);
        if (linked == 0) throw new BaseException(ErrorCode.NOT_FOUND);
        AuditControlDtos.Event event = repository.event(tenantId, request.eventId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        captureEntities(tenantId, caseId, actorId, event);
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("eventId", request.eventId().toString());
        activity.put("occurredAt", request.occurredAt().toString());
        putIfPresent(activity, "note", clean(request.note(), 2_000));
        repository.recordCaseActivity(
                tenantId, caseId, "EVIDENCE_LINKED", actorId,
                "audit.case.evidence-linked", activity);
        recordControl(tenantId, actorId, "audit.case.event-linked", "AUDIT_CASE", caseId.toString(),
                Map.of("eventId", request.eventId().toString()));
        return repository.caseById(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public AuditControlDtos.CaseWorkspace caseWorkspace(Long tenantId, UUID caseId) {
        AuditControlDtos.AuditCase auditCase = repository.caseById(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<AuditControlDtos.Finding> findings = repository.caseFindings(tenantId, caseId);
        List<AuditControlDtos.Event> evidence = repository.caseEvidence(tenantId, caseId);
        List<AuditControlDtos.CaseEntity> entities = repository.caseEntities(tenantId, caseId);
        List<AuditControlDtos.CaseActivity> activities = repository.caseActivities(tenantId, caseId);
        List<AuditControlDtos.CaseTask> tasks = repository.caseTasks(tenantId, caseId);
        Instant now = Instant.now();
        int maxRisk = java.util.stream.Stream.concat(
                        findings.stream().map(AuditControlDtos.Finding::riskScore),
                        evidence.stream().map(AuditControlDtos.Event::riskScore))
                .max(Integer::compareTo).orElse(0);
        int openTasks = (int) tasks.stream()
                .filter(task -> !Set.of("DONE", "SKIPPED").contains(task.status())).count();
        int overdueTasks = (int) tasks.stream()
                .filter(task -> !Set.of("DONE", "SKIPPED").contains(task.status()))
                .filter(task -> task.dueAt() != null && task.dueAt().isBefore(now)).count();
        return new AuditControlDtos.CaseWorkspace(
                auditCase,
                new AuditControlDtos.InvestigationSummary(
                        maxRisk, openTasks, overdueTasks,
                        evidence.size(), findings.size(), entities.size()),
                findings, evidence, entities, activities, tasks);
    }

    @Transactional
    public AuditControlDtos.CaseWorkspace addCaseNote(
            Long tenantId, String actorId, UUID caseId, AuditControlDtos.CaseNoteCreate request) {
        required(request.message(), 2_000, "message");
        int inserted = repository.recordCaseActivity(
                tenantId, caseId, "NOTE_ADDED", actorId,
                request.message().trim(), Map.of());
        if (inserted == 0) throw new BaseException(ErrorCode.NOT_FOUND);
        recordControl(tenantId, actorId, "audit.case.note-added", "AUDIT_CASE", caseId.toString(), Map.of());
        return caseWorkspace(tenantId, caseId);
    }

    @Transactional
    public AuditControlDtos.CaseTask createCaseTask(
            Long tenantId, String actorId, UUID caseId, AuditControlDtos.CaseTaskCreate request) {
        required(request.title(), 240, "title");
        String priority = normalized(request.priority());
        if (priority == null) priority = "MEDIUM";
        if (!CASE_SEVERITIES.contains(priority)) throw invalid("Invalid task priority.");
        AuditControlDtos.CaseTaskCreate normalized = new AuditControlDtos.CaseTaskCreate(
                request.title().trim(), clean(request.description(), 2_000), priority,
                clean(request.ownerActorId(), 160), request.dueAt());
        UUID taskId = repository.createCaseTask(tenantId, caseId, actorId, normalized);
        repository.recordCaseActivity(
                tenantId, caseId, "TASK_CREATED", actorId, "audit.case.task-created",
                Map.of("taskId", taskId.toString(), "title", normalized.title(), "priority", priority));
        return repository.caseTask(tenantId, caseId, taskId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public AuditControlDtos.CaseTask updateCaseTask(
            Long tenantId, String actorId, UUID caseId, UUID taskId,
            AuditControlDtos.CaseTaskUpdate request) {
        String status = normalized(request.status());
        String priority = normalized(request.priority());
        if (status != null && !TASK_STATES.contains(status)) throw invalid("Invalid task status.");
        if (priority != null && !CASE_SEVERITIES.contains(priority)) throw invalid("Invalid task priority.");
        AuditControlDtos.CaseTask result = repository.updateCaseTask(
                        tenantId, caseId, taskId, actorId,
                        new AuditControlDtos.CaseTaskUpdate(
                                clean(request.title(), 240), clean(request.description(), 2_000),
                                status, priority, clean(request.ownerActorId(), 160), request.dueAt()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        repository.recordCaseActivity(
                tenantId, caseId, "TASK_UPDATED", actorId, "audit.case.task-updated",
                Map.of("taskId", taskId.toString(), "status", result.status()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<AuditControlDtos.SavedSearch> savedSearches(Long tenantId, String actorId) {
        return repository.savedSearches(tenantId, actorId);
    }

    @Transactional
    public AuditControlDtos.SavedSearch saveSearch(
            Long tenantId, String actorId, AuditControlDtos.SavedSearchRequest request) {
        required(request.name(), 160, "name");
        AuditCriteria criteria = AuditCriteria.of(
                tenantId, request.window(), request.category(), request.severity(), request.outcome(),
                request.sourceService(), request.actor(), request.query(), Instant.now());
        Map<String, Object> savedCriteria = new LinkedHashMap<>();
        savedCriteria.put("window", criteria.window().name());
        savedCriteria.put("category", criteria.category());
        savedCriteria.put("severity", criteria.severity());
        savedCriteria.put("outcome", criteria.outcome());
        putIfPresent(savedCriteria, "sourceService", criteria.sourceService());
        putIfPresent(savedCriteria, "actor", criteria.actor());
        putIfPresent(savedCriteria, "query", criteria.query());
        AuditControlDtos.SavedSearch result = repository.upsertSavedSearch(
                tenantId, actorId, request.name().trim(), savedCriteria, request.shared());
        recordControl(
                tenantId, actorId, "audit.saved-search.saved", "AUDIT_SAVED_SEARCH",
                result.savedSearchId().toString(),
                Map.of("name", result.name(), "shared", result.shared()));
        return result;
    }

    @Transactional
    public void deleteSavedSearch(Long tenantId, String actorId, UUID savedSearchId) {
        if (!repository.deleteSavedSearch(tenantId, actorId, savedSearchId)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        recordControl(
                tenantId, actorId, "audit.saved-search.deleted", "AUDIT_SAVED_SEARCH",
                savedSearchId.toString(), Map.of());
    }

    @Transactional
    public AuditControlDtos.RetentionPolicy policy(Long tenantId) {
        return repository.policy(tenantId);
    }

    @Transactional
    public AuditControlDtos.RetentionPolicy updatePolicy(
            Long tenantId, String actorId, AuditControlDtos.RetentionPolicyUpdate request) {
        validatePolicy(request);
        AuditControlDtos.RetentionPolicy before = repository.policy(tenantId);
        AuditControlDtos.RetentionPolicy result = repository.updatePolicy(tenantId, actorId, request);
        recordControl(tenantId, actorId, "audit.policy.updated", "AUDIT_POLICY", tenantId.toString(),
                Map.of("beforeStandardDays", before.standardRetentionDays(),
                        "standardDays", result.standardRetentionDays(),
                        "extendedDays", result.extendedRetentionDays(),
                        "highRiskThreshold", result.highRiskThreshold()));
        return result;
    }

    @Transactional
    public AuditControlDtos.ExportJob export(
            Long tenantId, String actorId, AuditControlDtos.ExportRequest request) {
        AuditControlDtos.RetentionPolicy policy = repository.policy(tenantId);
        if (policy.requireExportReason() && (request.reason() == null || request.reason().isBlank())) {
            throw invalid("An export reason is required by policy.");
        }
        String format = normalized(request.format());
        if (!Set.of("CSV", "JSONL").contains(format)) throw invalid("Export format must be CSV or JSONL.");
        AuditCriteria criteria = AuditCriteria.of(
                tenantId, request.window(), request.category(), request.severity(), request.outcome(),
                request.sourceService(), request.actor(), request.query(), Instant.now());
        UUID exportId = repository.createExport(tenantId, actorId, json(request), format);
        List<AuditControlDtos.Event> events = repository.exportEvents(criteria, policy.exportLimitRows());
        byte[] content = serializeExport(events, format);
        String sha256 = AuditIntegrityService.sha256(new String(content, StandardCharsets.UTF_8));
        repository.completeExport(exportId, content, events.size(), sha256);
        Map<String, Object> exportMetadata = new LinkedHashMap<>();
        exportMetadata.put("format", format);
        exportMetadata.put("rows", events.size());
        if (request.reason() != null && !request.reason().isBlank()) {
            exportMetadata.put("reason", request.reason().trim());
        }
        recordControl(tenantId, actorId, "audit.export.completed", "AUDIT_EXPORT", exportId.toString(),
                exportMetadata);
        return repository.exportJob(tenantId, exportId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ExportArtifact exportContent(Long tenantId, UUID exportId) {
        AuditControlDtos.ExportJob job = repository.exportJob(tenantId, exportId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        byte[] content = repository.exportContent(tenantId, exportId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new ExportArtifact(job.format(), content);
    }

    public List<AuditControlDtos.IntegrityCheckpoint> integrity(Long tenantId) {
        return integrityService.list(tenantId);
    }

    @Transactional
    public List<AuditControlDtos.IntegrityCheckpoint> checkpoint(Long tenantId, String actorId) {
        integrityService.checkpoint(tenantId, LocalDate.now(ZoneOffset.UTC).minusDays(1));
        recordControl(tenantId, actorId, "audit.integrity.verified", "AUDIT_INTEGRITY", tenantId.toString(), Map.of());
        return integrityService.list(tenantId);
    }

    private void createFindingIfNeeded(AuditEvent event) {
        int threshold = repository.policy(event.tenantId()).highRiskThreshold();
        if (event.riskScore() < threshold) return;
        String rule = "high-risk-" + event.category().toLowerCase(Locale.ROOT).replace('_', '-');
        repository.createFinding(
                event,
                rule,
                "High-risk " + event.category().toLowerCase(Locale.ROOT).replace('_', ' ') + " event",
                event.action() + " reached risk score " + event.riskScore()
                        + " and requires investigator review.");
    }

    private void recordControl(
            Long tenantId, String actorId, String action, String targetType,
            String targetId, Map<String, Object> metadata) {
        outboxRecorder.record(AuditEvent.builder()
                .tenantId(tenantId).category("ADMIN_CHANGE").action(action).outcome("SUCCESS")
                .actorType("USER").actorId(actorId).sourceService("dwp-platform-server")
                .sourceModule("audit-control-plane").targetType(targetType).targetId(targetId)
                .metadata(metadata).retentionClass("EXTENDED").build());
    }

    private void captureEntities(
            Long tenantId, UUID caseId, String actorId, AuditControlDtos.Event event) {
        Instant occurredAt = event.occurredAt();
        if (event.actorId() != null && !event.actorId().isBlank()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "principal", event.actorPrincipal());
            attributes.put("actorType", event.actorType());
            repository.upsertCaseEntity(
                    tenantId, caseId, actorId,
                    new AuditControlDtos.CaseEntity(
                            actorEntityType(event.actorType()), event.actorId(),
                            first(event.actorDisplayName(), event.actorPrincipal(), event.actorId()),
                            "ACTOR", event.riskScore(), occurredAt, occurredAt, attributes));
        }

        Map<String, Object> targetAttributes = new LinkedHashMap<>();
        targetAttributes.put("targetType", event.targetType());
        repository.upsertCaseEntity(
                tenantId, caseId, actorId,
                new AuditControlDtos.CaseEntity(
                        targetEntityType(event.targetType()), event.targetId(),
                        first(event.targetDisplayName(), event.targetId()),
                        "TARGET", event.riskScore(), occurredAt, occurredAt, targetAttributes));

        Map<String, Object> sourceAttributes = new LinkedHashMap<>();
        sourceAttributes.put("module", event.sourceModule());
        sourceAttributes.put("environment", event.environment());
        repository.upsertCaseEntity(
                tenantId, caseId, actorId,
                new AuditControlDtos.CaseEntity(
                        "SERVICE", event.sourceService(), event.sourceService(),
                        "SOURCE", event.riskScore(), occurredAt, occurredAt, sourceAttributes));
    }

    private String actorEntityType(String actorType) {
        return switch (actorType) {
            case "USER" -> "USER";
            case "AGENT" -> "AI_AGENT";
            case "SERVICE", "SYSTEM" -> "SERVICE";
            default -> "OTHER";
        };
    }

    private String targetEntityType(String targetType) {
        String normalized = targetType == null ? "" : targetType.toUpperCase(Locale.ROOT);
        if (normalized.contains("USER") || normalized.contains("PERSON")) return "USER";
        if (normalized.contains("SERVICE")) return "SERVICE";
        if (normalized.contains("AGENT") || normalized.contains("AI")) return "AI_AGENT";
        if (normalized.contains("APP")) return "APPLICATION";
        if (normalized.contains("DATA") || normalized.contains("EXPORT")) return "DATA";
        return "RESOURCE";
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(before.keySet());
        fields.addAll(after.keySet());
        return fields.stream().filter(key -> !java.util.Objects.equals(before.get(key), after.get(key))).toList();
    }

    private String hash(AuditEvent event) {
        try {
            return AuditIntegrityService.sha256(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw invalid("Audit event cannot be canonicalized.");
        }
    }

    private byte[] serializeExport(List<AuditControlDtos.Event> events, String format) {
        try {
            if ("JSONL".equals(format)) {
                StringBuilder output = new StringBuilder();
                for (AuditControlDtos.Event event : events) {
                    output.append(objectMapper.writeValueAsString(event)).append('\n');
                }
                return output.toString().getBytes(StandardCharsets.UTF_8);
            }
            StringBuilder output = new StringBuilder(
                    "occurredAt,eventId,category,action,outcome,severity,riskScore,actor,source,target,correlationId,recordHash\n");
            for (AuditControlDtos.Event event : events) {
                output.append(csv(event.occurredAt())).append(',').append(csv(event.eventId())).append(',')
                        .append(csv(event.category())).append(',').append(csv(event.action())).append(',')
                        .append(csv(event.outcome())).append(',').append(csv(event.severity())).append(',')
                        .append(event.riskScore()).append(',').append(csv(first(event.actorDisplayName(), event.actorPrincipal(), event.actorId())))
                        .append(',').append(csv(event.sourceService())).append(',')
                        .append(csv(first(event.targetDisplayName(), event.targetId()))).append(',')
                        .append(csv(event.correlationId())).append(',').append(csv(event.recordHash())).append('\n');
            }
            return output.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit export cannot be serialized", exception);
        }
    }

    private void validatePolicy(AuditControlDtos.RetentionPolicyUpdate request) {
        if (request.standardRetentionDays() < 90 || request.standardRetentionDays() > 3_650
                || request.extendedRetentionDays() < request.standardRetentionDays()
                || request.extendedRetentionDays() > 3_650
                || request.exportLimitRows() < 100 || request.exportLimitRows() > 500_000
                || request.highRiskThreshold() < 50 || request.highRiskThreshold() > 100) {
            throw invalid("Audit retention policy is outside supported limits.");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw invalid("Audit criteria cannot be serialized."); }
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public record ExportArtifact(String format, byte[] content) { }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        return cleaned.substring(0, Math.min(cleaned.length(), max));
    }

    private void required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid(field + " is required.");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
