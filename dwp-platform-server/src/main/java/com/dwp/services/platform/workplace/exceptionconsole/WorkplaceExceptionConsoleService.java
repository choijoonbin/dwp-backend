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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExceptionConsole console(long tenantId) {
        requireTenant(tenantId);
        return console(tenantId, repository.unresolvedCounts(tenantId), false);
    }

    private ExceptionConsole console(
            long tenantId,
            WorkplaceExceptionConsoleRepository.SourceCounts counts,
            boolean complete) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        int regularLimit = complete ? MAX_EXPORT_ROWS + 1 : 100;
        int bookingLimit = complete ? MAX_EXPORT_ROWS + 1 : 250;
        List<ExceptionItem> items = new ArrayList<>();
        repository.activeSafety(tenantId, regularLimit).forEach(row -> items.add(safety(row, now)));
        repository.bookingFailures(tenantId, bookingLimit).forEach(row -> items.add(booking(row, now)));
        repository.connectorFailures(tenantId, regularLimit).forEach(row -> items.add(connector(row, now)));
        repository.deviceCommandFailures(tenantId, regularLimit).forEach(row -> items.add(device(row, now)));
        repository.serviceOrderFailures(tenantId, regularLimit).forEach(row -> items.add(serviceOrder(row, now)));
        repository.serviceProviderFailures(tenantId, regularLimit)
                .forEach(row -> items.add(serviceProvider(row, now)));
        repository.serviceRefundFailures(tenantId, regularLimit)
                .forEach(row -> items.add(serviceRefund(row, now)));
        repository.visitProviderFailures(tenantId, regularLimit)
                .forEach(row -> items.add(visitProvider(row, now)));
        repository.accessPassFailures(tenantId, regularLimit)
                .forEach(row -> items.add(accessPass(row, now)));
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
        List<ExceptionSourceCoverage> coverage = Arrays.stream(ExceptionSource.values())
                .map(source -> coverage(source, counts.get(source), items))
                .toList();
        ExceptionSummary summary = new ExceptionSummary(items.size(), critical, warning, error,
                stats.conflicts(), dlq, recovery, sla, counts.total(), items.size(),
                coverage.stream().anyMatch(ExceptionSourceCoverage::truncated));
        return new ExceptionConsole(summary, List.copyOf(items), guardrails(items, stats, dlq),
                now, externalTelemetryUrl, coverage);
    }

    @Transactional(readOnly = true)
    public ExceptionItem detail(long tenantId, String exceptionId) {
        requireTenant(tenantId);
        String normalized = normalizeId(exceptionId);
        int separator = normalized.indexOf(':');
        String prefix = normalized.substring(0, separator);
        String suffix = normalized.substring(separator + 1);
        OffsetDateTime now = now();
        return switch (ExceptionSource.valueOf(prefix)) {
            case SAFETY -> repository.activeSafety(tenantId, UUID.fromString(suffix))
                    .map(row -> safety(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case BOOKING -> repository.bookingFailure(tenantId, UUID.fromString(suffix))
                    .map(row -> booking(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case CONNECTOR -> repository.connectorFailure(tenantId, suffix)
                    .map(row -> connector(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case DEVICE_COMMAND -> repository.deviceCommandFailure(tenantId, UUID.fromString(suffix))
                    .map(row -> device(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case SERVICE_ORDER -> repository.serviceOrderFailure(tenantId, UUID.fromString(suffix))
                    .map(row -> serviceOrder(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case SERVICE_PROVIDER -> repository.serviceProviderFailure(tenantId, suffix)
                    .map(row -> serviceProvider(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case SERVICE_REFUND -> repository.serviceRefundFailure(tenantId, UUID.fromString(suffix))
                    .map(row -> serviceRefund(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case VISIT_PROVIDER -> repository.visitProviderFailure(tenantId, UUID.fromString(suffix))
                    .map(row -> visitProvider(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
            case ACCESS_PASS -> repository.accessPassFailure(tenantId, UUID.fromString(suffix))
                    .map(row -> accessPass(row, now)).orElseThrow(WorkplaceExceptionConsoleService::notFound);
        };
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
        requireTenant(tenantId);
        var counts = repository.unresolvedCounts(tenantId);
        rejectOverflow(counts.total());
        ExceptionConsole complete = console(tenantId, counts, true);
        if (complete.exceptions().size() > MAX_EXPORT_ROWS) {
            rejectOverflow(complete.exceptions().size());
        }
        if (complete.sourceCoverage().stream().anyMatch(coverage ->
                coverage.returnedCount() != coverage.unresolvedCount())) {
            throw versionConflict(
                    "Exception evidence changed while the export was assembled. Retry the preview.");
        }
        List<ExceptionItem> items = complete.exceptions();
        StringBuilder csv = new StringBuilder(
                "exceptionId,source,severity,status,code,detectedAt,resolved,resolvedAt,ageSeconds,title,impact\n");
        for (ExceptionItem item : items) {
            append(csv, item.exceptionId(), item.source().name(), item.severity().name(),
                    item.status().name(), item.code(), item.detectedAt().toString(),
                    Boolean.toString(item.resolved()),
                    item.resolvedAt() == null ? null : item.resolvedAt().toString(),
                    Long.toString(item.ageSeconds()), item.title(), item.impact());
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

    private ExceptionItem safety(
            WorkplaceExceptionConsoleRepository.SafetyRow row, OffsetDateTime now) {
        ExceptionSeverity severity = "CRITICAL".equals(row.severity())
                ? ExceptionSeverity.CRITICAL : ExceptionSeverity.WARNING;
        return new ExceptionItem("SAFETY:" + row.id(), ExceptionSource.SAFETY, severity,
                "CLOSURE_PENDING".equals(row.state()) ? ExceptionStatus.RECOVERING : ExceptionStatus.ACTIVE,
                safe(row.message(), "Active safety incident"),
                safe(row.action(), "Open the safety command center for impact and response status."),
                safeCode(row.type(), "SAFETY_INCIDENT"), row.detectedAt(), row.version(),
                List.of("Incident " + safeCode(row.number(), "restricted"),
                        "State=" + row.state()), ExceptionAction.OPEN_SAFETY,
                "/workplace/admin/safety?incident=" + row.id(), null, null, null,
                false, null, ageSeconds(row.detectedAt(), now));
    }

    private ExceptionItem booking(
            WorkplaceExceptionConsoleRepository.BookingRow row, OffsetDateTime now) {
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
                "/workplace/planner?batch=" + row.batchId() + "&step=RESULT", null, null, null,
                false, null, ageSeconds(row.detectedAt(), now));
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
                kind, row.configurationVersion(), row.runtimeVersion(),
                false, null, ageSeconds(row.receivedAt() == null ? now : row.receivedAt(), now));
    }

    private ExceptionItem device(
            WorkplaceExceptionConsoleRepository.OperationalRow row, OffsetDateTime now) {
        boolean exhausted = row.attemptCount() >= 10 || "DEAD_LETTER".equals(row.detail());
        return operational("DEVICE_COMMAND:" + row.id(), ExceptionSource.DEVICE_COMMAND,
                exhausted ? ExceptionSeverity.CRITICAL : ExceptionSeverity.ERROR,
                exhausted ? ExceptionStatus.MANUAL_REVIEW : ExceptionStatus.ACTIVE,
                "Device command outcome requires review",
                exhausted ? "Automatic provider reconciliation exhausted its bounded retry budget."
                        : "The provider outcome is unknown; do not repeat the mutation blindly.",
                safeCode(row.errorCode(), exhausted ? "DEVICE_RETRY_EXHAUSTED"
                        : "DEVICE_RESULT_UNKNOWN"), row,
                List.of("Operation=" + safeCode(row.operation(), "UNKNOWN"),
                        "State=" + safeCode(row.state(), "UNKNOWN"),
                        "Attempts=" + row.attemptCount()),
                ExceptionAction.REVIEW_DEVICE,
                "/workplace/admin/devices?device=" + row.parentId()
                        + "&command=" + row.id(), now);
    }

    private ExceptionItem serviceOrder(
            WorkplaceExceptionConsoleRepository.OperationalRow row, OffsetDateTime now) {
        return operational("SERVICE_ORDER:" + row.id(), ExceptionSource.SERVICE_ORDER,
                ExceptionSeverity.ERROR, ExceptionStatus.ACTIVE,
                "Service order provider outcome is unknown",
                "Review the authoritative provider result before changing fulfillment state.",
                safeCode(row.errorCode(), "SERVICE_ORDER_RESULT_UNKNOWN"), row,
                List.of("State=" + safeCode(row.state(), "UNKNOWN"),
                        "ReservationAuthority=" + safeCode(row.operation(), "UNKNOWN")),
                ExceptionAction.REVIEW_SERVICE_ORDER,
                "/workplace/admin/service-fulfillment?order=" + row.id(), now);
    }

    private ExceptionItem serviceProvider(
            WorkplaceExceptionConsoleRepository.ServiceProviderRow row, OffsetDateTime now) {
        OffsetDateTime detectedAt = row.receivedAt() == null ? row.updatedAt() : row.receivedAt();
        boolean unavailable = "UNAVAILABLE".equals(row.state()) || row.state() == null;
        List<String> evidence = new ArrayList<>();
        evidence.add("State=" + safeCode(row.state(), "UNVERIFIED"));
        evidence.add("ConfigurationVersion=" + row.configurationVersion());
        evidence.add("ObservedConfigurationVersion="
                + (row.observedConfigurationVersion() == null
                ? "none" : row.observedConfigurationVersion()));
        return new ExceptionItem("SERVICE_PROVIDER:" + row.providerCode(),
                ExceptionSource.SERVICE_PROVIDER,
                unavailable ? ExceptionSeverity.ERROR : ExceptionSeverity.WARNING,
                ExceptionStatus.ACTIVE, "Service provider is not ready",
                "Provider health or configuration-version evidence is incomplete or stale.",
                safeCode(row.errorCode(), row.state() == null
                        ? "SERVICE_PROVIDER_UNVERIFIED" : "SERVICE_PROVIDER_DEGRADED"),
                detectedAt, row.version(), List.copyOf(evidence),
                ExceptionAction.REVIEW_SERVICE_PROVIDER,
                "/workplace/admin/service-providers?provider="
                        + row.providerCode(), null, row.configurationVersion(),
                row.observedConfigurationVersion(), false, null,
                ageSeconds(detectedAt, now));
    }

    private ExceptionItem serviceRefund(
            WorkplaceExceptionConsoleRepository.OperationalRow row, OffsetDateTime now) {
        boolean manual = "REFUND_NOT_CONFIGURED".equals(row.state())
                || "FAILED".equals(row.state())
                || row.attemptCount() >= 10;
        return operational("SERVICE_REFUND:" + row.id(), ExceptionSource.SERVICE_REFUND,
                manual ? ExceptionSeverity.CRITICAL : ExceptionSeverity.ERROR,
                manual ? ExceptionStatus.MANUAL_REVIEW : ExceptionStatus.RECOVERING,
                "Service cancellation or refund requires reconciliation",
                manual ? "Automated settlement cannot complete without administrator review."
                        : "The provider refund outcome remains unresolved.",
                safeCode(row.errorCode(), manual ? "SERVICE_REFUND_MANUAL_REVIEW"
                        : "SERVICE_REFUND_RESULT_UNKNOWN"), row,
                List.of("State=" + safeCode(row.state(), "UNKNOWN"),
                        "RecoveryAttempts=" + row.attemptCount(),
                        "RefundScope=" + safeCode(row.detail(), "UNKNOWN")),
                ExceptionAction.REVIEW_SERVICE_REFUND,
                "/workplace/admin/service-fulfillment?order=" + row.parentId()
                        + "&adjustment=" + row.id(), now);
    }

    private ExceptionItem visitProvider(
            WorkplaceExceptionConsoleRepository.OperationalRow row, OffsetDateTime now) {
        boolean manual = "DEAD_LETTER".equals(row.state()) || row.attemptCount() >= 10;
        return operational("VISIT_PROVIDER:" + row.id(), ExceptionSource.VISIT_PROVIDER,
                manual ? ExceptionSeverity.CRITICAL : ExceptionSeverity.ERROR,
                manual ? ExceptionStatus.MANUAL_REVIEW : ExceptionStatus.ACTIVE,
                "Visit provider delivery requires review",
                manual ? "Visit-provider delivery exhausted its retry path."
                        : "Visitor or access-provider outcome is unknown.",
                safeCode(row.errorCode(), manual ? "VISIT_PROVIDER_RETRY_EXHAUSTED"
                        : "VISIT_PROVIDER_RESULT_UNKNOWN"), row,
                List.of("Operation=" + safeCode(row.operation(), "UNKNOWN"),
                        "State=" + safeCode(row.state(), "UNKNOWN"),
                        "Attempts=" + row.attemptCount()),
                ExceptionAction.REVIEW_VISIT,
                "/workplace/admin/visits?visit=" + row.parentId(), now);
    }

    private ExceptionItem accessPass(
            WorkplaceExceptionConsoleRepository.OperationalRow row, OffsetDateTime now) {
        boolean manual = "PAIRING_RETRY_EXHAUSTED".equals(row.state());
        return operational("ACCESS_PASS:" + row.id(), ExceptionSource.ACCESS_PASS,
                manual ? ExceptionSeverity.ERROR : ExceptionSeverity.CRITICAL,
                manual ? ExceptionStatus.MANUAL_REVIEW : ExceptionStatus.ACTIVE,
                manual ? "Access-pass pairing is locked"
                        : "Access-pass provider outcome is unknown",
                manual ? "Pairing attempts reached the governed limit."
                        : "Verify the access credential state before issuing another command.",
                safeCode(row.errorCode(), manual ? "ACCESS_PASS_PAIRING_RETRY_EXHAUSTED"
                        : "ACCESS_PASS_RESULT_UNKNOWN"), row,
                List.of("Operation=" + safeCode(row.operation(), "UNKNOWN"),
                        "State=" + safeCode(row.state(), "UNKNOWN"),
                        "Attempts=" + row.attemptCount(), "PassId=" + row.parentId()),
                ExceptionAction.REVIEW_ACCESS_PASS, null, now);
    }

    private ExceptionItem operational(
            String id, ExceptionSource source, ExceptionSeverity severity,
            ExceptionStatus status, String title, String impact, String code,
            WorkplaceExceptionConsoleRepository.OperationalRow row, List<String> evidence,
            ExceptionAction action, String href, OffsetDateTime now) {
        return new ExceptionItem(id, source, severity, status, title, impact, code,
                row.detectedAt(), row.version(), evidence, action, href,
                null, null, null, false, null, ageSeconds(row.detectedAt(), now));
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

    private static ExceptionSourceCoverage coverage(
            ExceptionSource source, long unresolvedCount, List<ExceptionItem> items) {
        int returned = Math.toIntExact(items.stream()
                .filter(item -> item.source() == source).count());
        return new ExceptionSourceCoverage(
                source, unresolvedCount, returned, returned < unresolvedCount);
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
        if (Set.of("SAFETY", "BOOKING", "DEVICE_COMMAND", "SERVICE_ORDER",
                "SERVICE_REFUND", "VISIT_PROVIDER", "ACCESS_PASS").contains(prefix)) {
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
        } else if ("SERVICE_PROVIDER".equals(prefix)) {
            if (!suffix.matches("[A-Za-z0-9._-]{1,80}")) {
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

    private static long ageSeconds(OffsetDateTime detectedAt, OffsetDateTime now) {
        if (detectedAt == null || now == null) return 0;
        return Math.max(0, Duration.between(detectedAt.toInstant(), now.toInstant()).getSeconds());
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

    private static BaseException notFound() {
        return new BaseException(
                ErrorCode.ENTITY_NOT_FOUND, "The Workplace exception was not found.");
    }

    private static void rejectOverflow(long unresolvedCount) {
        if (unresolvedCount > MAX_EXPORT_ROWS) {
            throw conflict("EXPORT_ROW_LIMIT_EXCEEDED: unresolvedCount=" + unresolvedCount
                    + ", maximumRows=" + MAX_EXPORT_ROWS
                    + ". Resolve exceptions or export a narrower governed scope.");
        }
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
