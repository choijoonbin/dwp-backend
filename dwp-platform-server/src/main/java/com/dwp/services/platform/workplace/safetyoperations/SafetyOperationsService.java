package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyClosureRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Service
public class SafetyOperationsService {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[ -]?){8,15}(?!\\d)");
    private final SafetyAudienceService audiences;
    private final SafetyIncidentRepository incidents;
    private final SafetyClosureRepository closures;
    private final SafetyConnectorService connectors;
    private final SafetyExportDocumentFactory exports;
    private final ObjectMapper mapper;
    private final Duration exportTtl;
    private final Clock clock;

    @Autowired
    public SafetyOperationsService(
            SafetyAudienceService audiences,
            SafetyIncidentRepository incidents,
            SafetyClosureRepository closures,
            SafetyConnectorService connectors,
            SafetyExportDocumentFactory exports,
            ObjectMapper mapper,
            @Value("${dwp.workplace.safety.export-ttl:PT15M}") Duration exportTtl) {
        this(audiences, incidents, closures, connectors, exports, mapper,
                exportTtl, Clock.systemUTC());
    }

    SafetyOperationsService(
            SafetyAudienceService audiences,
            SafetyIncidentRepository incidents,
            SafetyClosureRepository closures,
            SafetyConnectorService connectors,
            SafetyExportDocumentFactory exports,
            ObjectMapper mapper,
            Duration exportTtl,
            Clock clock) {
        this.audiences = audiences;
        this.incidents = incidents;
        this.closures = closures;
        this.connectors = connectors;
        this.exports = exports;
        this.mapper = mapper;
        this.exportTtl = positive(exportTtl, "exportTtl");
        this.clock = clock;
    }

    @Transactional
    public IncidentCommandResult activate(
            long tenantId, long actorId, String idempotencyKey,
            ActivateIncidentRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("ACTIVATE", request);
        incidents.lockCommandKey(tenantId, actorId, "ACTIVATE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "ACTIVATE", key).orElse(null);
        if (duplicate != null) return duplicateResult(tenantId, duplicate, fingerprint);
        PreviewRow preview = incidents.preview(tenantId, actorId, request.activationPreviewId())
                .orElseThrow(() -> notFound("The activation preview was not found."));
        OffsetDateTime now = now();
        if (!preview.expiresAt().isAfter(now)) throw conflict("The activation preview expired.");
        if (!preview.eligible()) throw conflict("The activation preview is not eligible.");
        UUID incidentId = UUID.randomUUID();
        AudienceSnapshot snapshot = audiences.snapshot(tenantId, "INCIDENT", incidentId,
                preview.siteId(), preview.floorIds(), preview.zoneIds(), preview.excludedKeys());
        if (snapshot.finalTargetCount() == 0) throw conflict("The current audience is empty.");
        String number = "INC-" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + incidentId.toString().substring(0, 8).toUpperCase();
        incidents.insertIncident(tenantId, actorId, incidentId, number, preview,
                snapshot.audienceSnapshotId(), now);
        String corr = correlation(correlationId);
        String href = "/v1/admin/workplace/safety/incidents/" + incidentId;
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "ACTIVATE", key, fingerprint, request.reason(), corr, href, now);
        incidents.insertDispatchBatch(tenantId, incidentId, command.id(),
                snapshot.audienceSnapshotId(), "ACTIVATION", preview.channels(), now,
                snapshot.members());
        incidents.audit(tenantId, incidentId, actorId, "safety.incident.activated",
                "INCIDENT", incidentId, corr,
                Map.of("incidentNumber", number, "audience", snapshot.finalTargetCount()), now);
        return new IncidentCommandResult(incident(tenantId, incidentId), receipt(command, false));
    }

    @Transactional(readOnly = true)
    public List<Incident> incidents(long tenantId, IncidentState state) {
        requireTenant(tenantId);
        return incidents.incidents(tenantId, state).stream()
                .map(row -> project(tenantId, row)).toList();
    }

    @Transactional(readOnly = true)
    public Incident incident(long tenantId, UUID incidentId) {
        requireTenant(tenantId);
        return project(tenantId, requireIncident(tenantId, incidentId));
    }

    @Transactional(readOnly = true)
    public List<SafetySheet> activeSheets(long tenantId, long userId) {
        requireActor(tenantId, userId);
        return incidents.activeForUser(tenantId, userId).stream()
                .map(row -> sheet(tenantId, userId, row)).toList();
    }

    @Transactional(readOnly = true)
    public SafetySheet sheet(long tenantId, long userId, UUID incidentId) {
        requireActor(tenantId, userId);
        IncidentRow row = incidents.incidentForUser(tenantId, userId, incidentId)
                .orElseThrow(() -> notFound("The safety incident is not visible to this user."));
        return sheet(tenantId, userId, row);
    }

    @Transactional
    public ResponseCommandResult respond(
            long tenantId, long userId, UUID incidentId, String idempotencyKey,
            SafetyResponseRequest request, String correlationId) {
        IncidentRow incident = requireUserIncident(tenantId, userId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        requireConfirmed(request.explicitConfirmation());
        if (request.response() == SafetyResponseState.NEEDS_HELP
                && (request.assistanceNote() == null || request.assistanceNote().isBlank())) {
            throw invalid("A needs-help response requires an assistance note.");
        }
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("RESPOND", request);
        incidents.lockCommandKey(tenantId, userId, "RESPOND", key);
        CommandRow duplicate = incidents.command(tenantId, userId, "RESPOND", key).orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            return new ResponseCommandResult(sheet(tenantId, userId, incident),
                    receipt(duplicate, true));
        }
        OffsetDateTime now = now();
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, userId, incidentId, "RESPOND",
                key, fingerprint, request.reason(), corr,
                "/v1/workplace/safety/incidents/" + incidentId, now);
        incidents.upsertResponse(tenantId, incidentId, userId, request, now);
        incidents.completeLocalCommand(tenantId, command.id(), "RESPONSE_RECORDED", now);
        incidents.audit(tenantId, incidentId, userId, "safety.response.recorded",
                "SAFETY_RESPONSE", incidentId, corr,
                Map.of("response", request.response().name()), now);
        return new ResponseCommandResult(sheet(tenantId, userId, incident),
                receipt(commandAsSucceeded(command, now), false));
    }

    @Transactional
    public MessageCommandResult userMessage(
            long tenantId, long userId, UUID incidentId, String idempotencyKey,
            MessageRequest request, String correlationId) {
        IncidentRow incident = requireUserIncident(tenantId, userId, incidentId);
        return message(tenantId, userId, incident, idempotencyKey, request,
                MessageDirection.USER_TO_COMMAND, correlationId);
    }

    @Transactional
    public MessageCommandResult adminMessage(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            MessageRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        IncidentRow incident = requireIncident(tenantId, incidentId);
        return message(tenantId, actorId, incident, idempotencyKey, request,
                request.targetUserId() == null ? MessageDirection.COMMAND_BROADCAST
                        : MessageDirection.COMMAND_TO_USER, correlationId);
    }

    @Transactional
    public AssemblyCommandResult confirmAssembly(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            AssemblyConfirmationRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        IncidentRow incident = requireIncident(tenantId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        if (!incidents.audienceContains(
                tenantId, incident.snapshotId(), request.subjectKeySha256())) {
            throw notFound("The assembly subject is not in the incident audience.");
        }
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("CONFIRM_ASSEMBLY", request);
        incidents.lockCommandKey(tenantId, actorId, "CONFIRM_ASSEMBLY", key);
        CommandRow duplicate = incidents.command(
                tenantId, actorId, "CONFIRM_ASSEMBLY", key).orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            AssemblyConfirmation existing = incidents.assemblyConfirmation(
                    tenantId, incidentId, request.subjectKeySha256()).orElseThrow();
            return new AssemblyCommandResult(existing, receipt(duplicate, true));
        }
        OffsetDateTime now = now();
        if (request.observedAt().isAfter(now.plusMinutes(5))) {
            throw invalid("Assembly observation time is in the future.");
        }
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "CONFIRM_ASSEMBLY", key, fingerprint, request.reason(), corr,
                "/v1/admin/workplace/safety/incidents/" + incidentId, now);
        AssemblyConfirmation confirmation = incidents.upsertAssembly(
                tenantId, incidentId, actorId, request, now)
                .orElseThrow(() -> conflict("The assembly confirmation version changed."));
        incidents.completeLocalCommand(tenantId, command.id(), "ASSEMBLY_RECORDED", now);
        incidents.audit(tenantId, incidentId, actorId, "safety.assembly.confirmed",
                "ASSEMBLY_CONFIRMATION", confirmation.assemblyConfirmationId(), corr,
                Map.of("confirmed", confirmation.confirmed(),
                        "evidenceReference", confirmation.evidenceReference()), now);
        return new AssemblyCommandResult(confirmation,
                receipt(commandAsSucceeded(command, now), false));
    }

    @Transactional(readOnly = true)
    public List<IncidentMessage> messages(long tenantId, long userId, UUID incidentId,
                                          boolean administrator) {
        if (administrator) requireIncident(tenantId, incidentId);
        else requireUserIncident(tenantId, userId, incidentId);
        return incidents.messages(tenantId, incidentId, administrator ? null : userId);
    }

    @Transactional
    public IncidentCommandResult applyScope(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            ApplyScopeRevisionRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("REVISE_SCOPE", request);
        incidents.lockCommandKey(tenantId, actorId, "REVISE_SCOPE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "REVISE_SCOPE", key).orElse(null);
        if (duplicate != null) return duplicateResult(tenantId, duplicate, fingerprint);
        IncidentRow incident = requireIncident(tenantId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        ScopeRow revision = incidents.scopeRevision(tenantId, incidentId, request.scopeRevisionId())
                .orElseThrow(() -> notFound("The scope revision preview was not found."));
        OffsetDateTime now = now();
        if (!"PREVIEW".equals(revision.state()) || !revision.expiresAt().isAfter(now)
                || revision.incidentVersion() != incident.version()) {
            throw conflict("The scope revision preview is stale or expired.");
        }
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "REVISE_SCOPE", key, fingerprint, request.reason(), corr,
                "/v1/admin/workplace/safety/incidents/" + incidentId, now);
        if (!incidents.applyScope(tenantId, incidentId, revision, incident.version(), now)) {
            throw conflict("The incident changed before the scope revision was applied.");
        }
        AudienceSnapshot snapshot = audiences.get(tenantId, revision.snapshotId(), true);
        incidents.insertDispatchBatch(tenantId, incidentId, command.id(), revision.snapshotId(),
                "SCOPE_REVISION", incident.channels(), now, snapshot.members());
        incidents.audit(tenantId, incidentId, actorId, "safety.scope.revised",
                "SCOPE_REVISION", revision.id(), corr,
                Map.of("targetCount", snapshot.finalTargetCount()), now);
        return new IncidentCommandResult(incident(tenantId, incidentId), receipt(command, false));
    }

    @Transactional
    public IncidentCommandResult resend(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            ResendRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        IncidentRow incident = requireIncident(tenantId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("RESEND", request);
        incidents.lockCommandKey(tenantId, actorId, "RESEND", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "RESEND", key).orElse(null);
        if (duplicate != null) return duplicateResult(tenantId, duplicate, fingerprint);
        List<UUID> eligibleIds = incidents.retryMemberIds(tenantId, incidentId, request.retryStates());
        AudienceSnapshot audience = audiences.get(tenantId, incident.snapshotId(), true);
        List<AudienceMember> members = audience.members().stream()
                .filter(member -> eligibleIds.contains(member.audienceMemberId())).toList();
        if (members.isEmpty()) throw conflict("No dispatch recipients match the selected retry states.");
        OffsetDateTime now = now();
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId, "RESEND",
                key, fingerprint, request.reason(), corr,
                "/v1/admin/workplace/safety/incidents/" + incidentId, now);
        incidents.insertDispatchBatch(tenantId, incidentId, command.id(), incident.snapshotId(),
                "RESEND", request.channels().stream().distinct().toList(), now, members);
        incidents.audit(tenantId, incidentId, actorId, "safety.dispatch.resent",
                "INCIDENT", incidentId, corr, Map.of("recipientCount", members.size()), now);
        return new IncidentCommandResult(incident(tenantId, incidentId), receipt(command, false));
    }

    @Transactional
    public ClosureCommandResult requestClosure(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            ClosureRequestInput request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        if (actorId == request.designatedApproverId()) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The closure requester cannot be the final approver.");
        }
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("REQUEST_CLOSURE", request);
        incidents.lockCommandKey(tenantId, actorId, "REQUEST_CLOSURE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "REQUEST_CLOSURE", key)
                .orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            ClosureRow existing = closures.closureByCommand(tenantId, duplicate.id())
                    .orElseThrow(() -> conflict("The replayed closure request is missing."));
            return new ClosureCommandResult(closure(existing), receipt(duplicate, true));
        }
        IncidentRow incident = requireIncident(tenantId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        ClosurePreview preview = closures.closurePreview(tenantId, actorId, request.closurePreviewId())
                .orElseThrow(() -> notFound("The closure preview was not found."));
        OffsetDateTime now = now();
        if (!preview.expiresAt().isAfter(now) || preview.incidentVersion() != incident.version()) {
            throw conflict("The closure preview is stale or expired.");
        }
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "REQUEST_CLOSURE", key, fingerprint, request.reason(), corr,
                "/v1/admin/workplace/safety/incidents/" + incidentId, now);
        ClosureRow created = closures.insertClosure(
                tenantId, actorId, incidentId, command.id(), preview, request, now)
                .orElseThrow(() -> conflict("The incident changed before closure was requested."));
        incidents.completeLocalCommand(tenantId, command.id(), "CLOSURE_REQUESTED", now);
        incidents.audit(tenantId, incidentId, actorId, "safety.closure.requested",
                "CLOSURE_REQUEST", created.id(), corr,
                Map.of("needsHelp", preview.needsHelpCount(), "noResponse", preview.noResponseCount()), now);
        return new ClosureCommandResult(closure(created),
                receipt(commandAsSucceeded(command, now), false));
    }

    @Transactional
    public IncidentCommandResult approveClosure(
            long tenantId, long actorId, UUID incidentId, UUID closureId,
            String idempotencyKey, ClosureApprovalInput request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("APPROVE_CLOSURE", request);
        incidents.lockCommandKey(tenantId, actorId, "APPROVE_CLOSURE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "APPROVE_CLOSURE", key)
                .orElse(null);
        if (duplicate != null) return duplicateResult(tenantId, duplicate, fingerprint);
        IncidentRow incident = requireIncident(tenantId, incidentId);
        if (incident.state() != IncidentState.CLOSURE_PENDING) {
            throw conflict("The incident is not waiting for closure approval.");
        }
        ClosureRow closure = closures.closure(tenantId, incidentId, closureId)
                .orElseThrow(() -> notFound("The closure request was not found."));
        if (closure.requestedBy() == actorId) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The closure requester cannot approve the request.");
        }
        OffsetDateTime now = now();
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "APPROVE_CLOSURE", key, fingerprint, request.reason(), corr,
                "/v1/admin/workplace/safety/incidents/" + incidentId, now);
        if (!closures.approveClosure(tenantId, incidentId, closure, actorId, request, now)) {
            throw conflict("The closure request or incident version changed.");
        }
        incidents.completeLocalCommand(tenantId, command.id(),
                request.approved() ? "CLOSURE_APPROVED" : "CLOSURE_REJECTED", now);
        if (request.approved()) createReport(tenantId, incidentId, actorId, now);
        incidents.audit(tenantId, incidentId, actorId, "safety.closure.decided",
                "CLOSURE_REQUEST", closureId, corr, Map.of("approved", request.approved()), now);
        return new IncidentCommandResult(incident(tenantId, incidentId),
                receipt(commandAsSucceeded(command, now), false));
    }

    @Transactional(readOnly = true)
    public PostIncidentReport report(long tenantId, UUID incidentId) {
        requireIncident(tenantId, incidentId);
        return closures.report(tenantId, incidentId)
                .orElseThrow(() -> notFound("The post-incident report is not available."));
    }

    @Transactional
    public ExportCommandResult createExport(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            GuardedExportRequest request, String correlationId, String stepUpEvidence) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        IncidentRow incident = requireIncident(tenantId, incidentId);
        if (incident.version() != request.expectedIncidentVersion()) {
            throw conflict("The incident version changed before export.");
        }
        PostIncidentReport report = closures.report(tenantId, incidentId)
                .orElseGet(() -> createReport(tenantId, incidentId, actorId, now()));
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("CREATE_EXPORT", request);
        incidents.lockCommandKey(tenantId, actorId, "CREATE_EXPORT", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "CREATE_EXPORT", key)
                .orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            GuardedExport replay = closures.exportByCommand(tenantId, duplicate.id())
                    .orElseThrow(() -> conflict("The replayed export artifact is missing."));
            return new ExportCommandResult(replay, receipt(duplicate, true));
        }
        OffsetDateTime now = now();
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "CREATE_EXPORT", key, fingerprint, request.reason(), corr,
                "/v1/admin/workplace/safety/incidents/" + incidentId, now);
        byte[] payload = exports.create(request.format(), incident, report,
                new SafetyExportDocumentFactory.ExportEvidence(request.purpose(), request.reason(),
                        actorId, corr, stepUpEvidence, now));
        GuardedExport export = closures.insertExport(tenantId, incidentId, command.id(), actorId,
                request.format(), request.purpose(), request.reason(), corr, stepUpEvidence,
                payload, now,
                now.plus(exportTtl));
        incidents.completeLocalCommand(tenantId, command.id(), "EXPORT_CREATED", now);
        incidents.audit(tenantId, incidentId, actorId, "safety.export.created",
                "GUARDED_EXPORT", export.exportId(), corr,
                Map.of("format", request.format().name(), "purpose", request.purpose(),
                        "reason", request.reason(), "requestedBy", actorId,
                        "stepUpEvidence", stepUpEvidence, "sha256", export.sha256()), now);
        return new ExportCommandResult(export, receipt(commandAsSucceeded(command, now), false));
    }

    @Transactional
    public ExportContent export(long tenantId, long actorId, UUID exportId,
                                String correlationId) {
        requireActor(tenantId, actorId);
        ExportContent content = closures.export(tenantId, exportId, now())
                .orElseThrow(() -> notFound("The guarded export is missing or expired."));
        incidents.audit(tenantId, content.metadata().incidentId(), actorId,
                "safety.export.downloaded", "GUARDED_EXPORT", exportId,
                correlation(correlationId), Map.of("sha256", content.metadata().sha256()), now());
        return content;
    }

    @Transactional
    public boolean recordDispatchOutcome(DispatchOutcome outcome) {
        if (outcome.tenantId() <= 0 || outcome.state() == AttemptState.QUEUED
                || outcome.state() == AttemptState.DISPATCHING
                || outcome.state() == AttemptState.OFFLINE_QUEUED) {
            throw invalid("Only terminal provider dispatch outcomes may be recorded.");
        }
        return incidents.recordOutcome(outcome);
    }

    @Transactional(readOnly = true)
    public CommandReceipt command(long tenantId, UUID incidentId, UUID commandId) {
        CommandRow command = incidents.command(tenantId, incidentId, commandId)
                .orElseThrow(() -> notFound("The safety command was not found."));
        return receipt(command, false);
    }

    private MessageCommandResult message(
            long tenantId, long actorId, IncidentRow incident, String idempotencyKey,
            MessageRequest request, MessageDirection direction, String correlationId) {
        requireActiveVersion(incident, request.expectedIncidentVersion());
        requireConfirmed(request.explicitConfirmation());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("SEND_MESSAGE", request);
        incidents.lockCommandKey(tenantId, actorId, "SEND_MESSAGE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "SEND_MESSAGE", key).orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            IncidentMessage existing = incidents.messageByCommand(tenantId, duplicate.id())
                    .orElseThrow(() -> conflict("The replayed message resource is missing."));
            return new MessageCommandResult(existing, receipt(duplicate, true));
        }
        OffsetDateTime now = now();
        String corr = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incident.id(),
                "SEND_MESSAGE", key, fingerprint, request.reason(), corr,
                "/v1/workplace/safety/incidents/" + incident.id(), now);
        IncidentMessage message = incidents.insertMessage(
                tenantId, incident.id(), command.id(), actorId,
                request.targetUserId(), direction, maskPii(request.body()), now);
        incidents.outbox(tenantId, command.id(), "SEND_MESSAGE", message.messageId(),
                "message:" + message.messageId(), Map.of("messageId", message.messageId()), now);
        incidents.completeLocalCommand(tenantId, command.id(), "MESSAGE_QUEUED", now);
        incidents.audit(tenantId, incident.id(), actorId, "safety.message.queued",
                "MESSAGE", message.messageId(), corr, Map.of("direction", direction.name()), now);
        return new MessageCommandResult(message, receipt(commandAsSucceeded(command, now), false));
    }

    private Incident project(long tenantId, IncidentRow row) {
        AudienceSnapshot audience = audiences.get(tenantId, row.snapshotId(), true);
        return new Incident(row.id(), row.number(), row.type(), row.severity(), row.state(),
                row.siteId(), row.floorIds(), row.zoneIds(), row.message(), row.safetyAction(),
                row.assemblyPoint(), row.channels(), audience,
                incidents.responseSummary(tenantId, row.id(), row.snapshotId()),
                incidents.assemblySummary(tenantId, row.id(), row.snapshotId()),
                incidents.dispatches(tenantId, row.id()), connectors.truth(tenantId), row.version(),
                row.activatedAt(), row.closedAt(), row.updatedAt());
    }

    private SafetySheet sheet(long tenantId, long userId, IncidentRow incident) {
        SafetyResponseState response = incidents.response(tenantId, incident.id(), userId)
                .map(ResponseRow::state).orElse(null);
        List<String> scopes = new ArrayList<>();
        scopes.add("SITE:" + incident.siteId());
        incident.floorIds().forEach(id -> scopes.add("FLOOR:" + id));
        incident.zoneIds().forEach(id -> scopes.add("ZONE:" + id));
        return new SafetySheet(incident.id(), incident.number(), incident.severity(),
                incident.message(), incident.safetyAction(), incident.assemblyPoint(), scopes,
                response, "Contact the site security desk or local emergency services.",
                incident.version(), incident.updatedAt());
    }

    private PostIncidentReport createReport(
            long tenantId, UUID incidentId, long actorId, OffsetDateTime now) {
        Incident incident = incident(tenantId, incidentId);
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("incidentNumber", incident.incidentNumber());
        summary.put("state", incident.state().name());
        summary.put("targetCount", incident.audience().finalTargetCount());
        summary.put("safeCount", incident.responses().safe());
        summary.put("needsHelpCount", incident.responses().needsHelp());
        summary.put("noResponseCount", incident.responses().noResponse());
        summary.put("assemblyConfirmed", incident.assembly().confirmed());
        summary.put("assemblyPending", incident.assembly().pending());
        summary.put("dispatches", incident.dispatches().size());
        summary.put("closedAt", incident.closedAt() == null ? "" : incident.closedAt().toString());
        return closures.createReport(tenantId, incidentId, actorId, summary, now);
    }

    private IncidentCommandResult duplicateResult(
            long tenantId, CommandRow command, String fingerprint) {
        requireFingerprint(command, fingerprint);
        return new IncidentCommandResult(incident(tenantId, command.incidentId()),
                receipt(command, true));
    }

    private void requireFingerprint(CommandRow command, String fingerprint) {
        if (!command.fingerprint().equals(fingerprint)) {
            throw conflict("The idempotency key was used for a different safety command.");
        }
    }

    private CommandReceipt receipt(CommandRow row, boolean replay) {
        return new CommandReceipt(row.id(), row.state(), row.statusHref(), replay,
                row.correlationId(), row.acceptedAt());
    }

    private CommandRow commandAsSucceeded(CommandRow row, OffsetDateTime now) {
        return new CommandRow(row.id(), row.incidentId(), row.type(), row.fingerprint(),
                CommandState.SUCCEEDED, row.reason(), row.correlationId(), row.statusHref(),
                row.resultCode(), row.providerReference(), row.version() + 1,
                row.acceptedAt(), now, now);
    }

    private ClosureRequest closure(ClosureRow row) {
        return new ClosureRequest(row.id(), row.incidentId(), row.requestedBy(),
                row.designatedApproverId(), row.reason(), row.followUpActions(), row.state(),
                row.version(), row.requestedAt());
    }

    private IncidentRow requireIncident(long tenantId, UUID incidentId) {
        requireTenant(tenantId);
        return incidents.incident(tenantId, incidentId)
                .orElseThrow(() -> notFound("The safety incident was not found."));
    }

    private IncidentRow requireUserIncident(long tenantId, long userId, UUID incidentId) {
        requireActor(tenantId, userId);
        return incidents.incidentForUser(tenantId, userId, incidentId)
                .orElseThrow(() -> notFound("The safety incident is not visible to this user."));
    }

    private static void requireActiveVersion(IncidentRow incident, long expectedVersion) {
        if (incident.state() != IncidentState.ACTIVE || incident.version() != expectedVersion) {
            throw conflict("The active incident version changed.");
        }
    }

    private String fingerprint(String type, Object request) {
        try { return SafetyRepositorySupport.sha256(type + ":" + mapper.writeValueAsString(request)); }
        catch (JsonProcessingException exception) { throw invalid("The safety command is invalid."); }
    }

    private static String maskPii(String value) {
        return PHONE.matcher(EMAIL.matcher(value).replaceAll("[email redacted]"))
                .replaceAll("[phone redacted]");
    }

    private static <T> List<T> distinct(List<T> values) {
        return values.stream().distinct().toList();
    }

    private static void validateScope(List<UUID> floors, List<UUID> zones) {
        if (floors == null || zones == null || (floors.isEmpty() && zones.isEmpty())) {
            throw invalid("At least one floor or zone is required.");
        }
    }

    private static String key(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw invalid("A bounded Idempotency-Key is required.");
        }
        return value.trim();
    }

    private static String correlation(String value) {
        try { return SafetyRepositorySupport.correlation(value); }
        catch (IllegalArgumentException exception) { throw invalid("Correlation ID is too long."); }
    }

    private static void requireConfirmed(boolean confirmed) {
        if (!confirmed) throw invalid("Explicit confirmation is required.");
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw invalid("A positive actor user ID is required.");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant ID is required.");
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }
}
