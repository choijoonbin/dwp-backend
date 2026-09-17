package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportRepository.*;

@Service
public class WorkplaceSpacePlanningBoardReportService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    private static final Duration CONTENT_TTL = Duration.ofHours(24);

    private final WorkplaceSpacePlanningBoardReportRepository repository;
    private final WorkplaceSpacePlanningBoardReportRenderer renderer;
    private final WorkplaceDelegatedAdminScopeGuard scopeGuard;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public WorkplaceSpacePlanningBoardReportService(
            WorkplaceSpacePlanningBoardReportRepository repository,
            WorkplaceSpacePlanningBoardReportRenderer renderer,
            WorkplaceDelegatedAdminScopeGuard scopeGuard,
            ObjectMapper objectMapper) {
        this(repository, renderer, scopeGuard, objectMapper, Clock.systemUTC());
    }

    WorkplaceSpacePlanningBoardReportService(
            WorkplaceSpacePlanningBoardReportRepository repository,
            WorkplaceSpacePlanningBoardReportRenderer renderer,
            WorkplaceDelegatedAdminScopeGuard scopeGuard,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.renderer = renderer;
        this.scopeGuard = scopeGuard;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ReportPreview preview(
            long tenantId,
            long actorId,
            UUID siteId,
            String idempotencyKey,
            PreviewRequest request,
            String correlationId,
            WorkplaceDelegatedAdminAccessScope requestedScope) {
        requireActor(tenantId, actorId);
        if (siteId == null || request == null || request.scenarioId() == null
                || request.expectedScenarioVersion() == null || request.format() == null) {
            throw invalid("A site, scenario, expected version and report format are required.");
        }
        String key = idempotencyKey(idempotencyKey);
        String reason = reason(request.reason());
        String correlation = correlation(correlationId);
        String fingerprint = sha256("PREVIEW\n" + siteId + "\n" + request.scenarioId()
                + "\n" + request.expectedScenarioVersion() + "\n" + request.format()
                + "\n" + reason);

        repository.lockPreviewKey(tenantId, actorId, key);
        PreviewRow existing = repository.previewByIdempotency(tenantId, actorId, key);
        if (existing != null) {
            requireFingerprint(existing.requestFingerprint(), fingerprint,
                    "The idempotency key was used for another board-report preview.");
            requireScope(requestedScope, tenantId, existing.siteId(), existing.floorId());
            return preview(existing, true);
        }

        ReportSource source = repository.lockReportSource(tenantId, request.scenarioId());
        if (source == null) {
            throw notFound("The space-planning scenario was not found in this tenant.");
        }
        if (!siteId.equals(source.siteId())) {
            throw forbidden("The requested site does not own this planning scenario.");
        }
        requireScope(requestedScope, tenantId, source.siteId(), source.floorId());
        if (source.scenarioVersion() != request.expectedScenarioVersion()) {
            throw versionConflict("The scenario changed. Refresh before previewing the report.");
        }
        if ("DRAFT".equals(source.scenarioState()) || source.comparisonJson() == null) {
            throw conflict("Create a current planning scenario preview before exporting a board report.");
        }

        OffsetDateTime now = now();
        ReportSnapshot snapshot = snapshot(source, now);
        String snapshotJson = json(snapshot);
        String snapshotHash = sha256(snapshotJson);
        PreviewRow created = new PreviewRow(
                UUID.randomUUID(), tenantId, actorId, source.scenarioId(), source.siteId(),
                source.floorId(), request.format(), source.scenarioVersion(), 1L, snapshotJson,
                snapshotHash, UUID.randomUUID().toString(), reason, key, fingerprint, correlation,
                now, now.plus(PREVIEW_TTL));
        repository.insertPreview(created);
        repository.audit(tenantId, actorId, source.scenarioId(), created.previewId(), null,
                "PREVIEW_CREATED", correlation, evidence("format", request.format().name(),
                        "scenarioVersion", source.scenarioVersion(),
                        "snapshotSha256", snapshotHash,
                        "reasonSha256", sha256(reason)), now);
        return preview(created, false);
    }

    @Transactional
    public ReportReceipt execute(
            long tenantId,
            long actorId,
            UUID siteId,
            String idempotencyKey,
            ExecuteRequest request,
            String correlationId,
            String decisionRevision,
            WorkplaceDelegatedAdminAccessScope requestedScope) {
        requireActor(tenantId, actorId);
        if (siteId == null || request == null || request.previewId() == null
                || request.expectedPreviewVersion() == null
                || request.expectedScenarioVersion() == null
                || !request.explicitConfirmation()) {
            throw invalid("A current preview, expected versions and explicit confirmation are required.");
        }
        String revision = decisionRevision(decisionRevision);
        String key = idempotencyKey(idempotencyKey);
        String reason = reason(request.reason());
        String token = token(request.confirmationToken());
        String correlation = correlation(correlationId);
        String fingerprint = sha256("EXECUTE\n" + siteId + "\n" + request.previewId()
                + "\n" + request.expectedPreviewVersion() + "\n"
                + request.expectedScenarioVersion() + "\n" + token + "\n" + reason
                + "\ntrue\n" + revision);

        repository.lockCommandKey(tenantId, actorId, key);
        CommandRow existing = repository.commandByIdempotency(tenantId, actorId, key);
        if (existing != null) {
            requireFingerprint(existing.requestFingerprint(), fingerprint,
                    "The idempotency key was used for another board-report command.");
            requireScope(requestedScope, tenantId, existing.siteId(), existing.floorId());
            return receipt(existing, true);
        }

        PreviewRow preview = repository.lockPreview(tenantId, actorId, request.previewId());
        OffsetDateTime now = now();
        if (preview == null || !preview.expiresAt().isAfter(now)) {
            throw conflict("The board-report preview is missing or expired.");
        }
        if (!siteId.equals(preview.siteId())) {
            throw forbidden("The requested site does not own this report preview.");
        }
        requireScope(requestedScope, tenantId, preview.siteId(), preview.floorId());
        if (preview.previewVersion() != request.expectedPreviewVersion()
                || preview.scenarioVersion() != request.expectedScenarioVersion()) {
            throw versionConflict("The board-report preview or scenario version changed.");
        }
        if (!constantTimeEquals(preview.confirmationToken(), token)) {
            throw conflict("The report confirmation token is invalid or stale.");
        }
        ReportSnapshot snapshot = fromJson(preview.snapshotJson(), ReportSnapshot.class);
        if (!constantTimeEquals(preview.snapshotSha256(), sha256(json(snapshot)))) {
            throw conflict("The board-report preview integrity check failed.");
        }

        ReportSource current = repository.lockReportSource(tenantId, preview.scenarioId());
        if (current == null || current.scenarioVersion() != preview.scenarioVersion()) {
            throw versionConflict("The scenario changed after the board-report preview.");
        }
        if (!current.siteId().equals(preview.siteId())
                || !java.util.Objects.equals(current.floorId(), preview.floorId())) {
            throw conflict("The planning scope changed after the board-report preview.");
        }

        var rendered = renderer.render(preview.format(), snapshot);
        byte[] payload = rendered.payload();
        String contentHash = sha256(payload);
        CommandRow created = new CommandRow(
                UUID.randomUUID(), tenantId, actorId, preview.previewId(), preview.scenarioId(),
                preview.siteId(), preview.floorId(), preview.format(), preview.scenarioVersion(),
                preview.previewVersion(), ReportCommandState.SUCCEEDED, 1L, reason, revision,
                rendered.mimeType(), rendered.fileName(), payload, payload.length, contentHash,
                key, fingerprint, correlation, now, now, now.plus(CONTENT_TTL));
        repository.insertCommand(created);
        repository.audit(tenantId, actorId, preview.scenarioId(), preview.previewId(),
                created.commandId(), "REPORT_EXPORTED", correlation,
                evidence("format", preview.format().name(),
                        "scenarioVersion", preview.scenarioVersion(),
                        "contentSha256", contentHash,
                        "byteSize", payload.length,
                        "decisionRevision", revision,
                        "reasonSha256", sha256(reason)), now);
        return receipt(created, false);
    }

    @Transactional
    public ReportReceipt receipt(
            long tenantId,
            long actorId,
            UUID siteId,
            UUID commandId,
            WorkplaceDelegatedAdminAccessScope requestedScope) {
        requireActor(tenantId, actorId);
        if (siteId == null || commandId == null) {
            throw invalid("A site and report command identifier are required.");
        }
        CommandRow command = requireCommand(tenantId, actorId, commandId);
        if (!siteId.equals(command.siteId())) {
            throw forbidden("The requested site does not own this report command.");
        }
        requireScope(requestedScope, tenantId, command.siteId(), command.floorId());
        return receipt(command, false);
    }

    @Transactional
    public ReportContent content(
            long tenantId,
            long actorId,
            UUID siteId,
            UUID commandId,
            String correlationId,
            String decisionRevision,
            WorkplaceDelegatedAdminAccessScope requestedScope) {
        requireActor(tenantId, actorId);
        String revision = decisionRevision(decisionRevision);
        CommandRow command = requireCommand(tenantId, actorId, commandId);
        if (siteId == null || !siteId.equals(command.siteId())) {
            throw forbidden("The requested site does not own this report content.");
        }
        requireScope(requestedScope, tenantId, command.siteId(), command.floorId());
        OffsetDateTime now = now();
        if (!command.expiresAt().isAfter(now)) {
            throw notFound("The board-report content expired.");
        }
        byte[] payload = command.documentContent();
        if (payload.length != command.documentSize()
                || !constantTimeEquals(command.contentSha256(), sha256(payload))) {
            throw conflict("The board-report content integrity check failed.");
        }
        String correlation = correlation(correlationId);
        repository.audit(tenantId, actorId, command.scenarioId(), command.previewId(),
                command.commandId(), "CONTENT_ACCESSED", correlation,
                evidence("contentSha256", command.contentSha256(),
                        "byteSize", command.documentSize(),
                        "decisionRevision", revision), now);
        return new ReportContent(receipt(command, false), payload);
    }

    private CommandRow requireCommand(long tenantId, long actorId, UUID commandId) {
        CommandRow command = repository.command(tenantId, actorId, commandId);
        if (command == null) {
            throw notFound("The board-report command was not found for this actor and tenant.");
        }
        return command;
    }

    private ReportSnapshot snapshot(ReportSource source, OffsetDateTime capturedAt) {
        JsonNode comparison = tree(source.comparisonJson());
        JsonNode forecast = tree(source.forecastJson());
        JsonNode metrics = forecast.path("recommendationMetrics");
        JsonNode emission = tree(source.emissionJson());
        return new ReportSnapshot(
                source.scenarioId(), source.scenarioVersion(), source.scenarioName(),
                source.scenarioState(), source.siteId(), source.siteCode(), source.siteName(),
                source.floorId(), source.floorName(), source.windowStart(), source.windowEnd(),
                integer(comparison, "currentCapacity"), source.proposedCapacity(),
                integer(comparison, "currentRoomCapacity"), source.proposedRoomCapacity(),
                integer(comparison, "currentAccessibleResourceCount"),
                source.proposedAccessibleResourceCount(),
                decimal(comparison, "currentUtilizationPercent"),
                decimal(comparison, "proposedUtilizationPercent"),
                decimal(metrics, "peakDemand"), decimal(metrics, "confidencePercent"),
                text(forecast, "state"), text(forecast, "calculationVersion"),
                decimal(emission, "energyValue"), text(emission, "energyUnit"),
                decimal(emission, "co2eValue"), text(emission, "co2eUnit"),
                text(emission, "factorVersion"), text(emission, "regionCode"),
                source.affectedResourceCount(), source.impactedBookingCount(), false, 0,
                capturedAt);
    }

    private ReportPreview preview(PreviewRow row, boolean replay) {
        return new ReportPreview(row.previewId(), row.scenarioId(), row.siteId(), row.floorId(),
                row.format(), row.scenarioVersion(), row.previewVersion(),
                row.confirmationToken(), row.snapshotSha256(),
                fromJson(row.snapshotJson(), ReportSnapshot.class), row.createdAt(), row.expiresAt(),
                replay);
    }

    private ReportReceipt receipt(CommandRow row, boolean replay) {
        String href = row.expiresAt().isAfter(now())
                ? "/v1/admin/workplace/space-planning/reports/" + row.commandId()
                + "/content?siteId=" + row.siteId()
                : null;
        return new ReportReceipt(row.commandId(), row.previewId(), row.scenarioId(), row.siteId(),
                row.floorId(), row.format(), row.state(), row.scenarioVersion(),
                row.commandVersion(), row.mimeType(), row.fileName(), row.documentSize(),
                row.contentSha256(), href, row.acceptedAt(), row.completedAt(), row.expiresAt(),
                replay, row.correlationId());
    }

    private void requireScope(
            WorkplaceDelegatedAdminAccessScope requested,
            long tenantId,
            UUID siteId,
            UUID floorId) {
        if (requested == null) {
            throw forbidden("A current Workplace planning scope is required.");
        }
        WorkplaceDelegatedAdminAccessScope current = scopeGuard.revalidate(requested);
        if (current.tenantId() != tenantId || !siteId.equals(current.siteId())) {
            throw forbidden("The Workplace planning scope changed.");
        }
        if (floorId == null) current.requireSiteWide(); else current.requireFloor(floorId);
    }

    private JsonNode tree(String value) {
        if (value == null || value.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode result = objectMapper.readTree(value);
            return result == null || !result.isObject() ? objectMapper.createObjectNode() : result;
        } catch (JsonProcessingException exception) {
            throw conflict("The persisted planning evidence is unreadable.");
        }
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.intValue() : 0;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private String evidence(Object... pairs) {
        var node = objectMapper.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) {
            String key = pairs[index].toString();
            Object value = pairs[index + 1];
            if (value instanceof Number number) node.put(key, number.longValue());
            else if (value instanceof Boolean bool) node.put(key, bool);
            else node.put(key, value == null ? null : value.toString());
        }
        return node.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the board-report snapshot.", exception);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw conflict("The persisted board-report snapshot is unreadable.");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String idempotencyKey(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{1,160}")) {
            throw invalid("Idempotency-Key must contain 1 to 160 visible ASCII characters.");
        }
        return value;
    }

    private static String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 500
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("A reason of at most 500 characters is required.");
        }
        return value.trim();
    }

    private static String token(String value) {
        if (value == null || !value.matches("[0-9a-f-]{36}")) {
            throw invalid("A valid board-report confirmation token is required.");
        }
        return value;
    }

    private static String decisionRevision(String value) {
        if (value == null || !value.matches("psr-[0-9a-f]{64}")) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "A current Workplace export authority revision is required.");
        }
        return value;
    }

    private static String correlation(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (normalized.length() > 160
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("The correlation identifier is invalid.");
        }
        return normalized;
    }

    private static void requireActor(long tenantId, long actorId) {
        if (tenantId <= 0) throw new BaseException(
                ErrorCode.TENANT_MISSING, "A positive tenant identifier is required.");
        if (actorId <= 0) throw invalid("A positive actor identifier is required.");
    }

    private static void requireFingerprint(String existing, String expected, String message) {
        if (!constantTimeEquals(existing, expected)) throw conflict(message);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
