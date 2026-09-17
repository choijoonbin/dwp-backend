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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyClosureRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Service
public class SafetyPreviewService {
    private final SafetyAudienceService audiences;
    private final SafetyIncidentRepository incidents;
    private final SafetyClosureRepository closures;
    private final SafetyConnectorService connectors;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public SafetyPreviewService(
            SafetyAudienceService audiences,
            SafetyIncidentRepository incidents,
            SafetyClosureRepository closures,
            SafetyConnectorService connectors,
            ObjectMapper mapper,
            @Value("${dwp.workplace.safety.preview-ttl:PT10M}") Duration ttl) {
        this(audiences, incidents, closures, connectors, mapper, ttl, Clock.systemUTC());
    }

    SafetyPreviewService(
            SafetyAudienceService audiences,
            SafetyIncidentRepository incidents,
            SafetyClosureRepository closures,
            SafetyConnectorService connectors,
            ObjectMapper mapper,
            Duration ttl,
            Clock clock) {
        this.audiences = audiences;
        this.incidents = incidents;
        this.closures = closures;
        this.connectors = connectors;
        this.mapper = mapper;
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("preview ttl");
        this.ttl = ttl;
        this.clock = clock;
    }

    @Transactional
    public ActivationPreviewResult activation(
            long tenantId, long actorId, String idempotencyKey,
            ActivationPreviewRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        validateScope(request.floorIds(), request.zoneIds());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("PREVIEW_ACTIVATION", request);
        incidents.lockCommandKey(tenantId, actorId, "PREVIEW_ACTIVATION", key);
        CommandRow duplicate = incidents.command(
                tenantId, actorId, "PREVIEW_ACTIVATION", key).orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            PreviewRow row = incidents.previewByCommand(tenantId, duplicate.id()).orElseThrow();
            return new ActivationPreviewResult(activationProjection(tenantId, row),
                    receipt(duplicate, true));
        }
        UUID previewId = UUID.randomUUID();
        OffsetDateTime now = now();
        String correlation = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, null,
                "PREVIEW_ACTIVATION", key, fingerprint, request.reason(), correlation,
                "/v1/admin/workplace/safety/activation-previews/" + previewId, now);
        AudienceSnapshot audience = audiences.snapshot(tenantId, "ACTIVATION_PREVIEW", previewId,
                request.siteId(), distinct(request.floorIds()), distinct(request.zoneIds()),
                request.excludedSubjectKeys().stream().distinct().toList());
        List<ConnectorTruth> truth = connectors.truth(tenantId);
        List<String> limitations = audienceLimitations(audience);
        boolean viableChannel = false;
        for (DeliveryChannel channel : request.channels().stream().distinct().toList()) {
            ConnectorKind external = connectors.requiredConnector(channel);
            if (external == null) viableChannel = true;
            else {
                ConnectorTruth connector = truth.stream().filter(value -> value.kind() == external)
                        .findFirst().orElseThrow();
                if (connector.state() == ConnectorTruthState.READY) viableChannel = true;
                else limitations.add(channel.name() + "_PROVIDER_" + connector.state().name());
            }
        }
        if (audience.finalTargetCount() == 0) limitations.add("AUDIENCE_EMPTY");
        boolean eligible = audience.finalTargetCount() > 0 && viableChannel;
        incidents.insertPreview(tenantId, actorId, previewId, command.id(), request,
                audience.audienceSnapshotId(), eligible, limitations, now.plus(ttl), now);
        incidents.completeLocalCommand(tenantId, command.id(), "ACTIVATION_PREVIEW_CREATED", now);
        incidents.audit(tenantId, null, actorId, "safety.activation.previewed",
                "ACTIVATION_PREVIEW", previewId, correlation,
                java.util.Map.of("targetCount", audience.finalTargetCount(), "eligible", eligible), now);
        PreviewRow row = incidents.preview(tenantId, previewId).orElseThrow();
        return new ActivationPreviewResult(activationProjection(tenantId, row),
                receipt(succeeded(command, now, "ACTIVATION_PREVIEW_CREATED"), false));
    }

    @Transactional(readOnly = true)
    public ActivationPreview activation(long tenantId, UUID previewId) {
        return activationProjection(tenantId, incidents.preview(tenantId, previewId)
                .orElseThrow(() -> notFound("The activation preview was not found.")));
    }

    @Transactional
    public ScopePreviewCommandResult scope(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            ScopeRevisionPreviewRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("PREVIEW_SCOPE:" + incidentId, request);
        incidents.lockCommandKey(tenantId, actorId, "PREVIEW_SCOPE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "PREVIEW_SCOPE", key)
                .orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            ScopeRow row = incidents.scopeRevisionByCommand(tenantId, duplicate.id()).orElseThrow();
            return new ScopePreviewCommandResult(scopeProjection(tenantId, row),
                    receipt(duplicate, true));
        }
        IncidentRow incident = requireIncident(tenantId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = now();
        String correlation = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "PREVIEW_SCOPE", key, fingerprint, request.reason(), correlation,
                "/v1/admin/workplace/safety/incidents/" + incidentId
                        + "/scope-revisions/" + revisionId, now);
        AudienceSnapshot revised = audiences.snapshot(tenantId, "SCOPE_REVISION", revisionId,
                incident.siteId(), distinct(request.floorIds()), distinct(request.zoneIds()),
                request.excludedSubjectKeys().stream().distinct().toList());
        incidents.insertScopeRevision(tenantId, actorId, revisionId, command.id(), incident,
                request, revised.audienceSnapshotId(), now.plus(ttl), now);
        incidents.completeLocalCommand(tenantId, command.id(), "SCOPE_PREVIEW_CREATED", now);
        incidents.audit(tenantId, incidentId, actorId, "safety.scope.previewed",
                "SCOPE_REVISION", revisionId, correlation,
                java.util.Map.of("targetCount", revised.finalTargetCount()), now);
        ScopeRow row = incidents.scopeRevision(tenantId, incidentId, revisionId).orElseThrow();
        return new ScopePreviewCommandResult(scopeProjection(tenantId, row),
                receipt(succeeded(command, now, "SCOPE_PREVIEW_CREATED"), false));
    }

    @Transactional(readOnly = true)
    public ScopeRevisionPreview scope(long tenantId, UUID incidentId, UUID revisionId) {
        ScopeRow row = incidents.scopeRevision(tenantId, incidentId, revisionId)
                .orElseThrow(() -> notFound("The scope revision preview was not found."));
        return scopeProjection(tenantId, row);
    }

    @Transactional
    public ClosurePreviewCommandResult closure(
            long tenantId, long actorId, UUID incidentId, String idempotencyKey,
            ClosurePreviewRequest request, String correlationId) {
        requireActor(tenantId, actorId);
        requireConfirmed(request.explicitConfirmation());
        String key = key(idempotencyKey);
        String fingerprint = fingerprint("PREVIEW_CLOSURE:" + incidentId, request);
        incidents.lockCommandKey(tenantId, actorId, "PREVIEW_CLOSURE", key);
        CommandRow duplicate = incidents.command(tenantId, actorId, "PREVIEW_CLOSURE", key)
                .orElse(null);
        if (duplicate != null) {
            requireFingerprint(duplicate, fingerprint);
            ClosurePreview replay = closures.closurePreviewByCommand(tenantId, duplicate.id())
                    .orElseThrow();
            return new ClosurePreviewCommandResult(replay, receipt(duplicate, true));
        }
        IncidentRow incident = requireIncident(tenantId, incidentId);
        requireActiveVersion(incident, request.expectedIncidentVersion());
        ClosureCounts counts = closures.closureCounts(tenantId, incidentId, incident.snapshotId());
        List<String> warnings = new ArrayList<>();
        if (counts.needsHelp() > 0) warnings.add("NEEDS_HELP_REMAINS");
        if (counts.noResponse() > 0) warnings.add("NO_RESPONSE_REMAINS");
        if (counts.failedUnknown() > 0) warnings.add("DELIVERY_FAILURE_OR_UNKNOWN_REMAINS");
        if (incidents.assemblySummary(tenantId, incidentId, incident.snapshotId()).pending() > 0) {
            warnings.add("ASSEMBLY_CONFIRMATION_PENDING");
        }
        OffsetDateTime now = now();
        UUID previewId = UUID.randomUUID();
        String correlation = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId,
                "PREVIEW_CLOSURE", key, fingerprint, request.reason(), correlation,
                "/v1/admin/workplace/safety/incidents/" + incidentId
                        + "/closure-previews/" + previewId, now);
        ClosurePreview preview = closures.insertClosurePreview(tenantId, actorId, incidentId,
                previewId, command.id(), incident.version(), counts, true, warnings,
                now.plus(ttl), now);
        incidents.completeLocalCommand(tenantId, command.id(), "CLOSURE_PREVIEW_CREATED", now);
        incidents.audit(tenantId, incidentId, actorId, "safety.closure.previewed",
                "CLOSURE_PREVIEW", preview.closurePreviewId(), correlation,
                java.util.Map.of("needsHelp", counts.needsHelp(), "noResponse", counts.noResponse()), now);
        return new ClosurePreviewCommandResult(preview,
                receipt(succeeded(command, now, "CLOSURE_PREVIEW_CREATED"), false));
    }

    @Transactional(readOnly = true)
    public ClosurePreview closure(long tenantId, UUID incidentId, UUID previewId) {
        return closures.closurePreview(tenantId, incidentId, previewId)
                .orElseThrow(() -> notFound("The closure preview was not found."));
    }

    private ActivationPreview activationProjection(long tenantId, PreviewRow row) {
        return new ActivationPreview(row.id(), row.incidentType(), row.severity(), row.siteId(),
                row.floorIds(), row.zoneIds(), row.message(), row.safetyAction(),
                row.assemblyPoint(), row.channels(), audiences.get(tenantId, row.snapshotId(), true),
                connectors.truth(tenantId), row.eligible(), row.limitations(),
                row.expiresAt(), row.createdAt());
    }

    private ScopeRevisionPreview scopeProjection(long tenantId, ScopeRow row) {
        AudienceSnapshot revised = audiences.get(tenantId, row.snapshotId(), true);
        IncidentRow incident = requireIncident(tenantId, row.incidentId());
        AudienceSnapshot current = audiences.get(tenantId, incident.snapshotId(), true);
        return new ScopeRevisionPreview(row.id(), row.incidentId(), row.incidentVersion(),
                row.previousFloors(), row.previousZones(), row.proposedFloors(), row.proposedZones(),
                row.message(), revised, difference(revised.members(), current.members()),
                difference(current.members(), revised.members()), row.expiresAt(), row.createdAt());
    }

    private List<String> audienceLimitations(AudienceSnapshot audience) {
        List<String> result = new ArrayList<>();
        for (SourceSummary source : audience.sources()) {
            if (source.availability() == AvailabilityState.UNAVAILABLE) {
                result.add(source.source().name() + "_UNAVAILABLE");
            } else if (source.availability() == AvailabilityState.PARTIAL) {
                result.add(source.source().name() + "_PARTIAL");
            }
            if (source.freshness() == FreshnessState.STALE) {
                result.add(source.source().name() + "_STALE");
            }
        }
        if (audience.unknownCount() > 0) result.add("UNKNOWN_IDENTITIES_EXCLUDED");
        return result;
    }

    private IncidentRow requireIncident(long tenantId, UUID incidentId) {
        return incidents.incident(tenantId, incidentId)
                .orElseThrow(() -> notFound("The safety incident was not found."));
    }

    private static void requireActiveVersion(IncidentRow incident, long version) {
        if (incident.state() != IncidentState.ACTIVE || incident.version() != version) {
            throw conflict("The active incident version changed.");
        }
    }

    private String fingerprint(String type, Object request) {
        try { return SafetyRepositorySupport.sha256(type + ":" + mapper.writeValueAsString(request)); }
        catch (JsonProcessingException exception) { throw invalid("The preview request is invalid."); }
    }

    private static int difference(List<AudienceMember> left, List<AudienceMember> right) {
        java.util.Set<String> other = right.stream().filter(AudienceMember::included)
                .map(AudienceMember::subjectKeySha256).collect(java.util.stream.Collectors.toSet());
        return Math.toIntExact(left.stream().filter(AudienceMember::included)
                .map(AudienceMember::subjectKeySha256).filter(key -> !other.contains(key)).count());
    }

    private static CommandReceipt receipt(CommandRow row, boolean replay) {
        return new CommandReceipt(row.id(), row.state(), row.statusHref(), replay,
                row.correlationId(), row.acceptedAt());
    }

    private static CommandRow succeeded(CommandRow row, OffsetDateTime now, String code) {
        return new CommandRow(row.id(), row.incidentId(), row.type(), row.fingerprint(),
                CommandState.SUCCEEDED, row.reason(), row.correlationId(), row.statusHref(),
                code, null, row.version() + 1, row.acceptedAt(), now, now);
    }

    private static void requireFingerprint(CommandRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw conflict("The Idempotency-Key was used for a different preview request.");
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

    private static void requireActor(long tenantId, long actorId) {
        if (tenantId <= 0 || actorId <= 0) throw invalid("Positive tenant and actor IDs are required.");
    }

    private static void requireConfirmed(boolean confirmed) {
        if (!confirmed) throw invalid("Explicit confirmation is required.");
    }

    private static void validateScope(List<UUID> floors, List<UUID> zones) {
        if (floors == null || zones == null || (floors.isEmpty() && zones.isEmpty())) {
            throw invalid("At least one floor or zone is required.");
        }
    }

    private static <T> List<T> distinct(List<T> values) {
        return values.stream().distinct().toList();
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
