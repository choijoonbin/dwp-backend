package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsRepository.*;

@Service
public class WorkplaceConnectorOpsService {
    private static final Duration MAX_REPLAY_WINDOW = Duration.ofDays(7);
    private static final Duration MAX_PROVIDER_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final String PROVIDER_SOURCE_PREFIX = "urn:dwp:provider:workplace-connector:";
    private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern FINGERPRINT = Pattern.compile("[A-Za-z0-9:_-]{8,128}");

    private final WorkplaceConnectorOpsRepository repository;
    private final WorkplaceSpatialGovernanceRepository audits;
    private final ObjectMapper objectMapper;
    private final List<WorkplaceConnectorReplayAdapter> adapters;
    private final Duration freshness;
    private final Duration previewTtl;
    private final Duration sensitivePayloadRetention;
    private final Clock clock;

    @Autowired
    public WorkplaceConnectorOpsService(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceSpatialGovernanceRepository audits,
            ObjectMapper objectMapper,
            List<WorkplaceConnectorReplayAdapter> adapters,
            @Value("${dwp.workplace.connector-runtime.freshness:PT5M}") Duration freshness,
            @Value("${dwp.workplace.connector-runtime.replay-preview-ttl:PT10M}") Duration previewTtl,
            @Value("${dwp.workplace.connector-runtime.sensitive-payload-retention:PT2160H}")
            Duration sensitivePayloadRetention) {
        this(repository, audits, objectMapper, adapters, freshness, previewTtl,
                sensitivePayloadRetention, Clock.systemUTC());
    }

    WorkplaceConnectorOpsService(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceSpatialGovernanceRepository audits,
            ObjectMapper objectMapper,
            List<WorkplaceConnectorReplayAdapter> adapters,
            Duration freshness,
            Duration previewTtl,
            Duration sensitivePayloadRetention,
            Clock clock) {
        this.repository = repository;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.adapters = List.copyOf(adapters);
        this.freshness = positiveDuration(freshness, "freshness");
        this.previewTtl = positiveDuration(previewTtl, "previewTtl");
        this.sensitivePayloadRetention = positiveDuration(
                sensitivePayloadRetention, "sensitivePayloadRetention");
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConnectorOperations operations(long tenantId) {
        requireTenant(tenantId);
        OffsetDateTime now = now();
        List<RuntimeRow> persisted = repository.runtimes(tenantId);
        List<ConnectorRuntimeTruth> result = new ArrayList<>();
        for (ConnectorKind kind : ConnectorKind.values()) {
            RuntimeRow row = persisted.stream().filter(candidate -> candidate.kind() == kind)
                    .findFirst().orElse(null);
            result.add(runtime(kind, row, now));
        }
        result.sort(Comparator.comparing(value -> value.kind().name()));
        return new ConnectorOperations(List.copyOf(result), now);
    }

    @Transactional(readOnly = true)
    public ConnectorRuntimeTruth detail(long tenantId, ConnectorKind kind) {
        requireTenant(tenantId);
        if (kind == null) throw invalid("Connector kind is required.");
        OffsetDateTime now = now();
        return runtime(kind, repository.runtime(tenantId, kind).orElse(null), now);
    }

    /** Called only by the provider event adapter. No administrator route exposes this method. */
    @Transactional
    public boolean observeProvider(ProviderObservation observation) {
        validateObservation(observation);
        RuntimeRow configuration = repository.runtime(observation.tenantId(), observation.kind())
                .orElseThrow(() -> conflict("The connector configuration does not exist."));
        // The observation is retained as evidence even when the provider/configuration version
        // differs. Public state derivation below refuses to classify that evidence as healthy.
        if (configuration.configuredProvider() == null) {
            throw conflict("The connector provider has not been configured.");
        }
        return repository.appendObservation(observation);
    }

    /** Persists a runtime poll produced by an approved adapter; the sequence is allocated under lock. */
    @Transactional
    public boolean observePolledRuntime(
            RuntimeRow expected,
            WorkplaceConnectorReplayAdapter.RuntimeObservation value) {
        if (expected == null || value == null) throw invalid("The runtime observation is incomplete.");
        repository.lockRuntimeObservation(expected.tenantId(), expected.kind());
        RuntimeRow current = repository.runtime(expected.tenantId(), expected.kind())
                .orElseThrow(() -> conflict("The connector configuration does not exist."));
        if (!current.enabled()
                || current.configurationVersion() != expected.configurationVersion()
                || !Objects.equals(current.configuredProvider(), expected.configuredProvider())
                || !Objects.equals(current.configurationReference(), expected.configurationReference())) {
            throw versionConflict("Connector configuration changed during runtime observation.");
        }
        OffsetDateTime receivedAt = now();
        ProviderObservation observation = new ProviderObservation(
                UUID.randomUUID(), current.tenantId(), current.kind(),
                PROVIDER_SOURCE_PREFIX + current.configuredProvider(), current.configuredProvider(),
                current.configurationVersion(), value.adapterId(), value.adapterVersion(),
                value.state(), value.capabilities(), value.sourceObservedAt(), receivedAt,
                value.lastSuccessAt(), value.lagSeconds(), value.checkpointReference(),
                value.retryQueueDepth(), value.deadLetterQueueDepth(), value.errorCode(),
                current.observationSequence() == null ? 1 : current.observationSequence() + 1,
                value.payloadFingerprint());
        validateObservation(observation);
        return repository.appendObservation(observation);
    }

    @Transactional
    public ReplayPreview preview(
            long tenantId,
            long actorId,
            ConnectorKind kind,
            String idempotencyKey,
            ReplayPreviewRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        validatePreviewRequest(request);
        String requestFingerprint = fingerprint(kind, request);
        String correlation = normalizeCorrelation(correlationId);
        repository.lockPreviewCommand(tenantId, actorId, key);
        PreviewCommandRow existing = repository.previewByIdempotency(tenantId, actorId, key)
                .orElse(null);
        if (existing != null) {
            if (existing.kind() != kind
                    || !existing.requestFingerprint().equals(requestFingerprint)) {
                throw conflict("The idempotency key was already used for a different replay preview.");
            }
            return existing.preview();
        }
        RuntimeRow row = repository.runtime(tenantId, kind)
                .orElseThrow(() -> conflict("The connector is not configured."));
        requireVersions(row, request.configurationVersion(), request.runtimeVersion());

        OffsetDateTime now = now();
        List<String> limitations = new ArrayList<>();
        WorkplaceConnectorReplayAdapter.ProviderContext context = providerContext(row);
        WorkplaceConnectorReplayAdapter adapter = adapter(context);
        boolean truthMatches = runtimeMatchesConfiguration(row);
        if (!row.enabled()) limitations.add("CONNECTOR_DISABLED");
        if (!truthMatches) limitations.add("RUNTIME_TRUTH_UNVERIFIED");
        if (truthMatches && !row.capabilities().contains(Capability.REPLAY)) {
            limitations.add("PROVIDER_REPLAY_CAPABILITY_NOT_OBSERVED");
        }
        if (adapter == null) limitations.add("APPROVED_REPLAY_ADAPTER_UNAVAILABLE");

        long estimated = 0;
        if (limitations.isEmpty()) {
            try {
                WorkplaceConnectorReplayAdapter.PreviewEstimate estimate = adapter.preview(
                        context, request.from(), request.to(),
                        request.failedOnly(), request.maximumRecords());
                estimated = Math.min(estimate.estimatedRecords(), request.maximumRecords());
                limitations.addAll(estimate.limitations());
            } catch (RuntimeException providerFailure) {
                limitations.add("PROVIDER_PREVIEW_UNAVAILABLE");
            }
        }

        ReplayPreview preview = new ReplayPreview(UUID.randomUUID(), kind, row.configuredProvider(),
                request.from(), request.to(), request.failedOnly(), request.maximumRecords(), estimated,
                limitations.isEmpty(), List.copyOf(limitations), row.configurationVersion(),
                Objects.requireNonNull(row.runtimeVersion()), now.plus(previewTtl), now);
        repository.savePreview(preview, tenantId, actorId);
        repository.savePreviewCommand(new PreviewCommandRow(
                UUID.randomUUID(), tenantId, kind, actorId, key, requestFingerprint,
                preview, correlation, now));
        audits.appendAudit(tenantId, actorId, "workplace.connector.replay.previewed",
                "WORKPLACE_CONNECTOR_REPLAY_PREVIEW", preview.previewId(), correlation,
                objectMapper.valueToTree(new ReplayPreviewAuditSnapshot(
                        kind, preview.provider(), preview.configurationVersion(),
                        preview.runtimeVersion(), preview.estimatedRecords(), preview.eligible())));
        return preview;
    }

    @Transactional
    public ReplayStartResponse startReplay(
            long tenantId,
            long actorId,
            ConnectorKind kind,
            String idempotencyKey,
            ReplayStartRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        String key = requireIdempotencyKey(idempotencyKey);
        validateStartRequest(request);
        String fingerprint = fingerprint(kind, request);
        String correlation = normalizeCorrelation(correlationId);

        repository.lockConnectorReplay(tenantId, kind);

        ReplayJobRow existing = repository.replayByIdempotency(tenantId, actorId, key).orElse(null);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint) || existing.kind() != kind) {
                throw conflict("The idempotency key was already used for a different replay command.");
            }
            return response(existing, true);
        }

        if (repository.activeReplay(tenantId, kind).isPresent()) {
            throw conflict("Another replay is already active for this connector.");
        }

        ReplayPreview preview = repository.previewOwnedBy(
                        tenantId, kind, request.previewId(), actorId)
                .orElseThrow(() -> notFound(
                        "The replay preview was not found for this tenant, connector and actor."));
        OffsetDateTime now = now();
        if (!preview.expiresAt().isAfter(now)) throw conflict("The replay preview expired. Create a new preview.");
        if (!preview.eligible()) throw conflict("The replay preview is not eligible for execution.");
        if (preview.configurationVersion() != request.configurationVersion()
                || preview.runtimeVersion() != request.runtimeVersion()) {
            throw versionConflict("The replay preview version does not match the command.");
        }
        RuntimeRow row = repository.runtime(tenantId, kind)
                .orElseThrow(() -> conflict("The connector is not configured."));
        requireVersions(row, request.configurationVersion(), request.runtimeVersion());
        if (!runtimeMatchesConfiguration(row) || !row.enabled()) {
            throw conflict("Current connector truth is not eligible for replay.");
        }
        WorkplaceConnectorReplayAdapter.ProviderContext context = providerContext(row);
        if (adapter(context) == null) {
            throw conflict("An approved replay adapter is not available.");
        }

        ReplayJobRow created = new ReplayJobRow(UUID.randomUUID(), preview.previewId(), tenantId, kind,
                row.configuredProvider(), ReplayState.QUEUED, request.reason().trim(), key, fingerprint,
                actorId, correlation, null, null, row.configurationReference(), row.configurationVersion(),
                Objects.requireNonNull(row.runtimeVersion()), 1, now, null, null, now,
                now.plus(sensitivePayloadRetention));
        if (!repository.createReplay(created)) {
            ReplayJobRow raced = repository.replayByIdempotency(tenantId, actorId, key)
                    .orElseThrow(() -> conflict("The replay command could not be created."));
            if (!raced.requestFingerprint().equals(fingerprint) || raced.kind() != kind) {
                throw conflict("The idempotency key was already used for a different replay command.");
            }
            return response(raced, true);
        }
        audits.appendAudit(tenantId, actorId, "workplace.connector.replay.started",
                "WORKPLACE_CONNECTOR_REPLAY", created.jobId(), correlation,
                objectMapper.valueToTree(new ReplayAuditSnapshot(kind, created.provider(),
                        created.previewId(), created.configurationVersion(), created.runtimeVersion(),
                        sha256(created.reason()), created.state().name())));
        return response(created, false);
    }

    @Transactional(readOnly = true)
    public ReplayJob replay(long tenantId, ConnectorKind kind, UUID jobId) {
        requireTenant(tenantId);
        return toReplayJob(repository.replay(tenantId, kind, jobId)
                .orElseThrow(() -> notFound("The replay job was not found for this tenant and connector.")));
    }

    private ConnectorRuntimeTruth runtime(ConnectorKind kind, RuntimeRow row, OffsetDateTime evaluatedAt) {
        if (row == null) {
            return new ConnectorRuntimeTruth(kind, null, false, RuntimeState.NOT_CONFIGURED,
                    null, List.of(), 0, null, null, null, null, null, null,
                    null, null, null, null, null, evaluatedAt);
        }
        RuntimeState state;
        if (!row.enabled()) state = RuntimeState.DISABLED;
        else if (!runtimeMatchesConfiguration(row)) state = RuntimeState.CONFIGURED_UNVERIFIED;
        else if (row.receivedAt().isBefore(evaluatedAt.minus(freshness))) state = RuntimeState.STALE;
        else if (row.activeReplayJobId() != null) state = RuntimeState.REPLAYING;
        else if (row.reportedState() == ProviderReportedState.HEALTHY) state = RuntimeState.HEALTHY;
        else state = RuntimeState.DEGRADED;
        return new ConnectorRuntimeTruth(kind, row.configuredProvider(), row.enabled(), state,
                row.reportedState(), row.capabilities(), row.configurationVersion(),
                row.observedConfigurationVersion(), row.runtimeVersion(), row.sourceObservedAt(),
                row.receivedAt(), row.lastSuccessAt(), row.lagSeconds(), row.checkpointReference(),
                row.retryQueueDepth(), row.deadLetterQueueDepth(), row.errorCode(),
                row.activeReplayJobId(), evaluatedAt);
    }

    private boolean runtimeMatchesConfiguration(RuntimeRow row) {
        return row.observationId() != null
                && row.enabled()
                && row.configuredProvider() != null
                && row.configuredProvider().equals(row.observedProvider())
                && row.observedConfigurationVersion() != null
                && row.configurationVersion() == row.observedConfigurationVersion()
                && row.runtimeVersion() != null;
    }

    private void requireVersions(RuntimeRow row, long configurationVersion, long runtimeVersion) {
        if (row.configurationVersion() != configurationVersion
                || row.runtimeVersion() == null
                || row.runtimeVersion() != runtimeVersion) {
            throw versionConflict("Connector configuration or runtime truth changed. Refresh before retrying.");
        }
    }

    WorkplaceConnectorReplayAdapter adapter(WorkplaceConnectorReplayAdapter.ProviderContext context) {
        if (context.provider() == null || context.credentialReference() == null) return null;
        return adapters.stream().filter(candidate -> candidate.ready(context)).findFirst().orElse(null);
    }

    static WorkplaceConnectorReplayAdapter.ProviderContext providerContext(RuntimeRow row) {
        return new WorkplaceConnectorReplayAdapter.ProviderContext(row.tenantId(), row.kind(),
                row.configuredProvider(), row.configurationReference());
    }

    private ReplayStartResponse response(ReplayJobRow row, boolean replayed) {
        ReplayJob job = toReplayJob(row);
        return new ReplayStartResponse(job, new CommandReceipt(job.jobId(), job.state(), job.requestedAt(),
                "/v1/admin/workplace/connectors/" + job.kind().name()
                        + "/replays/" + job.jobId(), replayed, row.correlationId()));
    }

    private ReplayJob toReplayJob(ReplayJobRow row) {
        return new ReplayJob(row.jobId(), row.previewId(), row.kind(), row.provider(), row.state(),
                row.reason(), row.providerOperationReference(), row.resultSummary(),
                row.configurationVersion(), row.runtimeVersion(), row.version(), row.requestedAt(),
                row.startedAt(), row.finishedAt(), row.updatedAt());
    }

    private void validateObservation(ProviderObservation observation) {
        if (observation == null || observation.observationId() == null || observation.tenantId() <= 0
                || observation.kind() == null || observation.reportedState() == null
                || observation.capabilities() == null || observation.sourceObservedAt() == null
                || observation.receivedAt() == null || observation.sequence() <= 0
                || observation.configurationVersion() < 0
                || blank(observation.provider()) || blank(observation.adapterId())
                || blank(observation.adapterVersion()) || blank(observation.payloadFingerprint())) {
            throw invalid("The provider observation is incomplete.");
        }
        if (!PROVIDER.matcher(observation.provider()).matches()
                || observation.adapterId().length() > 120
                || observation.adapterVersion().length() > 80
                || !FINGERPRINT.matcher(observation.payloadFingerprint()).matches()
                || (observation.checkpointReference() != null
                        && observation.checkpointReference().length() > 320)
                || (observation.errorCode() != null && observation.errorCode().length() > 120)) {
            throw invalid("The provider observation contains an invalid identifier.");
        }
        String expectedSource = PROVIDER_SOURCE_PREFIX + observation.provider();
        if (!expectedSource.equals(observation.source())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The event source does not own this connector provider observation.");
        }
        OffsetDateTime now = now();
        if (observation.receivedAt().isAfter(now.plus(MAX_PROVIDER_CLOCK_SKEW))
                || observation.sourceObservedAt().isAfter(observation.receivedAt().plus(MAX_PROVIDER_CLOCK_SKEW))) {
            throw invalid("The provider observation timestamp is outside the accepted clock skew.");
        }
    }

    private void validatePreviewRequest(ReplayPreviewRequest request) {
        if (request == null || request.from() == null || request.to() == null
                || !request.to().isAfter(request.from())) {
            throw invalid("Replay from/to must define a positive interval.");
        }
        if (Duration.between(request.from(), request.to()).compareTo(MAX_REPLAY_WINDOW) > 0) {
            throw invalid("A replay preview cannot span more than seven days.");
        }
        if (request.maximumRecords() < 1 || request.maximumRecords() > 100_000) {
            throw invalid("maximumRecords must be between 1 and 100000.");
        }
    }

    private void validateStartRequest(ReplayStartRequest request) {
        if (request == null || request.previewId() == null || blank(request.reason())) {
            throw invalid("Preview and reason are required.");
        }
        if (request.reason().trim().length() > 500) throw invalid("Reason is too long.");
        if (!request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required before replay execution.");
        }
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{1,160}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Idempotency-Key must contain 1 to 160 visible ASCII characters.");
        }
        return value;
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new BaseException(ErrorCode.TENANT_MISSING, "A positive tenant identifier is required.");
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw new BaseException(ErrorCode.UNAUTHORIZED, "A positive actor identifier is required.");
    }

    private String fingerprint(ConnectorKind kind, ReplayStartRequest request) {
        return sha256(String.join("\n", kind.name(), request.previewId().toString(),
                Long.toString(request.configurationVersion()), Long.toString(request.runtimeVersion()),
                request.reason().trim(), Boolean.toString(request.explicitConfirmation())));
    }

    private String fingerprint(ConnectorKind kind, ReplayPreviewRequest request) {
        return sha256(String.join("\n", kind.name(), request.from().toInstant().toString(),
                request.to().toInstant().toString(), Boolean.toString(request.failedOnly()),
                Integer.toString(request.maximumRecords()),
                Long.toString(request.configurationVersion()),
                Long.toString(request.runtimeVersion())));
    }

    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String normalizeCorrelation(String value) {
        if (blank(value)) return UUID.randomUUID().toString();
        String normalized = value.trim();
        if (normalized.length() > 160) throw invalid("Correlation identifier is too long.");
        return normalized;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private static BaseException conflict(String message) { return new BaseException(ErrorCode.RESOURCE_CONFLICT, message); }
    private static BaseException versionConflict(String message) { return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message); }
    private static BaseException notFound(String message) { return new BaseException(ErrorCode.NOT_FOUND, message); }

    private record ReplayAuditSnapshot(
            ConnectorKind kind,
            String provider,
            UUID previewId,
            long configurationVersion,
            long runtimeVersion,
            String reasonSha256,
            String state) { }

    private record ReplayPreviewAuditSnapshot(
            ConnectorKind kind,
            String provider,
            long configurationVersion,
            long runtimeVersion,
            long estimatedRecords,
            boolean eligible) { }

}
