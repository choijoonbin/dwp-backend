package com.dwp.services.people.workforce;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class WorkforceExportService {

    private static final String GOVERNANCE_PERMISSION = "ADMIN.WORKFORCE_ACCESS:MANAGE";

    private final WorkforceAccessPolicyService accessPolicyService;
    private final WorkforceExportRepository repository;
    private final WorkforceExportPolicy policy;
    private final AuditOutboxRecorder audit;
    private final ObjectMapper objectMapper;

    public WorkforceExportService(
            WorkforceAccessPolicyService accessPolicyService,
            WorkforceExportRepository repository,
            WorkforceExportPolicy policy,
            AuditOutboxRecorder audit,
            ObjectMapper objectMapper) {
        this.accessPolicyService = accessPolicyService;
        this.repository = repository;
        this.policy = policy;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkforceExportDtos.Preview preview(WorkforceExportDtos.PreviewRequest request) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        WorkforceAccessPolicyService.Decision decision = accessPolicyService.require("EXPORT");
        DatasetDecision dataset = requireDataset(
                request.datasetKey(), request.selection(), decision.fieldGroups());
        WorkforceExportDtos.Preview preview = new WorkforceExportDtos.Preview(
                true,
                policy.executionEnabled(),
                dataset.dataset().datasetKey(),
                dataset.dataset().allowedSelectionKeys(),
                decision.tenantWide() ? "TENANT" : "ORGANIZATION_SET",
                decision.organizationIds().stream().sorted().toList(),
                decision.fieldGroups().stream().sorted().toList(),
                "CSV",
                policy.maskingProfile(),
                policy.watermarkTemplate(),
                policy.artifactTtlHours(),
                policy.maximumAttempts(),
                policy.maximumManualRetries(),
                policy.blockers(),
                policy.executionEnabled()
                        ? "The request can enter the governed export queue."
                        : "Execution remains blocked until security and worker infrastructure decisions are approved.",
                Instant.now());
        record(actor, "workforce.export.previewed", "PREVIEW", null,
                Map.of("datasetKey", preview.datasetKey(),
                        "selection", dataset.selection(),
                        "populationType", preview.populationType(),
                        "organizationCount", preview.organizationIds().size(),
                        "fieldGroups", preview.fieldGroups(),
                        "executionEnabled", preview.executionEnabled(),
                        "blockers", preview.blockers()), null);
        return preview;
    }

    @Transactional(readOnly = true)
    public List<WorkforceExportDtos.DatasetSummary> datasets() {
        WorkforceAccessPolicyService.Decision decision = accessPolicyService.require("EXPORT");
        return repository.activeDatasets().stream()
                .filter(dataset -> decision.fieldGroups().containsAll(dataset.requiredFieldGroups()))
                .map(dataset -> new WorkforceExportDtos.DatasetSummary(
                        dataset.datasetKey(), dataset.name(), dataset.description(),
                        dataset.requiredFieldGroups(), dataset.allowedSelectionKeys(),
                        dataset.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkforceExportDtos.RequestSummary> list() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        return repository.list(actor.tenantId(), actor.userId(), canGovern(actor)).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkforceExportDtos.AttemptEvent> attempts(UUID requestId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireRequest(actor, requestId);
        return repository.attempts(actor.tenantId(), requestId);
    }

    @Transactional
    public WorkforceExportDtos.RequestSummary create(
            WorkforceExportDtos.CreateRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        WorkforceAccessPolicyService.Decision decision = accessPolicyService.require("EXPORT");
        DatasetDecision dataset = requireDataset(
                request.datasetKey(), request.selection(), decision.fieldGroups());
        UUID requestId = UUID.randomUUID();
        List<UUID> organizationIds = decision.organizationIds().stream().sorted().toList();
        List<String> fieldGroups = decision.fieldGroups().stream().sorted().toList();
        String populationType = decision.tenantWide() ? "TENANT" : "ORGANIZATION_SET";
        String watermark = policy.watermark(
                actor.tenantId(), actor.userId(), request.recipientReference().trim(), requestId);
        Map<String, Object> snapshot = Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("datasetKey", dataset.dataset().datasetKey()),
                Map.entry("datasetVersion", dataset.dataset().version()),
                Map.entry("selection", dataset.selection()),
                Map.entry("populationType", populationType),
                Map.entry("organizationIds", organizationIds),
                Map.entry("fieldGroups", fieldGroups),
                Map.entry("exportFormat", request.exportFormat()),
                Map.entry("maskingProfile", policy.maskingProfile()),
                Map.entry("watermarkText", watermark),
                Map.entry("artifactTtlHours", policy.artifactTtlHours()),
                Map.entry("maximumAttempts", policy.maximumAttempts()),
                Map.entry("maximumManualRetries", policy.maximumManualRetries()),
                Map.entry("executionEnabled", policy.executionEnabled()),
                Map.entry("blockers", policy.blockers()),
                Map.entry("accessDecisionFingerprint", decision.fingerprint()));
        String snapshotJson = json(snapshot);
        String requestSha256 = sha256(json(Map.ofEntries(
                Map.entry("datasetKey", dataset.dataset().datasetKey()),
                Map.entry("datasetVersion", dataset.dataset().version()),
                Map.entry("selection", dataset.selection()),
                Map.entry("populationType", populationType),
                Map.entry("organizationIds", organizationIds),
                Map.entry("fieldGroups", fieldGroups),
                Map.entry("exportFormat", request.exportFormat()),
                Map.entry("maskingProfile", policy.maskingProfile()),
                Map.entry("watermarkTemplate", policy.watermarkTemplate()),
                Map.entry("artifactTtlHours", policy.artifactTtlHours()),
                Map.entry("maximumAttempts", policy.maximumAttempts()),
                Map.entry("maximumManualRetries", policy.maximumManualRetries()),
                Map.entry("executionEnabled", policy.executionEnabled()),
                Map.entry("blockers", policy.blockers()),
                Map.entry("accessDecisionFingerprint", decision.fingerprint()),
                Map.entry("recipientReference", request.recipientReference().trim()),
                Map.entry("purpose", request.purpose().trim()),
                Map.entry("sourceReference", request.sourceReference().trim()))));
        try {
            WorkforceExportRepository.RequestRow created = repository.create(
                    actor.tenantId(), actor.userId(), requestId, request,
                    populationType, organizationIds, fieldGroups, policy.maskingProfile(),
                    watermark, policy.executionEnabled(), policy.blockers(), snapshotJson,
                    requestSha256);
            record(actor, "workforce.export.requested", requestId.toString(), correlationId,
                    snapshot(created), request.purpose());
            return summary(created);
        } catch (DataIntegrityViolationException exception) {
            WorkforceExportRepository.RequestRow existing = repository.findByIdempotency(
                    actor.tenantId(), actor.userId(), request.idempotencyKey().trim())
                    .orElseThrow(() -> exception);
            if (!existing.requestSha256().equals(requestSha256)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key is already bound to another export request.");
            }
            return summary(existing);
        }
    }

    @Transactional
    public WorkforceExportDtos.RequestSummary cancel(
            UUID requestId,
            WorkforceExportDtos.DecisionRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        WorkforceExportRepository.RequestRow before = requireRequest(actor, requestId);
        String target = WorkforceExportLifecycle.cancellationTarget(before.lifecycleState());
        WorkforceExportRepository.RequestRow cancelled = repository.cancel(
                actor.tenantId(), actor.userId(), requestId, request.version(), target,
                canGovern(actor));
        if (cancelled == null) {
            throw conflict("The export request changed before cancellation.");
        }
        record(actor, "workforce.export.cancellation-requested", requestId.toString(),
                correlationId, snapshot(cancelled), request.reason());
        return summary(cancelled);
    }

    @Transactional
    public WorkforceExportDtos.RequestSummary retry(
            UUID requestId,
            WorkforceExportDtos.DecisionRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = requireGovernor();
        WorkforceExportRepository.RequestRow before = requireRequest(actor, requestId);
        WorkforceExportLifecycle.requireRetryable(
                before.lifecycleState(), before.executionEnabled(), !before.blockers().isEmpty(),
                before.manualRetryCount(), policy.maximumManualRetries());
        WorkforceExportRepository.RequestRow retried = repository.retry(
                actor.tenantId(), actor.userId(), requestId, request.version(), true);
        if (retried == null) throw conflict("The export request changed before retry.");
        record(actor, "workforce.export.retry-requested", requestId.toString(), correlationId,
                snapshot(retried), request.reason());
        return summary(retried);
    }

    WorkforceExportRepository.RequestRow requireRequest(
            PeopleRequestContext.Actor actor,
            UUID requestId) {
        return repository.find(actor.tenantId(), actor.userId(), requestId, canGovern(actor))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    void recordWorkerEvent(
            WorkforceExportRepository.RequestRow row,
            String action,
            String reason,
            Map<String, Object> metadata) {
        audit.record(AuditEvent.builder()
                .tenantId(row.tenantId())
                .category("DATA_EXPORT")
                .action(action)
                .actorType("SERVICE")
                .actorId("workforce-export-worker")
                .sourceService("dwp-people-server")
                .sourceModule("workforce-export")
                .targetType("WORKFORCE_EXPORT")
                .targetId(row.requestId().toString())
                .reason(reason)
                .metadata(metadata)
                .retentionClass("EXTENDED")
                .build());
    }

    @Transactional
    void completeWorkerAttempt(
            WorkforceExportRepository.RequestRow row,
            WorkforceExportDtos.ArtifactEvidence artifact,
            String workerReference) {
        repository.complete(row, artifact, workerReference);
        recordWorkerEvent(row, "workforce.export.completed", null,
                Map.of("artifactSha256", artifact.artifactSha256(),
                        "artifactSizeBytes", artifact.artifactSizeBytes(),
                        "artifactExpiresAt", artifact.artifactExpiresAt(),
                        "attemptCount", row.attemptCount()));
    }

    @Transactional
    void failWorkerAttempt(
            WorkforceExportRepository.RequestRow row,
            String targetState,
            Instant nextAttemptAt,
            String failureCode,
            String redactedMessage,
            String workerReference) {
        repository.fail(row, targetState, nextAttemptAt, failureCode, redactedMessage,
                workerReference);
        String action = switch (targetState) {
            case "FAILED" -> "workforce.export.failed";
            case "CANCELLED" -> "workforce.export.cancelled";
            default -> "workforce.export.retry-scheduled";
        };
        recordWorkerEvent(row, action,
                "CANCELLED".equals(targetState)
                        ? "Cancellation was honored before artifact publication."
                        : "Workforce export artifact processing failed.",
                Map.of("attemptCount", row.attemptCount(),
                        "maximumAttempts", policy.maximumAttempts(),
                        "retryCycleAttemptCount", row.retryCycleAttemptCount(),
                        "failureCode", failureCode));
    }

    @Transactional
    boolean expireArtifact(WorkforceExportRepository.RequestRow row) {
        if (!repository.expireArtifact(row)) return false;
        recordWorkerEvent(
                row,
                "workforce.export.expired",
                "The governed artifact retention window elapsed.",
                Map.of(
                        "artifactSha256", row.artifactSha256(),
                        "artifactSizeBytes", row.artifactSizeBytes(),
                        "artifactExpiresAt", row.artifactExpiresAt(),
                        "attemptCount", row.attemptCount()));
        return true;
    }

    private PeopleRequestContext.Actor requireGovernor() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (!canGovern(actor)) throw new BaseException(ErrorCode.FORBIDDEN);
        return actor;
    }

    private boolean canGovern(PeopleRequestContext.Actor actor) {
        return actor.permissions().contains(GOVERNANCE_PERMISSION)
                || (actor.permissions().isEmpty()
                    && actor.hasAnyRole("ADMIN", "TENANT_ADMIN"));
    }

    private WorkforceExportDtos.RequestSummary summary(WorkforceExportRepository.RequestRow row) {
        return new WorkforceExportDtos.RequestSummary(
                row.requestId(), row.datasetKey(), selection(row.selection()),
                row.populationType(), row.organizationIds(), row.fieldGroups(),
                row.exportFormat(), row.maskingProfile(), row.watermarkText(),
                row.recipientReference(), row.purpose(), row.sourceReference(),
                row.lifecycleState(), row.executionEnabled(), row.blockers(), row.requestSha256(),
                row.artifactSha256(), row.artifactSizeBytes(), row.artifactExpiresAt(),
                row.attemptCount(), row.retryCycleAttemptCount(), row.manualRetryCount(),
                row.nextAttemptAt(), row.cancellationRequestedAt(),
                row.completedAt(), row.version(), row.createdAt(), row.updatedAt());
    }

    private Map<String, Object> snapshot(WorkforceExportRepository.RequestRow row) {
        return Map.ofEntries(
                Map.entry("requestId", row.requestId()),
                Map.entry("datasetKey", row.datasetKey()),
                Map.entry("selection", selection(row.selection())),
                Map.entry("lifecycleState", row.lifecycleState()),
                Map.entry("populationType", row.populationType()),
                Map.entry("organizationIds", row.organizationIds()),
                Map.entry("fieldGroups", row.fieldGroups()),
                Map.entry("maskingProfile", row.maskingProfile()),
                Map.entry("requestSha256", row.requestSha256()),
                Map.entry("executionEnabled", row.executionEnabled()),
                Map.entry("blockers", row.blockers()),
                Map.entry("attemptCount", row.attemptCount()),
                Map.entry("retryCycleAttemptCount", row.retryCycleAttemptCount()),
                Map.entry("manualRetryCount", row.manualRetryCount()),
                Map.entry("version", row.version()));
    }

    private void record(
            PeopleRequestContext.Actor actor,
            String action,
            String targetId,
            String correlationId,
            Map<String, Object> after,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("DATA_EXPORT")
                .action(action)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("workforce-export")
                .targetType("WORKFORCE_EXPORT")
                .targetId(targetId)
                .reason(reason)
                .correlationId(correlationId)
                .afterState(after)
                .retentionClass("EXTENDED")
                .build());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The export policy snapshot cannot be serialized.", exception);
        }
    }

    private Map<String, String> selection(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The stored export selection is invalid.", exception);
        }
    }

    private DatasetDecision requireDataset(
            String datasetKey,
            Map<String, String> selection,
            Set<String> permittedFieldGroups) {
        WorkforceExportRepository.DatasetRow dataset = repository.dataset(datasetKey)
                .filter(row -> "ACTIVE".equals(row.lifecycleState()))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The workforce export dataset is unavailable."));
        if (!permittedFieldGroups.containsAll(dataset.requiredFieldGroups())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The workforce boundary does not permit every field required by this dataset.");
        }
        Map<String, String> normalized = new TreeMap<>();
        selection.forEach((key, value) -> {
            String normalizedValue = value == null ? "" : value.trim();
            if (!dataset.allowedSelectionKeys().contains(key) || normalizedValue.isBlank()) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The workforce export selection contains an unsupported key or value.");
            }
            normalized.put(key, normalizedValue);
        });
        return new DatasetDecision(dataset, Collections.unmodifiableMap(normalized));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record DatasetDecision(
            WorkforceExportRepository.DatasetRow dataset,
            Map<String, String> selection) {
    }
}
