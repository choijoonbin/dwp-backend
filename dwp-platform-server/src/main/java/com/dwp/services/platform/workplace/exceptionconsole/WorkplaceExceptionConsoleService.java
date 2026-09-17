package com.dwp.services.platform.workplace.exceptionconsole;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.workplace.exceptionconsole.WorkplaceExceptionConsoleDtos.*;

@Service
public class WorkplaceExceptionConsoleService {
    private static final int MAX_EXPORT_ROWS = 250;
    private final WorkplaceExceptionConsoleRepository repository;
    private final WorkplaceConnectorOpsService connectors;
    private final WorkplaceSpatialGovernanceRepository audits;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String externalTelemetryUrl;

    @Autowired
    public WorkplaceExceptionConsoleService(
            WorkplaceExceptionConsoleRepository repository,
            WorkplaceConnectorOpsService connectors,
            WorkplaceSpatialGovernanceRepository audits,
            ObjectMapper objectMapper,
            @Value("${dwp.workplace.exception-console.external-telemetry-url:}")
            String externalTelemetryUrl) {
        this(repository, connectors, audits, objectMapper, Clock.systemUTC(), externalTelemetryUrl);
    }

    WorkplaceExceptionConsoleService(
            WorkplaceExceptionConsoleRepository repository,
            WorkplaceConnectorOpsService connectors,
            WorkplaceSpatialGovernanceRepository audits,
            ObjectMapper objectMapper,
            Clock clock,
            String externalTelemetryUrl) {
        this.repository = repository;
        this.connectors = connectors;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.externalTelemetryUrl = safeHttpsUrl(externalTelemetryUrl);
    }

    @Transactional(readOnly = true)
    public ExceptionConsole console(long tenantId) {
        requireTenant(tenantId);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<ExceptionItem> items = new ArrayList<>();
        repository.activeSafety(tenantId).forEach(row -> items.add(safety(row)));
        repository.bookingFailures(tenantId).forEach(row -> items.add(booking(row)));
        repository.connectorFailures(tenantId).forEach(row -> items.add(connector(row, now)));
        items.sort(Comparator.comparing(ExceptionItem::severity)
                .thenComparing(ExceptionItem::detectedAt, Comparator.reverseOrder()));

        var stats = repository.recoveryStats(tenantId);
        long dlq = items.stream().filter(item -> item.source() == ExceptionSource.CONNECTOR)
                .flatMap(item -> item.evidence().stream())
                .filter(value -> value.startsWith("DLQ="))
                .mapToLong(value -> parseCount(value.substring(4))).sum();
        // Recovery and SLA percentages remain unknown until their owning systems publish
        // explicit outcome/denominator evidence. A booking success ratio is not a recovery SLA.
        BigDecimal recovery = null;
        int critical = count(items, ExceptionSeverity.CRITICAL);
        int warning = count(items, ExceptionSeverity.WARNING);
        int error = count(items, ExceptionSeverity.ERROR);
        BigDecimal sla = null;
        ExceptionSummary summary = new ExceptionSummary(items.size(), critical, warning, error,
                stats.conflicts(), dlq, recovery, sla);
        return new ExceptionConsole(summary, List.copyOf(items), guardrails(items, stats, dlq),
                now, externalTelemetryUrl);
    }

    @Transactional(readOnly = true)
    public ExceptionItem detail(long tenantId, String exceptionId) {
        String normalized = normalizeId(exceptionId);
        return console(tenantId).exceptions().stream()
                .filter(item -> item.exceptionId().equals(normalized))
                .findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The Workplace exception was not found."));
    }

    @Transactional
    public RecoveryPreview preview(
            long tenantId, long actorId, String exceptionId, String idempotencyKey,
            RecoveryPreviewRequest request, String correlationId) {
        ExceptionItem item = detail(tenantId, exceptionId);
        if (item.action() != ExceptionAction.REPLAY_CONNECTOR || item.connectorKind() == null
                || item.configurationVersion() == null || item.runtimeVersion() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "This exception does not expose an automated recovery action.");
        }
        var replayRequest = new WorkplaceConnectorOpsDtos.ReplayPreviewRequest(
                request.from(), request.to(), true, request.maximumRecords(),
                item.configurationVersion(), item.runtimeVersion());
        var replay = connectors.preview(tenantId, actorId, item.connectorKind(),
                idempotencyKey, replayRequest, correlationId);
        return new RecoveryPreview(item.exceptionId(), replay,
                "Up to " + replay.estimatedRecords() + " failed integration events will be replayed.");
    }

    @Transactional
    public RecoveryReceipt recover(
            long tenantId, long actorId, String exceptionId, String idempotencyKey,
            RecoveryStartRequest request, String correlationId) {
        ExceptionItem item = detail(tenantId, exceptionId);
        if (item.action() != ExceptionAction.REPLAY_CONNECTOR || item.connectorKind() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "This exception does not expose an automated recovery action.");
        }
        var replayRequest = new WorkplaceConnectorOpsDtos.ReplayStartRequest(
                request.previewId(), request.configurationVersion(), request.runtimeVersion(),
                request.reason(), request.explicitConfirmation());
        return new RecoveryReceipt(item.exceptionId(), connectors.startReplay(
                tenantId, actorId, item.connectorKind(), idempotencyKey,
                replayRequest, correlationId));
    }

    String exportCsv(long tenantId) {
        List<ExceptionItem> items = console(tenantId).exceptions().stream()
                .limit(MAX_EXPORT_ROWS).toList();
        StringBuilder csv = new StringBuilder("exceptionId,source,severity,status,code,detectedAt,title,impact\n");
        for (ExceptionItem item : items) {
            append(csv, item.exceptionId(), item.source().name(), item.severity().name(),
                    item.status().name(), item.code(), item.detectedAt().toString(),
                    item.title(), item.impact());
        }
        return csv.toString();
    }

    @Transactional
    public ExportPreview previewExport(long tenantId, long actorId, String idempotencyKey,
                                       ExportPreviewRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        String purpose = normalizeReason(request == null ? null : request.purpose());
        String requestFingerprint = sha256(purpose);
        String correlation = normalizeCorrelation(correlationId);
        repository.lockExportPreview(tenantId, actorId, key);
        var existing = repository.exportPreviewByIdempotency(tenantId, actorId, key);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(requestFingerprint)) {
                throw conflict("The idempotency key was used for a different export preview.");
            }
            return toPreview(existing);
        }
        String csv = exportCsv(tenantId);
        OffsetDateTime now = now();
        var created = new WorkplaceExceptionConsoleRepository.ExportPreviewRow(
                UUID.randomUUID(), tenantId, actorId, purpose, csvRowCount(csv), sha256(csv), key,
                requestFingerprint, correlation, now, now.plusMinutes(10));
        repository.saveExportPreview(created);
        audits.appendAudit(tenantId, actorId, "workplace.exception.export.previewed",
                "WORKPLACE_EXCEPTION_EXPORT_PREVIEW", created.previewId(), correlation,
                objectMapper.createObjectNode().put("rowCount", created.rowCount())
                        .put("purposeSha256", sha256(purpose)));
        return toPreview(created);
    }

    @Transactional
    public ExportReceipt startExport(long tenantId, long actorId, String idempotencyKey,
                                     ExportStartRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        if (request == null || request.previewId() == null || !request.explicitConfirmation()) {
            throw invalid("A current preview and explicit confirmation are required.");
        }
        String reason = normalizeReason(request.reason());
        String fingerprint = sha256(request.previewId() + ":" + reason + ":true");
        String correlation = normalizeCorrelation(correlationId);
        repository.lockExportCommand(tenantId, actorId, key);
        var existing = repository.exportCommandByIdempotency(tenantId, actorId, key);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key was used for a different export command.");
            }
            return toReceipt(existing, true);
        }
        var preview = repository.exportPreview(tenantId, actorId, request.previewId());
        OffsetDateTime now = now();
        if (preview == null || !preview.expiresAt().isAfter(now)) {
            throw conflict("The export preview is missing or expired.");
        }
        String csv = exportCsv(tenantId);
        String contentSha = sha256(csv);
        if (!preview.contentSha256().equals(contentSha) || preview.rowCount() != csvRowCount(csv)) {
            throw versionConflict("Exception evidence changed. Create a new export preview.");
        }
        var created = new WorkplaceExceptionConsoleRepository.ExportCommandRow(
                UUID.randomUUID(), preview.previewId(), tenantId, actorId, reason,
                preview.rowCount(), csv, contentSha, key, fingerprint, correlation,
                now, now.plusHours(1));
        repository.saveExportCommand(created);
        audits.appendAudit(tenantId, actorId, "workplace.exception.export.created",
                "WORKPLACE_EXCEPTION_EXPORT", created.commandId(), correlation,
                objectMapper.createObjectNode().put("rowCount", created.rowCount())
                        .put("contentSha256", created.contentSha256())
                        .put("reasonSha256", sha256(reason)));
        return toReceipt(created, false);
    }

    @Transactional(readOnly = true)
    public String downloadExport(long tenantId, long actorId, UUID commandId) {
        requireActor(tenantId, actorId);
        var command = repository.exportCommand(tenantId, actorId, commandId);
        if (command == null) throw new BaseException(
                ErrorCode.ENTITY_NOT_FOUND, "The exception export was not found.");
        if (!command.expiresAt().isAfter(now())) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The exception export expired.");
        }
        if (!command.contentSha256().equals(sha256(command.csvContent()))) {
            throw conflict("The exception export integrity check failed.");
        }
        return command.csvContent();
    }

    private ExceptionItem safety(WorkplaceExceptionConsoleRepository.SafetyRow row) {
        ExceptionSeverity severity = "CRITICAL".equals(row.severity())
                ? ExceptionSeverity.CRITICAL : ExceptionSeverity.WARNING;
        return new ExceptionItem("SAFETY:" + row.id(), ExceptionSource.SAFETY, severity,
                "CLOSURE_PENDING".equals(row.state()) ? ExceptionStatus.RECOVERING : ExceptionStatus.ACTIVE,
                safe(row.message(), "Active safety incident"),
                safe(row.action(), "Open the safety command center for impact and response status."),
                safeCode(row.type(), "SAFETY_INCIDENT"), row.detectedAt(), row.version(),
                List.of("Incident " + safeCode(row.number(), "restricted"),
                        "State=" + row.state()), ExceptionAction.OPEN_SAFETY,
                "/workplace/admin/safety?incident=" + row.id(), null, null, null);
    }

    private ExceptionItem booking(WorkplaceExceptionConsoleRepository.BookingRow row) {
        boolean unknown = "RESULT_UNKNOWN".equals(row.state());
        String code = safeCode(row.errorCode(), unknown ? "BOOKING_RESULT_UNKNOWN" : "BOOKING_FAILED");
        return new ExceptionItem("BOOKING:" + row.id(), ExceptionSource.BOOKING,
                unknown ? ExceptionSeverity.CRITICAL : ExceptionSeverity.ERROR,
                ExceptionStatus.ACTIVE, "Reservation synchronization requires review",
                unknown ? "Outcome is unknown; re-query before any compensating action."
                        : "A reservation item did not reach its authoritative owner.",
                code, row.detectedAt(), row.version(),
                List.of("State=" + row.state(), "Compensation=" + row.compensationAvailable(),
                        "Requery=" + row.requeryRequired()), ExceptionAction.REVIEW_BOOKING,
                "/workplace/planner?batch=" + row.batchId() + "&step=RESULT", null, null, null);
    }

    private ExceptionItem connector(
            WorkplaceExceptionConsoleRepository.ConnectorRow row, OffsetDateTime now) {
        WorkplaceConnectorOpsDtos.ConnectorKind kind =
                WorkplaceConnectorOpsDtos.ConnectorKind.valueOf(row.kind());
        long dlq = row.deadLetterQueueDepth() == null ? 0 : row.deadLetterQueueDepth();
        boolean unverified = row.runtimeVersion() == null;
        boolean stale = row.receivedAt() == null || row.receivedAt().isBefore(now.minusMinutes(5));
        ExceptionSeverity severity = "UNAVAILABLE".equals(row.reportedState()) || dlq > 0
                ? ExceptionSeverity.ERROR : ExceptionSeverity.WARNING;
        String title = label(kind) + (dlq > 0 ? " dead-letter queue needs attention"
                : unverified ? " runtime truth is unavailable"
                : stale ? " runtime observation is stale" : " integration is degraded");
        List<String> evidence = new ArrayList<>();
        evidence.add("DLQ=" + dlq);
        evidence.add("RetryQueue=" + (row.retryQueueDepth() == null ? 0 : row.retryQueueDepth()));
        if (row.lagSeconds() != null) evidence.add("LagSeconds=" + row.lagSeconds());
        if (row.lastSuccessAt() != null) evidence.add("LastSuccessAt=" + row.lastSuccessAt());
        boolean recoverable = row.runtimeVersion() != null && dlq > 0;
        return new ExceptionItem("CONNECTOR:" + row.kind(), ExceptionSource.CONNECTOR, severity,
                row.replayJobId() == null ? ExceptionStatus.ACTIVE : ExceptionStatus.RECOVERING,
                title, dlq > 0 ? dlq + " failed events await governed replay."
                        : "Provider health evidence is incomplete or outside its freshness target.",
                safeCode(row.errorCode(), stale ? "RUNTIME_STALE" : "CONNECTOR_DEGRADED"),
                row.receivedAt() == null ? now : row.receivedAt(),
                row.runtimeVersion() == null ? row.configurationVersion() : row.runtimeVersion(),
                List.copyOf(evidence), recoverable ? ExceptionAction.REPLAY_CONNECTOR
                        : ExceptionAction.REVIEW_BOOKING,
                recoverable ? null : "/workplace/admin/governance?area=dataSources",
                kind, row.configurationVersion(), row.runtimeVersion());
    }

    private List<Guardrail> guardrails(
            List<ExceptionItem> items,
            WorkplaceExceptionConsoleRepository.RecoveryStats stats,
            long dlq) {
        long staleConnectors = items.stream().filter(item -> "RUNTIME_STALE".equals(item.code())).count();
        return List.of(
                new Guardrail("BOOKING_CONFLICT_RECOVERY", "409 concurrency recovery",
                        "Shared rooms and desks", "Automatic recovery within governed retry budget",
                        stats.conflicts() + " conflicts / 24h",
                        stats.conflicts() == 0 ? GuardrailStatus.HEALTHY : GuardrailStatus.WARNING,
                        "Re-query authoritative state before compensation"),
                new Guardrail("CONNECTOR_DLQ", "External integration delivery",
                        "Calendar, access, signage and facilities", "DLQ depth = 0",
                        Long.toString(dlq), dlq == 0 ? GuardrailStatus.HEALTHY : GuardrailStatus.BREACHED,
                        "Preview and explicitly confirm a bounded replay"),
                new Guardrail("RUNTIME_FRESHNESS", "Connector evidence freshness",
                        "All enabled Workplace connectors", "Latest evidence within 5 minutes",
                        staleConnectors + " stale", staleConnectors == 0
                                ? GuardrailStatus.HEALTHY : GuardrailStatus.WARNING,
                        "Never infer healthy state from configuration alone"));
    }

    private static int count(List<ExceptionItem> items, ExceptionSeverity severity) {
        return Math.toIntExact(items.stream().filter(item -> item.severity() == severity).count());
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank() || value.length() > 100
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A valid Workplace exception identifier is required.");
        }
        String normalized = value.trim();
        int separator = normalized.indexOf(':');
        if (separator < 0) {
            separator = normalized.indexOf('_');
        }
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw invalid("A valid Workplace exception identifier is required.");
        }
        String prefix = normalized.substring(0, separator).toUpperCase(Locale.ROOT);
        String suffix = normalized.substring(separator + 1);
        if ("SAFETY".equals(prefix) || "BOOKING".equals(prefix)) {
            try {
                suffix = UUID.fromString(suffix).toString();
            } catch (IllegalArgumentException exception) {
                throw invalid("A valid Workplace exception identifier is required.");
            }
        } else if ("CONNECTOR".equals(prefix)) {
            suffix = suffix.toUpperCase(Locale.ROOT);
            try {
                WorkplaceConnectorOpsDtos.ConnectorKind.valueOf(suffix);
            } catch (IllegalArgumentException exception) {
                throw invalid("A valid Workplace exception identifier is required.");
            }
        } else {
            throw invalid("A valid Workplace exception identifier is required.");
        }
        return prefix + ':' + suffix;
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 497) + "...";
    }

    private static String safeCode(String value, String fallback) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,120}")) return fallback;
        return value;
    }

    private static String label(WorkplaceConnectorOpsDtos.ConnectorKind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static long parseCount(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String safeHttpsUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.matches("https://[^\\s]{1,500}") ? trimmed : null;
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new BaseException(
                ErrorCode.TENANT_MISSING, "A positive tenant identifier is required.");
    }

    private static void append(StringBuilder csv, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            String value = values[i] == null ? "" : values[i];
            String leftTrimmed = value.stripLeading();
            if (!leftTrimmed.isEmpty() && "=+-@".indexOf(leftTrimmed.charAt(0)) >= 0) {
                value = "'" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append('\n');
    }

    private ExportPreview toPreview(WorkplaceExceptionConsoleRepository.ExportPreviewRow row) {
        return new ExportPreview(row.previewId(), row.rowCount(), row.purpose(),
                row.createdAt(), row.expiresAt());
    }

    private ExportReceipt toReceipt(
            WorkplaceExceptionConsoleRepository.ExportCommandRow row, boolean replay) {
        return new ExportReceipt(row.commandId(), row.rowCount(), row.acceptedAt(), row.expiresAt(),
                "/v1/admin/workplace/exceptions/exports/" + row.commandId() + "/content",
                replay, row.correlationId());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static int csvRowCount(String csv) {
        return Math.max(0, csv.split("\\n", -1).length - 2);
    }

    private static String normalizeReason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 500
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("A purpose or reason of at most 500 characters is required.");
        }
        return value.trim();
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{1,160}")) {
            throw invalid("Idempotency-Key must contain 1 to 160 visible ASCII characters.");
        }
        return value;
    }

    private static String normalizeCorrelation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (normalized.length() > 160 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Correlation identifier is invalid.");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw invalid("A positive actor identifier is required.");
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
